package dev.pluginsync.core.sync;

import dev.pluginsync.core.config.ModSource;
import dev.pluginsync.core.config.ServerConfig;
import dev.pluginsync.core.config.ServerModConfigEntry;
import dev.pluginsync.core.json.JsonCodec;
import dev.pluginsync.core.model.ManagedState;
import dev.pluginsync.core.model.ModEntry;
import dev.pluginsync.core.model.Side;
import dev.pluginsync.core.model.SyncManifest;
import dev.pluginsync.core.relaunch.PendingOperations;
import dev.pluginsync.core.server.ManifestBuilder;
import dev.pluginsync.core.server.ManifestHttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies SyncSession's bookkeeping when a download comes back {@link Downloader.Outcome.Deferred}
 * - the real-world case, confirmed against an actual Windows client twice: any mod jar already
 * loaded by NeoForge this session can't be overwritten in place, for the life of the JVM, not just
 * during a race. A real AccessDeniedException can't be forced through the full stack on this
 * (Linux) sandbox, so this injects a stand-in Downloader that always defers, isolating exactly what
 * SyncSession itself is responsible for: not treating a deferral as a failure, and persisting it
 * correctly for RelaunchHelper.relaunchWithPendingOperations to pick up.
 */
class SyncSessionDeferredOperationsTest {

    @Test
    void deferredDownloadIsRecordedAsPendingOperationNotAFailure(@TempDir Path tempDir) throws IOException {
        Path serverModsDir = tempDir.resolve("server-mods");
        Path clientModsDir = tempDir.resolve("client-mods");
        Path managedStatePath = tempDir.resolve("managed-mods.json");
        Files.createDirectories(serverModsDir);
        Files.createDirectories(clientModsDir);
        Files.writeString(serverModsDir.resolve("mod.jar"), "server-content", StandardCharsets.UTF_8);

        ServerConfig config = ServerConfig.createDefault("Test", "127.0.0.1");
        config.mods().add(new ServerModConfigEntry("mod.jar", ModSource.DIRECT, null, Side.BOTH));

        AtomicReference<SyncManifest> manifestRef = new AtomicReference<>(SyncManifest.of("", "", java.util.List.of()));
        try (ManifestHttpServer httpServer = new ManifestHttpServer("127.0.0.1", 0, serverModsDir, manifestRef::get)) {
            httpServer.start();
            String baseUrl = "http://127.0.0.1:" + httpServer.port();
            manifestRef.set(new ManifestBuilder(serverModsDir, config, baseUrl).build());

            SyncSession session = new SyncSession(
                    baseUrl,
                    clientModsDir,
                    managedStatePath,
                    new dev.pluginsync.core.http.ManifestHttpClient(),
                    new AlwaysDeferDownloader(),
                    event -> { });

            SyncEvent.Complete result = session.run();

            assertTrue(result.restartRequired());
            assertTrue(Files.exists(session.pendingOperationsPath()), "pending operations file should have been written");

            PendingOperations pending = JsonCodec.readFile(session.pendingOperationsPath(), PendingOperations.class);
            assertEquals(1, pending.renames().size());
            assertTrue(pending.renames().get(0).tempPath().endsWith("mod.jar.psync-pending"));
            assertTrue(pending.renames().get(0).finalPath().endsWith("mod.jar"));

            ManagedState state = JsonCodec.readFile(managedStatePath, ManagedState.class);
            assertTrue(state.managedFiles().containsKey("mod.jar"), "managed state should optimistically record the deferred file");
        }
    }

    /** Always reports the download as deferred, without touching the network or filesystem beyond the stand-in temp file. */
    private static final class AlwaysDeferDownloader extends Downloader {
        @Override
        public Outcome download(ModEntry entry, Path destDir, ProgressListener progressListener) throws IOException {
            Path deferredPath = destDir.resolve(entry.fileName() + ".psync-pending");
            Files.writeString(deferredPath, "deferred-content", StandardCharsets.UTF_8);
            return new Outcome.Deferred(deferredPath, destDir.resolve(entry.fileName()));
        }
    }
}
