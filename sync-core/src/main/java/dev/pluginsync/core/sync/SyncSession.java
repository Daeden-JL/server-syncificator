package dev.pluginsync.core.sync;

import dev.pluginsync.core.http.ManifestHttpClient;
import dev.pluginsync.core.json.JsonCodec;
import dev.pluginsync.core.model.ManagedState;
import dev.pluginsync.core.model.ModEntry;
import dev.pluginsync.core.model.SyncManifest;
import dev.pluginsync.core.scan.ModsFolderScanner;
import dev.pluginsync.core.scan.ModsFolderScanner.ScannedFile;

import java.io.IOException;
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
 * with a {@link SyncEvent.Complete#restartRequired()} result (typically: relaunch the client).
 */
public final class SyncSession {

    private final String baseUrl;
    private final Path modsDir;
    private final Path managedStatePath;
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
        this.manifestClient = manifestClient;
        this.downloader = downloader;
        this.listener = listener;
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

        int totalFiles = plan.toDelete().size() + plan.toDownload().size();
        int index = 0;

        for (String fileName : plan.toDelete()) {
            index++;
            listener.accept(new SyncEvent.Deleting(fileName, index, totalFiles));
            Files.deleteIfExists(modsDir.resolve(fileName));
            state.managedFiles().remove(fileName);
        }

        for (ModEntry entry : plan.toDownload()) {
            index++;
            final int currentIndex = index;
            try {
                downloader.download(entry, modsDir, (downloaded, total) -> listener.accept(
                        new SyncEvent.Downloading(entry.fileName(), downloaded, total, currentIndex, totalFiles)));
            } catch (IOException e) {
                listener.accept(new SyncEvent.Failed("Failed to download " + entry.fileName() + ": " + e.getMessage(), e));
                throw e;
            }
            state.managedFiles().put(entry.fileName(), entry.sha256());
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
        saveManagedState(state);
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
