package dev.pluginsync.core.model;

import java.util.List;
import java.util.Objects;

/**
 * A single mod file as advertised by the server's manifest.
 *
 * <p>{@code downloadUrls} is ordered by preference: a client tries each URL in turn until one
 * succeeds and the downloaded bytes hash to {@code sha256}. This lets a server offer an external
 * CDN (Modrinth today, CurseForge in the future) first for bandwidth offload, while always
 * keeping a direct-from-server URL as a guaranteed fallback.
 */
public final class ModEntry {
    private final String fileName;
    private final String sha256;
    private final long size;
    private final List<String> downloadUrls;
    private final Side side;

    public ModEntry(String fileName, String sha256, long size, List<String> downloadUrls, Side side) {
        this.fileName = Objects.requireNonNull(fileName, "fileName");
        this.sha256 = Objects.requireNonNull(sha256, "sha256").toLowerCase(java.util.Locale.ROOT);
        this.size = size;
        this.downloadUrls = List.copyOf(Objects.requireNonNull(downloadUrls, "downloadUrls"));
        this.side = Objects.requireNonNull(side, "side");
        if (this.downloadUrls.isEmpty()) {
            throw new IllegalArgumentException("downloadUrls must not be empty for " + fileName);
        }
    }

    public String fileName() {
        return fileName;
    }

    public String sha256() {
        return sha256;
    }

    public long size() {
        return size;
    }

    public List<String> downloadUrls() {
        return downloadUrls;
    }

    public Side side() {
        return side;
    }

    /** True if this entry's hash matches the given (case-insensitive) sha256 hex digest. */
    public boolean matchesHash(String otherSha256) {
        return sha256.equalsIgnoreCase(otherSha256);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ModEntry other)) return false;
        return size == other.size
                && fileName.equals(other.fileName)
                && sha256.equals(other.sha256)
                && downloadUrls.equals(other.downloadUrls)
                && side == other.side;
    }

    @Override
    public int hashCode() {
        return Objects.hash(fileName, sha256, size, downloadUrls, side);
    }

    @Override
    public String toString() {
        return "ModEntry{fileName='%s', sha256='%s', size=%d, side=%s}"
                .formatted(fileName, sha256, size, side);
    }
}
