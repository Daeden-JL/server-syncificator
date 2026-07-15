package dev.pluginsync.core.config;

import dev.pluginsync.core.model.Side;

/**
 * Admin-authored entry describing one mod file that should be advertised to clients.
 *
 * <p>{@code fileName} must match a file actually present in the server's mods folder; the file
 * on disk is always the source of truth for the hash that ends up in the manifest, regardless of
 * what an external source reports (see ModrinthClient).
 */
public final class ServerModConfigEntry {
    private String fileName = "";
    private ModSource source = ModSource.DIRECT;
    private String modrinthVersionId;
    private Side side = Side.BOTH;

    public ServerModConfigEntry() {
    }

    public ServerModConfigEntry(String fileName, ModSource source, String modrinthVersionId, Side side) {
        this.fileName = fileName;
        this.source = source;
        this.modrinthVersionId = modrinthVersionId;
        this.side = side;
    }

    public String fileName() {
        return fileName;
    }

    public ModSource source() {
        return source;
    }

    public String modrinthVersionId() {
        return modrinthVersionId;
    }

    public Side side() {
        return side;
    }
}
