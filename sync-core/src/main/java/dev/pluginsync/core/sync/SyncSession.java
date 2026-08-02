package dev.pluginsync.core.sync;

import dev.pluginsync.core.http.ManifestHttpClient;
import dev.pluginsync.core.json.JsonCodec;
import dev.pluginsync.core.model.ManagedState;
import dev.pluginsync.core.model.ModEntry;
import dev.pluginsync.core.model.SyncManifest;
import dev.pluginsync.core.relaunch.PendingOperations;
import dev.pluginsync.core.scan.ModsFolderScanner;
import dev.pluginsync.core.scan.ModsFolderScanner.ScannedFile;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Orchestrates one full sync run: fetch manifest -&gt; diff against local state -&gt; delete
 * stale managed files -&gt; download changed files -&gt; persist new managed state. Meant to be
 * run on a background thread; every step reports a {@link SyncEvent} to the given listener so a
 * loader-specific GUI screen can render progress.
 *
 * <p>Intentionally has no knowledge of Minecraft, Forge, or NeoForge - callers decide what to do
 * with a {@link SyncEvent.Complete#restartRequired()} result (typically: relaunch the client). If
 * {@link #pendingOperationsPath()} exists after {@link #run()} returns, some files couldn't be
 * updated/removed immediately because they were locked by this JVM (on Windows: any mod jar the
 * loader already has open this session, not just a race) - the caller must relaunch via
 * {@code RelaunchHelper.relaunchWithPendingOperations(...)} rather than a plain relaunch, so those
 * changes get applied once this JVM has actually exited.
 */
public final class SyncSession {

    private final String baseUrl;
    private final Path modsDir;
    private final Path managedStatePath;
    private final Path pendingOperationsPath;
    private final ManifestHttpClient manifestClient;
    private final Downloader downloader;
    private final Consumer<SyncEvent> listener;

    public SyncSession(String baseUrl, Path modsDir, Path managedStatePath, Consumer<SyncEvent> listener) {
        this(baseUrl, modsDir, managedStatePath, new ManifestHttpClient(), new Downloader(), listener);
    }

    public SyncSession(
            String baseUrl,
            Path modsDir,
            Path managedStatePath,
            ManifestHttpClient manifestClient,
            Downloader downloader,
            Consumer<SyncEvent> listener) {
        this.baseUrl = baseUrl;
        this.modsDir = modsDir;
        this.managedStatePath = managedStatePath;
        this.pendingOperationsPath = managedStatePath.resolveSibling("daedens-server-syncificator-pending.json");
        this.manifestClient = manifestClient;
        this.downloader = downloader;
        this.listener = listener;
    }

    /** Where a pending-operations file would be written, if any files couldn't be applied immediately. */
    public Path pendingOperationsPath() {
        return pendingOperationsPath;
    }

    /**
     * Runs the sync to completion, emitting events throughout. Throws on the first unrecoverable
     * failure, after emitting a matching {@link SyncEvent.Failed}.
     */
    public SyncEvent.Complete run() throws IOException {
        listener.accept(new SyncEvent.Connecting(baseUrl));
        ManagedState state = loadManagedState();

        listener.accept(new SyncEvent.FetchingManifest());
        SyncManifest manifest = fetchManifest();

        listener.accept(new SyncEvent.Diffing());
        Map<String, ScannedFile> localFiles = scanLocalFiles();
        SyncPlan plan = DiffEngine.diff(manifest, localFiles, state);

        if (plan.isNoOp()) {
            return finish(state, false);
        }

        PendingOperations pendingOperations = new PendingOperations();
        int totalFiles = plan.toDelete().size() + plan.toDownload().size();
        int index = 0;

        for (String fileName : plan.toDelete()) {
            index++;
            listener.accept(new SyncEvent.Deleting(fileName, index, totalFiles));
            try {
                Files.deleteIfExists(modsDir.resolve(fileName));
            } catch (AccessDeniedException lockedException) {
                // Currently loaded by this JVM (on Windows: true of every already-installed mod
                // jar for the life of the session) - defer the removal to the post-relaunch
                // trampoline rather than failing the whole sync.
                pendingOperations.deletes().add(modsDir.resolve(fileName).toString());
            } catch (IOException e) {
                listener.accept(new SyncEvent.Failed("Failed to remove " + fileName + ": " + e.getMessage(), e));
                throw e;
            }
            state.managedFiles().remove(fileName);
        }

        for (ModEntry entry : plan.toDownload()) {
            index++;
            final int currentIndex = index;
            Downloader.Outcome outcome;
            try {
                outcome = downloader.download(entry, modsDir, (downloaded, total) -> listener.accept(
                        new SyncEvent.Downloading(entry.fileName(), downloaded, total, currentIndex, totalFiles)));
            } catch (IOException e) {
                listener.accept(new SyncEvent.Failed("Failed to download " + entry.fileName() + ": " + e.getMessage(), e));
                throw e;
            }
            if (outcome instanceof Downloader.Outcome.Deferred deferred) {
                pendingOperations.renames().add(
                        new PendingOperations.Rename(deferred.deferredPath().toString(), deferred.finalPath().toString()));
            }
            state.managedFiles().put(entry.fileName(), entry.sha256());
        }

        if (!pendingOperations.isEmpty()) {
            JsonCodec.writeFile(pendingOperationsPath, pendingOperations);
        }

        return finish(state, true);
    }

    private SyncManifest fetchManifest() throws IOException {
        try {
            return manifestClient.fetch(baseUrl);
        } catch (IOException e) {
            listener.accept(new SyncEvent.Failed("Could not reach " + baseUrl + ": " + e.getMessage(), e));
            throw e;
        }
    }

    private Map<String, ScannedFile> scanLocalFiles() throws IOException {
        try {
            return ModsFolderScanner.scan(modsDir);
        } catch (IOException e) {
            listener.accept(new SyncEvent.Failed("Could not scan " + modsDir + ": " + e.getMessage(), e));
            throw e;
        }
    }

    private SyncEvent.Complete finish(ManagedState state, boolean changed) throws IOException {
        listener.accept(new SyncEvent.Finalizing());
        state.setLastServerAddress(baseUrl);
        try {
            saveManagedState(state);
        } catch (IOException e) {
            listener.accept(new SyncEvent.Failed("Failed to save sync state: " + e.getMessage(), e));
            throw e;
        }
        SyncEvent.Complete complete = new SyncEvent.Complete(changed);
        listener.accept(complete);
        return complete;
    }

    private ManagedState loadManagedState() {
        if (!Files.isRegularFile(managedStatePath)) {
            return new ManagedState();
        }
        try {
            return JsonCodec.readFile(managedStatePath, ManagedState.class);
        } catch (IOException e) {
            // A corrupt/unreadable state file must never block syncing - fall back to "nothing
            // managed yet", which is always safe (just means nothing is a delete candidate).
            return new ManagedState();
        }
    }

    private void saveManagedState(ManagedState state) throws IOException {
        JsonCodec.writeFile(managedStatePath, state);
    }
}
