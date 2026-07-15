package dev.pluginsync.core.sync;

import dev.pluginsync.core.model.ManagedState;
import dev.pluginsync.core.model.ModEntry;
import dev.pluginsync.core.model.SyncManifest;
import dev.pluginsync.core.scan.ModsFolderScanner.ScannedFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Computes what needs to change to bring the local mods folder in line with a server's manifest.
 *
 * <p>Deletion is deliberately conservative: a file is only ever proposed for deletion if it is
 * both (a) tracked in {@link ManagedState} from a previous sync, and (b) absent from the current
 * remote manifest. Any jar the user dropped in by hand - never tracked as managed - is never
 * touched, no matter what the server's manifest says.
 */
public final class DiffEngine {

    private DiffEngine() {
    }

    public static SyncPlan diff(SyncManifest remote, Map<String, ScannedFile> localFiles, ManagedState managedState) {
        List<ModEntry> toDownload = new ArrayList<>();
        List<String> unchanged = new ArrayList<>();
        Set<String> remoteFileNames = new java.util.HashSet<>();

        for (ModEntry entry : remote.mods()) {
            remoteFileNames.add(entry.fileName());
            ScannedFile local = localFiles.get(entry.fileName());
            if (local != null && entry.matchesHash(local.sha256())) {
                unchanged.add(entry.fileName());
            } else {
                toDownload.add(entry);
            }
        }

        List<String> toDelete = new ArrayList<>();
        for (String managedFileName : managedState.managedFiles().keySet()) {
            if (!remoteFileNames.contains(managedFileName)) {
                toDelete.add(managedFileName);
            }
        }

        return new SyncPlan(toDownload, toDelete, unchanged);
    }
}
