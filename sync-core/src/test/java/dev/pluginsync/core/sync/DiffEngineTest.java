package dev.pluginsync.core.sync;

import dev.pluginsync.core.model.ManagedState;
import dev.pluginsync.core.model.ModEntry;
import dev.pluginsync.core.model.Side;
import dev.pluginsync.core.model.SyncManifest;
import dev.pluginsync.core.scan.ModsFolderScanner.ScannedFile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiffEngineTest {

    private static ModEntry entry(String fileName, String sha256) {
        return new ModEntry(fileName, sha256, 100, List.of("http://example.invalid/" + fileName), Side.BOTH);
    }

    @Test
    void newModIsDownloaded() {
        SyncManifest manifest = SyncManifest.of("srv", "", List.of(entry("a.jar", "aaaa")));
        SyncPlan plan = DiffEngine.diff(manifest, Map.of(), new ManagedState());

        assertEquals(List.of(entry("a.jar", "aaaa")), plan.toDownload());
        assertTrue(plan.toDelete().isEmpty());
        assertTrue(plan.unchanged().isEmpty());
    }

    @Test
    void matchingHashIsUnchanged() {
        SyncManifest manifest = SyncManifest.of("srv", "", List.of(entry("a.jar", "AAAA")));
        Map<String, ScannedFile> local = Map.of("a.jar", new ScannedFile("a.jar", "aaaa", 100));
        ManagedState state = new ManagedState();
        state.managedFiles().put("a.jar", "aaaa");

        SyncPlan plan = DiffEngine.diff(manifest, local, state);

        assertTrue(plan.toDownload().isEmpty());
        assertTrue(plan.toDelete().isEmpty());
        assertEquals(List.of("a.jar"), plan.unchanged());
        assertTrue(plan.isNoOp());
    }

    @Test
    void staleHashTriggersRedownload() {
        SyncManifest manifest = SyncManifest.of("srv", "", List.of(entry("a.jar", "newhash")));
        Map<String, ScannedFile> local = Map.of("a.jar", new ScannedFile("a.jar", "oldhash", 100));
        ManagedState state = new ManagedState();
        state.managedFiles().put("a.jar", "oldhash");

        SyncPlan plan = DiffEngine.diff(manifest, local, state);

        assertEquals(1, plan.toDownload().size());
        assertEquals("a.jar", plan.toDownload().get(0).fileName());
    }

    @Test
    void managedModRemovedFromManifestIsDeleted() {
        SyncManifest manifest = SyncManifest.of("srv", "", List.of());
        ManagedState state = new ManagedState();
        state.managedFiles().put("removed.jar", "aaaa");

        SyncPlan plan = DiffEngine.diff(manifest, Map.of("removed.jar", new ScannedFile("removed.jar", "aaaa", 1)), state);

        assertEquals(List.of("removed.jar"), plan.toDelete());
    }

    @Test
    void unmanagedExtraModIsNeverDeleted() {
        // "extra.jar" exists locally but was never installed by plugin-sync (not in ManagedState),
        // and the server manifest doesn't mention it either. It must be left completely alone.
        SyncManifest manifest = SyncManifest.of("srv", "", List.of());
        ManagedState state = new ManagedState(); // empty - nothing managed

        SyncPlan plan = DiffEngine.diff(manifest, Map.of("extra.jar", new ScannedFile("extra.jar", "ffff", 1)), state);

        assertTrue(plan.toDelete().isEmpty());
        assertTrue(plan.toDownload().isEmpty());
    }
}
