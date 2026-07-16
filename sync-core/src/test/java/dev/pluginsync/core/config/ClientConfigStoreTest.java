package dev.pluginsync.core.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientConfigStoreTest {

    @Test
    void writesADefaultFileWhenNoneExists(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("client.json");

        ClientConfig config = ClientConfigStore.loadOrCreate(file);

        assertTrue(Files.isRegularFile(file), "a fresh install must end up with a config file to edit");
        assertFalse(config.isConfigured());
        String json = Files.readString(file);
        assertTrue(json.contains("serverHost"), "the written file should show the host field: " + json);
        assertTrue(json.contains("syncPort"), "the written file should show the port field: " + json);
    }

    @Test
    void createsParentDirectoriesForTheConfigFile(@TempDir Path tempDir) throws IOException {
        // Mirrors a fresh client where config/ doesn't exist yet.
        Path file = tempDir.resolve("config").resolve("client.json");

        ClientConfigStore.loadOrCreate(file);

        assertTrue(Files.isRegularFile(file));
    }

    @Test
    void doesNotOverwriteAnExistingConfig(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("client.json");
        ClientConfigStore.save(file, ClientConfig.create("play.example.com", 9000, 25566));

        ClientConfig loaded = ClientConfigStore.loadOrCreate(file);

        assertEquals("play.example.com", loaded.serverHost());
        assertEquals(9000, loaded.syncPort());
        assertEquals(25566, loaded.minecraftPort());
    }

    @Test
    void roundTripsEverySetting(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("client.json");
        ClientConfig original = ClientConfig.create("host", 1234, 25566);
        original.setEnabled(false);
        original.setAutoRestart(false);
        original.setPinToServerList(false);
        original.setServerListName("My Server");

        ClientConfigStore.save(file, original);
        ClientConfig loaded = ClientConfigStore.loadOrCreate(file);

        assertFalse(loaded.enabled());
        assertFalse(loaded.autoRestart());
        assertFalse(loaded.pinToServerList());
        assertEquals("My Server", loaded.serverListName());
        assertEquals("http://host:1234", loaded.syncBaseUrl());
        assertEquals("host:25566", loaded.serverAddress());
    }

    @Test
    void normalizesAHandEditedFileOnLoad(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("client.json");
        Files.writeString(file, "{\"serverHost\":\"  host  \",\"syncPort\":0}");

        ClientConfig loaded = ClientConfigStore.loadOrCreate(file);

        assertEquals("host", loaded.serverHost());
        assertEquals(ServerConfig.DEFAULT_HTTP_PORT, loaded.syncPort(), "a nonsense port should fall back, not build a broken URL");
    }

    @Test
    void reportsACorruptConfigRatherThanLookingUnconfigured(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("client.json");
        Files.writeString(file, "{ this is not json");

        assertThrows(Exception.class, () -> ClientConfigStore.loadOrCreate(file));
    }
}
