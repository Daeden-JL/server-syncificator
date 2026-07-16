package dev.pluginsync.loader.forge;

import dev.pluginsync.core.config.ServerConfig;
import dev.pluginsync.core.config.ServerConfigStore;
import dev.pluginsync.core.json.JsonCodec;
import dev.pluginsync.core.scan.ModrinthClient;
import dev.pluginsync.core.server.ManifestBuilder;
import dev.pluginsync.core.server.ManifestHttpServer;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Starts the companion manifest HTTP server on dedicated-server startup, reading
 * {@code config/daedens-server-syncificator-server.json}. Writes a template config (and refuses to
 * start serving) the first time it's run, so an admin always reviews/fills in {@code publicHost}
 * before this server starts handing out download URLs.
 *
 * <p>On every start the config's mod list is reconciled with the mods folder and written back, so
 * the file on disk always names exactly what clients will be offered.
 */
final class ServerLifecycleHandler {

    private static final Logger LOGGER = Logger.getLogger("daedens_server_syncificator");
    private static ManifestHttpServer httpServer;

    private ServerLifecycleHandler() {
    }

    static synchronized void start() {
        Path configPath = FMLPaths.CONFIGDIR.get().resolve("daedens-server-syncificator-server.json");
        Path modsDir = FMLPaths.MODSDIR.get();

        ServerConfig config = loadOrCreateDefaultConfig(configPath, modsDir);
        if (config == null) {
            return;
        }
        reconcileModList(config, modsDir, configPath);

        if (config.publicHost().isEmpty()) {
            LOGGER.warning("Daeden's Server Syncificator: 'publicHost' is not set in " + configPath + " - refusing to start "
                    + "(clients need a reachable address to download from). Edit the config and restart.");
            return;
        }

        String directBaseUrl = "http://" + config.publicHost() + ":" + config.httpPort();
        // The 3-arg constructor defaults to a no-op warning logger, which silently swallowed every
        // "configured mod isn't on disk"-style warning the builder produces.
        ManifestBuilder manifestBuilder = new ManifestBuilder(modsDir, config, directBaseUrl, new ModrinthClient(),
                (message, error) -> LOGGER.log(Level.WARNING, "Daeden's Server Syncificator: " + message, error));

        try {
            httpServer = new ManifestHttpServer(config.httpBind(), config.httpPort(), modsDir, () -> {
                try {
                    return manifestBuilder.build();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            httpServer.start();
            LOGGER.info("Daeden's Server Syncificator manifest server listening on " + config.httpBind() + ":" + config.httpPort());
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Daeden's Server Syncificator: failed to start manifest server", e);
        }
    }

    /**
     * Writes the mod list back to the config so it always shows exactly what clients are told
     * about, and an admin can review it (and mark anything server-side) by reading one file.
     * Non-fatal: the in-memory list is already correct, so a failed write only costs visibility.
     */
    private static void reconcileModList(ServerConfig config, Path modsDir, Path configPath) {
        try {
            if (!ServerConfigStore.reconcileWithModsFolder(config, modsDir)) {
                return;
            }
            ServerConfigStore.save(configPath, config);
            LOGGER.info("Daeden's Server Syncificator: updated the 'mods' list in " + configPath + " to match "
                    + modsDir + " - it now lists every mod clients will be offered. Review it and set"
                    + " \"side\": \"SERVER_ONLY\" on anything clients shouldn't get.");
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Daeden's Server Syncificator: could not update the mod list in " + configPath
                    + " - syncing still works, but the file won't reflect what's being served", e);
        }
    }

    private static ServerConfig loadOrCreateDefaultConfig(Path configPath, Path modsDir) {
        if (Files.isRegularFile(configPath)) {
            try {
                return JsonCodec.readFile(configPath, ServerConfig.class);
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Daeden's Server Syncificator: failed to read " + configPath + " - disabled this session", e);
                return null;
            }
        }

        ServerConfig defaultConfig = ServerConfig.createDefault("My Server", "");
        try {
            // Populate the list before the first write, so the very first config an admin opens
            // already names their mods instead of being an empty array they have to fill in.
            ServerConfigStore.reconcileWithModsFolder(defaultConfig, modsDir);
            JsonCodec.writeFile(configPath, defaultConfig);
            LOGGER.info("Daeden's Server Syncificator: wrote a default config to " + configPath + " listing "
                    + defaultConfig.mods().size() + " mod(s) found in " + modsDir + " - set 'publicHost', review the"
                    + " 'mods' list, then restart the server to enable syncing.");
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Daeden's Server Syncificator: failed to write default config to " + configPath, e);
        }
        return null;
    }
}
