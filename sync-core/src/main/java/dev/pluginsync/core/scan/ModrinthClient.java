package dev.pluginsync.core.scan;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.pluginsync.core.json.JsonCodec;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * Minimal client for the public, keyless Modrinth API - just enough to resolve a version ID to
 * its primary file's direct CDN URL, file name, size and sha256 hash. Used server-side only: the
 * server resolves external download URLs ahead of time and bakes them into the manifest it hands
 * clients, so the client never needs to talk to Modrinth (or know it exists) at all.
 */
public class ModrinthClient {

    /** Overridable for tests; production default is the real Modrinth API. */
    private final String baseUrl;
    private final HttpClient httpClient;

    public ModrinthClient() {
        this("https://api.modrinth.com/v2");
    }

    public ModrinthClient(String baseUrl) {
        this(baseUrl, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    public ModrinthClient(String baseUrl, HttpClient httpClient) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.httpClient = httpClient;
    }

    public record ResolvedFile(String fileName, String downloadUrl, String sha256, long size) {
    }

    /**
     * Fetches version metadata for {@code versionId} and returns its primary file, or the first
     * file if none are marked primary. Throws IOException on any network/parsing failure or if
     * the version has no files - callers should treat that as "fall back to direct-from-server".
     */
    public ResolvedFile resolveVersion(String versionId) throws IOException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/version/" + versionId))
                .header("User-Agent", "plugin-sync/0.1.0 (dev.pluginsync)")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while contacting Modrinth", e);
        }

        if (response.statusCode() != 200) {
            throw new IOException("Modrinth API returned HTTP " + response.statusCode() + " for version " + versionId);
        }

        JsonObject body = JsonCodec.gson().fromJson(response.body(), JsonObject.class);
        JsonArray files = body.getAsJsonArray("files");
        if (files == null || files.isEmpty()) {
            throw new IOException("Modrinth version " + versionId + " has no files");
        }

        JsonObject chosen = pickPrimary(files).orElseGet(() -> files.get(0).getAsJsonObject());
        String fileName = chosen.get("filename").getAsString();
        String url = chosen.get("url").getAsString();
        long size = chosen.has("size") ? chosen.get("size").getAsLong() : -1L;
        JsonObject hashes = chosen.getAsJsonObject("hashes");
        String sha256 = hashes != null && hashes.has("sha256") ? hashes.get("sha256").getAsString() : null;
        if (sha256 == null) {
            throw new IOException("Modrinth version " + versionId + " file has no sha256 hash");
        }

        return new ResolvedFile(fileName, url, sha256, size);
    }

    private static Optional<JsonObject> pickPrimary(JsonArray files) {
        for (JsonElement element : files) {
            JsonObject obj = element.getAsJsonObject();
            if (obj.has("primary") && obj.get("primary").getAsBoolean()) {
                return Optional.of(obj);
            }
        }
        return Optional.empty();
    }
}
