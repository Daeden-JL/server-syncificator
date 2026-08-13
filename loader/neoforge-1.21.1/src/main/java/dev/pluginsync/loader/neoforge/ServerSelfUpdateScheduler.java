package dev.pluginsync.loader.neoforge;

import dev.pluginsync.core.config.ServerConfig;
import dev.pluginsync.core.selfupdate.SelfUpdateChecker;
import dev.pluginsync.core.selfupdate.SelfUpdateSession;
import net.neoforged.fml.ModList;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Checks GitHub for a newer syncificator release on server startup, then once a day while the
 * server keeps running, and downloads it if found - so an admin doesn't have to notice a release
 * and manually swap the jar in themselves. Never restarts the server: the new jar only takes
 * effect the next time an admin restarts it (see {@code SelfUpdateSession}), and clients then pick
 * it up the same way they pick up any other mod update, since the mods folder is the same thing
 * {@code ManifestBuilder} advertises to them.
 */
final class ServerSelfUpdateScheduler {

    private static final String GITHUB_OWNER = "Daeden-JL";
    private static final String GITHUB_REPO = "server-syncificator";
    private static final String JAR_NAME_PREFIX = "daedens-server-syncificator-neoforge-1.21.1-";
    private static final long CHECK_INTERVAL_HOURS = 24;

    private static final Logger LOGGER = Logger.getLogger("daedens_server_syncificator");

    private ServerSelfUpdateScheduler() {
    }

    static void start(ServerConfig config, Path modsDir) {
        if (!config.selfUpdateEnabled()) {
            return;
        }
        String currentVersion = currentVersion();
        if (currentVersion == null) {
            LOGGER.warning("Daeden's Server Syncificator: could not determine this mod's own running "
                    + "version - self-update checks are disabled this session.");
            return;
        }
        Path currentJarPath = modsDir.resolve(JAR_NAME_PREFIX + currentVersion + ".jar");
        // Logged unconditionally (not just on a found update) so a wrongly-resolved version -
        // e.g. if the jar manifest ever stops carrying Implementation-Version again - shows up
        // immediately in the log, instead of manifesting only as an endless redundant re-download.
        LOGGER.info("Daeden's Server Syncificator: self-update checks enabled - this server is v" + currentVersion + ".");

        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(daemonThreadFactory());
        executor.scheduleAtFixedRate(
                () -> checkOnce(currentVersion, modsDir, currentJarPath), 0, CHECK_INTERVAL_HOURS, TimeUnit.HOURS);
    }

    private static void checkOnce(String currentVersion, Path modsDir, Path currentJarPath) {
        try {
            SelfUpdateSession.Result result = new SelfUpdateSession(new SelfUpdateChecker(GITHUB_OWNER, GITHUB_REPO))
                    .run(currentVersion, JAR_NAME_PREFIX, modsDir, currentJarPath);

            if (result instanceof SelfUpdateSession.Result.Updated updated) {
                LOGGER.info("Daeden's Server Syncificator: downloaded v" + updated.newVersion() + " - "
                        + (updated.pendingDelete()
                                ? "the old jar is queued for removal once this server process exits; "
                                : "the old jar has already been removed; ")
                        + "restart the server to run the new version.");
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Daeden's Server Syncificator: self-update check failed", e);
        }
    }

    /** @return this instance's own running version (e.g. "0.1.4"), or null if it can't be determined. */
    private static String currentVersion() {
        return ModList.get().getModContainerById(PluginSyncNeoForge.MOD_ID)
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse(null);
    }

    private static ThreadFactory daemonThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "daedens-server-syncificator-self-update");
            thread.setDaemon(true);
            return thread;
        };
    }
}
