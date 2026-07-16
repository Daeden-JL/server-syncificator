package dev.pluginsync.core.config;

import dev.pluginsync.core.json.JsonCodec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads and writes the client config file.
 *
 * <p>The key behaviour is {@link #loadOrCreate(Path)} writing a default file when none exists.
 * Without that the mod is invisible: a fresh install has nothing to edit and no hint that a config
 * is even expected, so the sync silently never runs. Creating the file up front means the settings
 * are discoverable on disk after one launch, whether or not the player ever opens the config
 * screen.
 */
public final class ClientConfigStore {

    private ClientConfigStore() {
    }

    /**
     * Loads the config at {@code path}, writing a default file first if it doesn't exist yet.
     *
     * @return the loaded (or newly created) config, always {@link ClientConfig#normalize() normalized}
     * @throws IOException if an existing file can't be read/parsed, or a default can't be written.
     *     Callers should surface this rather than swallow it - a config the player edited and
     *     typo'd should say so, not look like "not configured".
     */
    public static ClientConfig loadOrCreate(Path path) throws IOException {
        if (Files.isRegularFile(path)) {
            return JsonCodec.readFile(path, ClientConfig.class).normalize();
        }
        ClientConfig config = ClientConfig.createDefault();
        JsonCodec.writeFile(path, config);
        return config;
    }

    /** Writes {@code config} to {@code path}, creating parent directories as needed. */
    public static void save(Path path, ClientConfig config) throws IOException {
        JsonCodec.writeFile(path, config.normalize());
    }
}
