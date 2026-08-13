package dev.pluginsync.core.selfupdate;

import dev.pluginsync.core.model.ModEntry;
import dev.pluginsync.core.model.Side;
import dev.pluginsync.core.relaunch.PendingOperations;
import dev.pluginsync.core.relaunch.RelaunchHelper;
import dev.pluginsync.core.sync.Downloader;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Downloads and applies a self-update found by {@link SelfUpdateChecker}, for a dedicated server
 * to update its own mod jar instead of an admin having to notice a release and swap the file in by
 * hand.
 *
 * <p>The new jar always lands under a brand-new file name (the version is baked into it, same as
 * every release so far), so downloading it never collides with anything already on disk. Cleanup
 * afterward sweeps the mods folder for <em>every</em> other jar matching this loader's naming
 * prefix, rather than trying to delete one specific filename reconstructed from "the currently
 * running version" - a version that, as observed on a real server updating 0.1.5 -&gt; 0.1.6, isn't
 * guaranteed to be trustworthy (0.1.5's own jar manifest didn't resolve a version at all, so it
 * went looking for a file that was never going to exist and left itself behind). Sweeping by
 * prefix instead means any stray copy - however it got there - eventually gets cleaned up.
 *
 * <p>NeoForge/Forge keep every mod jar this JVM has loaded open for the life of the process, so
 * deleting the file this code itself is presently running from can fail with
 * {@link AccessDeniedException} on Windows. When that happens, the removal is hitched to
 * {@link RelaunchHelper#applyOperationsOnExit} - deliberately not a relaunch of any kind, since the
 * next time this server process starts again is an admin's decision, not something to act on here.
 */
public final class SelfUpdateSession {

    public sealed interface Result {
        record UpToDate() implements Result {
        }

        /** @param pendingDelete true if at least one stale jar is still on disk, queued for removal once this JVM exits */
        record Updated(String newVersion, boolean pendingDelete) implements Result {
        }
    }

    private final SelfUpdateChecker checker;
    private final Downloader downloader;

    public SelfUpdateSession(SelfUpdateChecker checker) {
        this(checker, new Downloader());
    }

    public SelfUpdateSession(SelfUpdateChecker checker, Downloader downloader) {
        this.checker = checker;
        this.downloader = downloader;
    }

    /** @param modsDir the mods folder this instance is running from */
    public Result run(String currentVersion, String jarNamePrefix, Path modsDir) throws IOException {
        Optional<SelfUpdateChecker.AvailableUpdate> update = checker.checkForUpdate(currentVersion, jarNamePrefix);
        if (update.isEmpty()) {
            return new Result.UpToDate();
        }
        SelfUpdateChecker.AvailableUpdate available = update.get();

        ModEntry entry = new ModEntry(available.jarFileName(), available.sha256(), available.size(),
                List.of(available.downloadUrl()), Side.BOTH);
        Downloader.Outcome outcome = downloader.download(entry, modsDir, null);
        if (!(outcome instanceof Downloader.Outcome.Applied)) {
            // A brand-new file name never collides with anything already on disk, so the "target is
            // locked" deferral this same Outcome exists for should be unreachable here in practice.
            throw new IOException("Could not apply downloaded update " + available.jarFileName());
        }

        boolean pendingDelete = false;
        PendingOperations pending = new PendingOperations();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(modsDir, jarNamePrefix + "*.jar")) {
            for (Path candidate : stream) {
                if (candidate.getFileName().toString().equals(available.jarFileName())) {
                    continue;
                }
                try {
                    Files.deleteIfExists(candidate);
                } catch (AccessDeniedException e) {
                    pending.deletes().add(candidate.toString());
                    pendingDelete = true;
                }
            }
        }
        if (pendingDelete) {
            RelaunchHelper.applyOperationsOnExit(pending);
        }

        return new Result.Updated(available.version(), pendingDelete);
    }
}
