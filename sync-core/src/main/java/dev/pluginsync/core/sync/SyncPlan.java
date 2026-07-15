package dev.pluginsync.core.sync;

import dev.pluginsync.core.model.ModEntry;

import java.util.List;

/** The result of diffing a remote manifest against the local mods folder + managed state. */
public record SyncPlan(List<ModEntry> toDownload, List<String> toDelete, List<String> unchanged) {

    public boolean isNoOp() {
        return toDownload.isEmpty() && toDelete.isEmpty();
    }
}
