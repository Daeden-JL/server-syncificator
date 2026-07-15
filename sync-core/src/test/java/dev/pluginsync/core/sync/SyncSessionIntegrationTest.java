package dev.pluginsync.core.sync;

import dev.pluginsync.core.config.ModSource;
import dev.pluginsync.core.config.ServerConfig;
import dev.pluginsync.core.config.ServerModConfigEntry;
import dev.pluginsync.core.model.Side;
import dev.pluginsync.core.model.SyncManifest;
import dev.pluginsync.core.server.ManifestBuilder;
import dev.pluginsync.core.server.ManifestHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test of the full sync pipeline: a real {@link ManifestHttpServer} on loopback
 * serving a manifest built by {@link ManifestBuilder} from real files on disk, and a real
 * {@link SyncSession} (client side) fetching, diffing, downloading, and hash-verifying against it.
 * No mocks - this is the actual wire protocol exercised end to end.
 */
class SyncSessionIntegrationTest {

    private Path serverModsDir;
    private Path clientModsDir;
    private Path managedStatePath;
    private ManifestHttpServer httpServer;
    private int boundPort;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws IOException {
        serverModsDir = tempDir.resolve("server-mods");
        clientModsDir = tempDir.resolve("client-mods");
        managedStatePath = tempDir.resolve("managed-mods.json");
        Files.createDirectories(serverModsDir);
        Files.createDirectories(clientModsDir);
    }

    @AfterEach
    void tearDown() {
        if (httpServer != null) {
            httpServer.close();
        }
    }

    private void writeServerMod(String fileName, String content) throws IOException {
        Files.writeString(serverModsDir.resolve(fileName), content, StandardCharsets.UTF_8);
    }

    /** Starts (or restarts) the manifest server on a fresh ephemeral port, serving {@code config}. */
    private void startServer(ServerConfig config) throws IOException {
        if (httpServer != null) {
            httpServer.close();
        }
        // Never let the supplier observe null: initialize with an (empty) placeholder before the
        // server starts accepting connections, so a request racing the real build below always
        // sees a valid manifest rather than a transient null.
        AtomicReference<SyncManifest> manifestRef = new AtomicReference<>(SyncManifest.of("", "", List.of()));
        httpServer = new ManifestHttpServer("127.0.0.1", 0, serverModsDir, manifestRef::get);
        httpServer.start();
        boundPort = httpServer.port();
        manifestRef.set(new ManifestBuilder(serverModsDir, config, baseUrl()).build());
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + boundPort;
    }

    private static ServerConfig configWithMods(String... fileNames) {
        ServerConfig config = ServerConfig.createDefault("Test Server", "127.0.0.1");
        for (String fileName : fileNames) {
            config.mods().add(new ServerModConfigEntry(fileName, ModSource.DIRECT, null, Side.BOTH));
        }
        return config;
    }

    @Test
    void firstSyncDownloadsAllConfiguredMods() throws IOException {
        writeServerMod("mod-a.jar", "content-a");
        writeServerMod("mod-b.jar", "content-b");
        startServer(configWithMods("mod-a.jar", "mod-b.jar"));

        List<SyncEvent> events = new ArrayList<>();
        SyncEvent.Complete result = new SyncSession(baseUrl(), clientModsDir, managedStatePath, events::add).run();

        assertTrue(result.restartRequired());
        assertEquals("content-a", Files.readString(clientModsDir.resolve("mod-a.jar")));
        assertEquals("content-b", Files.readString(clientModsDir.resolve("mod-b.jar")));
        assertTrue(events.stream().anyMatch(e -> e instanceof SyncEvent.Downloading));
        assertTrue(events.get(events.size() - 1) instanceof SyncEvent.Complete);
    }

    @Test
    void secondSyncWithNoServerChangesIsNoOp() throws IOException {
        writeServerMod("mod-a.jar", "content-a");
        startServer(configWithMods("mod-a.jar"));

        new SyncSession(baseUrl(), clientModsDir, managedStatePath, e -> { }).run();
        SyncEvent.Complete second = new SyncSession(baseUrl(), clientModsDir, managedStatePath, e -> { }).run();

        assertFalse(second.restartRequired());
    }

    @Test
    void removingModFromServerDeletesItLocallyButLeavesUserAddedJarsAlone() throws IOException {
        writeServerMod("mod-a.jar", "content-a");
        writeServerMod("mod-b.jar", "content-b");
        startServer(configWithMods("mod-a.jar", "mod-b.jar"));
        new SyncSession(baseUrl(), clientModsDir, managedStatePath, e -> { }).run();

        // Simulate the user manually dropping in their own extra mod - never tracked by us.
        Files.writeString(clientModsDir.resolve("user-added.jar"), "not managed", StandardCharsets.UTF_8);

        // Server admin removes mod-b from the pack.
        startServer(configWithMods("mod-a.jar"));

        SyncEvent.Complete result = new SyncSession(baseUrl(), clientModsDir, managedStatePath, e -> { }).run();

        assertTrue(result.restartRequired());
        assertTrue(Files.exists(clientModsDir.resolve("mod-a.jar")), "mod-a should remain (still in manifest)");
        assertFalse(Files.exists(clientModsDir.resolve("mod-b.jar")), "mod-b should be deleted (removed from manifest)");
        assertTrue(Files.exists(clientModsDir.resolve("user-added.jar")), "user's own jar must never be touched");
    }

    @Test
    void updatedModContentTriggersRedownload() throws IOException {
        writeServerMod("mod-a.jar", "version-1");
        startServer(configWithMods("mod-a.jar"));
        new SyncSession(baseUrl(), clientModsDir, managedStatePath, e -> { }).run();
        assertEquals("version-1", Files.readString(clientModsDir.resolve("mod-a.jar")));

        writeServerMod("mod-a.jar", "version-2-longer-content");
        startServer(configWithMods("mod-a.jar"));

        SyncEvent.Complete result = new SyncSession(baseUrl(), clientModsDir, managedStatePath, e -> { }).run();

        assertTrue(result.restartRequired());
        assertEquals("version-2-longer-content", Files.readString(clientModsDir.resolve("mod-a.jar")));
    }
}
