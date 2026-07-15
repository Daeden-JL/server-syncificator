package dev.pluginsync.core.model;

import java.util.List;
import java.util.Objects;

/** The full manifest a server advertises describing what a client's mods folder should contain. */
public final class SyncManifest {

    /** Bumped whenever the wire format changes incompatibly. */
    public static final int CURRENT_PROTOCOL_VERSION = 1;

    private final int protocolVersion;
    private final String serverName;
    private final String motd;
    private final List<ModEntry> mods;

    public SyncManifest(int protocolVersion, String serverName, String motd, List<ModEntry> mods) {
        this.protocolVersion = protocolVersion;
        this.serverName = Objects.requireNonNull(serverName, "serverName");
        this.motd = motd == null ? "" : motd;
        this.mods = List.copyOf(Objects.requireNonNull(mods, "mods"));
    }

    public static SyncManifest of(String serverName, String motd, List<ModEntry> mods) {
        return new SyncManifest(CURRENT_PROTOCOL_VERSION, serverName, motd, mods);
    }

    public int protocolVersion() {
        return protocolVersion;
    }

    public String serverName() {
        return serverName;
    }

    public String motd() {
        return motd;
    }

    public List<ModEntry> mods() {
        return mods;
    }

    public boolean isCompatible() {
        return protocolVersion == CURRENT_PROTOCOL_VERSION;
    }
}
