package dev.pluginsync.core.relaunch;

import java.util.ArrayList;
import java.util.List;

/**
 * File operations that couldn't be applied immediately because the target was locked by the
 * currently-running JVM. On Windows, a mod jar this session's NeoForge/Forge instance already has
 * open (which is every jar in the mods folder, for the lifetime of the JVM, not just ones touched
 * by a race) can't be renamed or deleted while that JVM is alive - unlike POSIX filesystems, which
 * allow this transparently. Persisted to disk and applied by a small trampoline script launched by
 * {@code RelaunchHelper.relaunchWithPendingOperations}, which waits for this JVM to actually exit
 * before touching anything.
 */
public final class PendingOperations {

    public record Rename(String tempPath, String finalPath) {
    }

    private List<Rename> renames = new ArrayList<>();
    private List<String> deletes = new ArrayList<>();

    public List<Rename> renames() {
        return renames;
    }

    public List<String> deletes() {
        return deletes;
    }

    public boolean isEmpty() {
        return renames.isEmpty() && deletes.isEmpty();
    }
}
