package dev.pluginsync.core.config;

import dev.pluginsync.core.json.JsonCodec;
import dev.pluginsync.core.model.Side;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerConfigStoreTest {

    private static Path modsFolderWith(Path dir, String... fileNames) throws IOException {
        Path modsDir = Files.createDirectories(dir.resolve("mods"));
        for (String fileName : fileNames) {
            Files.writeString(modsDir.resolve(fileName), "jar", StandardCharsets.UTF_8);
        }
        return modsDir;
    }

    private static List<String> listedNames(ServerConfig config) {
        return config.mods().stream().map(ServerModConfigEntry::fileName).toList();
    }

    private static ServerModConfigEntry entryFor(ServerConfig config, String fileName) {
        return config.mods().stream().filter(e -> e.fileName().equals(fileName)).findFirst().orElseThrow();
    }

    @Test
    void listsEveryJarFromTheModsFolder(@TempDir Path tempDir) throws IOException {
        Path modsDir = modsFolderWith(tempDir, "jei.jar", "create.jar");
        ServerConfig config = ServerConfig.createDefault("Test", "host");

        boolean changed = ServerConfigStore.reconcileWithModsFolder(config, modsDir);

        assertTrue(changed);
        assertEquals(List.of("create.jar", "jei.jar"), listedNames(config), "listed alphabetically for readability");
    }

    @Test
    void newlyListedModsDefaultToBeingSentToClients(@TempDir Path tempDir) throws IOException {
        Path modsDir = modsFolderWith(tempDir, "jei.jar");
        ServerConfig config = ServerConfig.createDefault("Test", "host");

        ServerConfigStore.reconcileWithModsFolder(config, modsDir);

        ServerModConfigEntry entry = entryFor(config, "jei.jar");
        assertEquals(Side.BOTH, entry.side());
        assertEquals(ModSource.DIRECT, entry.source());
    }

    @Test
    void reconcilingAnUnchangedFolderReportsNoChange(@TempDir Path tempDir) throws IOException {
        Path modsDir = modsFolderWith(tempDir, "jei.jar");
        ServerConfig config = ServerConfig.createDefault("Test", "host");
        ServerConfigStore.reconcileWithModsFolder(config, modsDir);

        boolean changedAgain = ServerConfigStore.reconcileWithModsFolder(config, modsDir);

        assertFalse(changedAgain, "an unchanged folder must not keep rewriting the config file");
    }

    @Test
    void anAdminsServerOnlyMarkingSurvivesReconciling(@TempDir Path tempDir) throws IOException {
        Path modsDir = modsFolderWith(tempDir, "admin-tools.jar");
        ServerConfig config = ServerConfig.createDefault("Test", "host");
        config.mods().add(new ServerModConfigEntry("admin-tools.jar", ModSource.DIRECT, null, Side.SERVER_ONLY));

        ServerConfigStore.reconcileWithModsFolder(config, modsDir);

        assertEquals(Side.SERVER_ONLY, entryFor(config, "admin-tools.jar").side(), "must not be reset to BOTH");
        assertEquals(1, config.mods().size(), "must not be duplicated");
    }

    @Test
    void aModrinthOverrideSurvivesReconciling(@TempDir Path tempDir) throws IOException {
        Path modsDir = modsFolderWith(tempDir, "jei.jar");
        ServerConfig config = ServerConfig.createDefault("Test", "host");
        config.mods().add(new ServerModConfigEntry("jei.jar", ModSource.MODRINTH, "abcdEFGH", Side.BOTH));

        ServerConfigStore.reconcileWithModsFolder(config, modsDir);

        assertEquals(ModSource.MODRINTH, entryFor(config, "jei.jar").source());
        assertEquals("abcdEFGH", entryFor(config, "jei.jar").modrinthVersionId());
    }

    @Test
    void anEntryWhoseJarIsGoneIsDropped(@TempDir Path tempDir) throws IOException {
        Path modsDir = modsFolderWith(tempDir, "still-here.jar");
        ServerConfig config = ServerConfig.createDefault("Test", "host");
        config.mods().add(new ServerModConfigEntry("deleted.jar", ModSource.DIRECT, null, Side.BOTH));

        boolean changed = ServerConfigStore.reconcileWithModsFolder(config, modsDir);

        assertTrue(changed);
        assertEquals(List.of("still-here.jar"), listedNames(config), "the file should describe reality");
    }

    @Test
    void aServerOnlyEntryIsKeptEvenWithNoJarBehindIt(@TempDir Path tempDir) throws IOException {
        Path modsDir = modsFolderWith(tempDir, "jei.jar");
        ServerConfig config = ServerConfig.createDefault("Test", "host");
        config.mods().add(new ServerModConfigEntry("admin-tools.jar", ModSource.DIRECT, null, Side.SERVER_ONLY));

        ServerConfigStore.reconcileWithModsFolder(config, modsDir);

        // Dropping it would mean putting the jar back later silently starts publishing it.
        assertTrue(listedNames(config).contains("admin-tools.jar"),
                "a SERVER_ONLY marking is a standing rule, not a description of the folder");
    }

    @Test
    void autoServeDisabledDoesNotAddAnything(@TempDir Path tempDir) throws IOException {
        Path modsDir = modsFolderWith(tempDir, "jei.jar", "create.jar");
        ServerConfig config = JsonCodec.fromJson(
                "{\"serverName\":\"Test\",\"publicHost\":\"host\",\"autoServeModsFolder\":false}", ServerConfig.class);

        ServerConfigStore.reconcileWithModsFolder(config, modsDir);

        assertEquals(List.of(), listedNames(config), "strict allowlist mode must stay hand-managed");
    }

    @Test
    void theWrittenConfigRoundTripsAndKeepsTheList(@TempDir Path tempDir) throws IOException {
        Path modsDir = modsFolderWith(tempDir, "jei.jar", "admin-tools.jar");
        Path configPath = tempDir.resolve("server.json");
        ServerConfig config = ServerConfig.createDefault("Test", "host");
        config.mods().add(new ServerModConfigEntry("admin-tools.jar", ModSource.DIRECT, null, Side.SERVER_ONLY));
        ServerConfigStore.reconcileWithModsFolder(config, modsDir);

        ServerConfigStore.save(configPath, config);
        ServerConfig reloaded = ServerConfigStore.loadOrCreate(configPath, "Test");

        assertEquals(List.of("admin-tools.jar", "jei.jar"), listedNames(reloaded));
        assertEquals(Side.SERVER_ONLY, entryFor(reloaded, "admin-tools.jar").side());
        assertTrue(Files.readString(configPath).contains("jei.jar"), "the admin must be able to read the list on disk");
    }
}
