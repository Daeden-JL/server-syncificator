package dev.pluginsync.core.server;

import dev.pluginsync.core.config.ModSource;
import dev.pluginsync.core.config.ServerConfig;
import dev.pluginsync.core.config.ServerModConfigEntry;
import dev.pluginsync.core.model.ModEntry;
import dev.pluginsync.core.model.Side;
import dev.pluginsync.core.model.SyncManifest;
import dev.pluginsync.core.scan.ModrinthClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManifestBuilderTest {

    @Test
    void serverOnlyModsAreExcludedFromClientManifest(@TempDir Path modsDir) throws IOException {
        Files.writeString(modsDir.resolve("client-mod.jar"), "client", StandardCharsets.UTF_8);
        Files.writeString(modsDir.resolve("server-only.jar"), "server", StandardCharsets.UTF_8);

        ServerConfig config = ServerConfig.createDefault("Test", "127.0.0.1");
        config.mods().add(new ServerModConfigEntry("client-mod.jar", ModSource.DIRECT, null, Side.BOTH));
        config.mods().add(new ServerModConfigEntry("server-only.jar", ModSource.DIRECT, null, Side.SERVER_ONLY));

        SyncManifest manifest = new ManifestBuilder(modsDir, config, "http://host:1234").build();

        assertEquals(1, manifest.mods().size());
        assertEquals("client-mod.jar", manifest.mods().get(0).fileName());
    }

    @Test
    void missingConfiguredFileIsSkippedNotFatal(@TempDir Path modsDir) throws IOException {
        ServerConfig config = ServerConfig.createDefault("Test", "127.0.0.1");
        config.mods().add(new ServerModConfigEntry("does-not-exist.jar", ModSource.DIRECT, null, Side.BOTH));

        SyncManifest manifest = new ManifestBuilder(modsDir, config, "http://host:1234").build();

        assertTrue(manifest.mods().isEmpty());
    }

    @Test
    void modrinthHashMismatchFallsBackToDirectUrlOnly(@TempDir Path modsDir) throws IOException {
        Files.writeString(modsDir.resolve("mod.jar"), "actual-content", StandardCharsets.UTF_8);

        ServerConfig config = ServerConfig.createDefault("Test", "127.0.0.1");
        config.mods().add(new ServerModConfigEntry("mod.jar", ModSource.MODRINTH, "some-version-id", Side.BOTH));

        // Fake resolver that returns a hash that does NOT match the real local file - the
        // manifest builder must not trust it and must fall back to direct-only.
        ModrinthClient fakeClient = new ModrinthClient("http://unused") {
            @Override
            public ResolvedFile resolveVersion(String versionId) {
                return new ResolvedFile("mod.jar", "https://cdn.modrinth.com/mod.jar", "mismatched-hash", 123);
            }
        };

        SyncManifest manifest = new ManifestBuilder(
                modsDir, config, "http://host:1234", fakeClient, (msg, err) -> { }).build();

        ModEntry entry = manifest.mods().get(0);
        assertEquals(1, entry.downloadUrls().size());
        assertTrue(entry.downloadUrls().get(0).startsWith("http://host:1234/plugin-sync/v1/files/"));
    }

    @Test
    void modrinthHashMatchAddsExternalUrlFirst(@TempDir Path modsDir) throws IOException {
        Files.writeString(modsDir.resolve("mod.jar"), "actual-content", StandardCharsets.UTF_8);
        String realHash = dev.pluginsync.core.scan.ModsFolderScanner.sha256Hex(modsDir.resolve("mod.jar"));

        ServerConfig config = ServerConfig.createDefault("Test", "127.0.0.1");
        config.mods().add(new ServerModConfigEntry("mod.jar", ModSource.MODRINTH, "some-version-id", Side.BOTH));

        ModrinthClient fakeClient = new ModrinthClient("http://unused") {
            @Override
            public ResolvedFile resolveVersion(String versionId) {
                return new ResolvedFile("mod.jar", "https://cdn.modrinth.com/mod.jar", realHash, 123);
            }
        };

        SyncManifest manifest = new ManifestBuilder(
                modsDir, config, "http://host:1234", fakeClient, (msg, err) -> { }).build();

        ModEntry entry = manifest.mods().get(0);
        assertEquals(2, entry.downloadUrls().size());
        assertEquals("https://cdn.modrinth.com/mod.jar", entry.downloadUrls().get(0));
        assertTrue(entry.downloadUrls().get(1).startsWith("http://host:1234/plugin-sync/v1/files/"));
    }
}
