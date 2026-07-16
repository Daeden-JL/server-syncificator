package dev.pluginsync.core.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tracks which files in the mods folder are "owned" by server-syncificator, and what hash was installed
 * for each. This is the safety net that lets the diff engine tell the difference between "the
 * server stopped shipping this mod, delete it" and "the user manually added this jar, leave it
 * alone" — only files present in this state are ever candidates for deletion.
 */
public final class ManagedState {

    private String lastServerAddress = "";
    private Map<String, String> managedFiles = new LinkedHashMap<>();

    public ManagedState() {
    }

    public String lastServerAddress() {
        return lastServerAddress;
    }

    public void setLastServerAddress(String lastServerAddress) {
        this.lastServerAddress = lastServerAddress == null ? "" : lastServerAddress;
    }

    /** Mutable view; callers may add/remove/iterate directly. Keys are file names, values sha256. */
    public Map<String, String> managedFiles() {
        return managedFiles;
    }

    public boolean isManaged(String fileName) {
        return managedFiles.containsKey(fileName);
    }

    public ManagedState copy() {
        ManagedState copy = new ManagedState();
        copy.lastServerAddress = this.lastServerAddress;
        copy.managedFiles.putAll(this.managedFiles);
        return copy;
    }
}
