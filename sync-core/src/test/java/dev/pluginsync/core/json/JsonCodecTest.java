package dev.pluginsync.core.json;

import dev.pluginsync.core.config.ModSource;
import dev.pluginsync.core.config.ServerConfig;
import dev.pluginsync.core.config.ServerModConfigEntry;
import dev.pluginsync.core.model.ManagedState;
import dev.pluginsync.core.model.Side;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonCodecTest {

    @Test
    void managedStateRoundTripsThroughFile(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("managed-mods.json");
        ManagedState state = new ManagedState();
        state.setLastServerAddress("http://example.com");
        state.managedFiles().put("a.jar", "hash-a");
        state.managedFiles().put("b.jar", "hash-b");

        JsonCodec.writeFile(file, state);
        ManagedState loaded = JsonCodec.readFile(file, ManagedState.class);

        assertEquals("http://example.com", loaded.lastServerAddress());
        assertEquals(2, loaded.managedFiles().size());
        assertEquals("hash-a", loaded.managedFiles().get("a.jar"));
    }

    @Test
    void serverConfigRoundTripsThroughFile(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("pluginsync-server.json");
        ServerConfig config = ServerConfig.createDefault("My Server", "play.example.com");
        config.mods().add(new ServerModConfigEntry("jei.jar", ModSource.MODRINTH, "abc123", Side.BOTH));

        JsonCodec.writeFile(file, config);
        ServerConfig loaded = JsonCodec.readFile(file, ServerConfig.class);

        assertEquals("My Server", loaded.serverName());
        assertEquals(1, loaded.mods().size());
        assertEquals(ModSource.MODRINTH, loaded.mods().get(0).source());
        assertEquals("abc123", loaded.mods().get(0).modrinthVersionId());
    }

    @Test
    void writeIsAtomicAndOverwritesExistingFile(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("state.json");
        ManagedState first = new ManagedState();
        first.managedFiles().put("a.jar", "1");
        JsonCodec.writeFile(file, first);

        ManagedState second = new ManagedState();
        second.managedFiles().put("b.jar", "2");
        JsonCodec.writeFile(file, second);

        ManagedState loaded = JsonCodec.readFile(file, ManagedState.class);
        assertTrue(loaded.managedFiles().containsKey("b.jar"));
        assertTrue(!loaded.managedFiles().containsKey("a.jar"));
    }
}
