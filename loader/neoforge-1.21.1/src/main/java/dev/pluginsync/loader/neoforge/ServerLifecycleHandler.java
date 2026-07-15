package dev.pluginsync.loader.neoforge;

import dev.pluginsync.core.config.ServerConfig;
import dev.pluginsync.core.json.JsonCodec;
import dev.pluginsync.core.server.ManifestBuilder;
import dev.pluginsync.core.server.ManifestHttpServer;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Starts the companion manifest HTTP server on dedicated-server startup, reading
 * {@code config/pluginsync-server.json}. Writes a template config (and refuses to start serving)
 * the first time it's run, so an admin always reviews/fills in {@code publicHost} and the mod
 * list before this server starts handing out download URLs.
 */
final class ServerLifecycleHandler {

    private static final Logger LOGGER = Logger.getLogger("pluginsync");
    private static ManifestHttpServer httpServer;

    private ServerLifecycleHandler() {
    }

    static synchronized void start() {
        Path configPath = FMLPaths.CONFIGDIR.get().resolve("pluginsync-server.json");
        ServerConfig config = loadOrCreateDefaultConfig(configPath);
        if (config == null) {
            return;
        }
        if (config.publicHost().isEmpty()) {
            LOGGER.warning("plugin-sync: 'publicHost' is not set in " + configPath + " - refusing to start "
                    + "(clients need a reachable address to download from). Edit the config and restart.");
            return;
        }

        Path modsDir = FMLPaths.MODSDIR.get();
        String directBaseUrl = "http://" + config.publicHost() + ":" + config.httpPort();
        ManifestBuilder manifestBuilder = new ManifestBuilder(modsDir, config, directBaseUrl);

        try {
            httpServer = new ManifestHttpServer(config.httpBind(), config.httpPort(), modsDir, () -> {
                try {
                    return manifestBuilder.build();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            httpServer.start();
            LOGGER.info("plugin-sync manifest server listening on " + config.httpBind() + ":" + config.httpPort());
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "plugin-sync: failed to start manifest server", e);
        }
    }

    private static ServerConfig loadOrCreateDefaultConfig(Path configPath) {
        if (Files.isRegularFile(configPath)) {
            try {
                return JsonCodec.readFile(configPath, ServerConfig.class);
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "plugin-sync: failed to read " + configPath + " - disabled this session", e);
                return null;
            }
        }

        ServerConfig defaultConfig = ServerConfig.createDefault("My Server", "");
        try {
            JsonCodec.writeFile(configPath, defaultConfig);
            LOGGER.info("plugin-sync: wrote a default config to " + configPath + " - edit it (at least 'publicHost' "
                    + "and 'mods'), then restart the server to enable syncing.");
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "plugin-sync: failed to write default config to " + configPath, e);
        }
        return null;
    }
}
