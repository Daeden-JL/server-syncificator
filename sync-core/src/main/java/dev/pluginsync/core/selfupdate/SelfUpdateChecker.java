package dev.pluginsync.core.selfupdate;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import dev.pluginsync.core.json.JsonCodec;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * Checks a GitHub repository's latest release against a running version, for the mod to update
 * itself rather than relying on an admin to notice a new release exists.
 *
 * <p>A release is expected to publish, per loader, a jar asset and a {@code checksums.txt} listing
 * each jar's sha256 (one {@code <hash>  <filename>} line each, i.e. {@code sha256sum} output) -
 * GitHub's release API doesn't hand back file hashes itself, and this project only ever downloads
 * a file after verifying it against a hash it already trusted going in (see {@code Downloader}).
 */
public class SelfUpdateChecker {

    private final String apiBaseUrl;
    private final HttpClient httpClient;

    public record AvailableUpdate(String version, String jarFileName, String downloadUrl, String sha256, long size) {
    }

    public SelfUpdateChecker(String owner, String repo) {
        this("https://api.github.com/repos/" + owner + "/" + repo);
    }

    /** @param apiBaseUrl e.g. {@code https://api.github.com/repos/<owner>/<repo>}; overridable for tests. */
    public SelfUpdateChecker(String apiBaseUrl) {
        this(apiBaseUrl, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build());
    }

    public SelfUpdateChecker(String apiBaseUrl, HttpClient httpClient) {
        this.apiBaseUrl = apiBaseUrl.endsWith("/") ? apiBaseUrl.substring(0, apiBaseUrl.length() - 1) : apiBaseUrl;
        this.httpClient = httpClient;
    }

    /**
     * @param currentVersion this running instance's version, e.g. "0.1.4"
     * @param jarNamePrefix picks this loader's asset out of a release that publishes more than one,
     *     e.g. "daedens-server-syncificator-neoforge-1.21.1-"
     * @return the newer release, or empty if the latest release is not newer than {@code currentVersion}
     */
    public Optional<AvailableUpdate> checkForUpdate(String currentVersion, String jarNamePrefix) throws IOException {
        JsonObject release = getJson(apiBaseUrl + "/releases/latest");
        String tagName = requireString(release, "tag_name", "latest release");
        String latestVersion = tagName.startsWith("v") ? tagName.substring(1) : tagName;

        if (Versions.compare(latestVersion, currentVersion) <= 0) {
            return Optional.empty();
        }

        JsonArray assets = release.has("assets") && release.get("assets").isJsonArray()
                ? release.getAsJsonArray("assets")
                : new JsonArray();

        JsonObject jarAsset = findAsset(assets, jarNamePrefix, ".jar");
        if (jarAsset == null) {
            throw new IOException("Release " + tagName + " has no asset matching '" + jarNamePrefix + "*.jar'");
        }
        String jarFileName = requireString(jarAsset, "name", tagName);
        String downloadUrl = requireString(jarAsset, "browser_download_url", tagName);
        long size = jarAsset.has("size") && jarAsset.get("size").isJsonPrimitive() ? jarAsset.get("size").getAsLong() : -1L;

        JsonObject checksumsAsset = findAsset(assets, "checksums", ".txt");
        if (checksumsAsset == null) {
            throw new IOException("Release " + tagName + " has no checksums.txt asset to verify " + jarFileName + " against");
        }
        String sha256 = fetchSha256(requireString(checksumsAsset, "browser_download_url", tagName), jarFileName);

        return Optional.of(new AvailableUpdate(latestVersion, jarFileName, downloadUrl, sha256, size));
    }

    private String fetchSha256(String checksumsUrl, String jarFileName) throws IOException {
        String body = getText(checksumsUrl);
        for (String line : body.split("\n")) {
            String[] parts = line.trim().split("\\s+", 2);
            if (parts.length == 2 && parts[1].equals(jarFileName)) {
                return parts[0];
            }
        }
        throw new IOException("checksums.txt has no entry for " + jarFileName);
    }

    private static JsonObject findAsset(JsonArray assets, String namePrefix, String nameSuffix) {
        for (JsonElement element : assets) {
            JsonObject asset = element.getAsJsonObject();
            if (!asset.has("name") || !asset.get("name").isJsonPrimitive()) {
                continue;
            }
            String name = asset.get("name").getAsString();
            if (name.startsWith(namePrefix) && name.endsWith(nameSuffix)) {
                return asset;
            }
        }
        return null;
    }

    private JsonObject getJson(String url) throws IOException {
        String body = getText(url);
        try {
            JsonObject json = JsonCodec.gson().fromJson(body, JsonObject.class);
            if (json == null) {
                throw new IOException("Empty response from " + url);
            }
            return json;
        } catch (JsonParseException | IllegalStateException e) {
            throw new IOException("Unparseable JSON from " + url, e);
        }
    }

    private String getText(String url) throws IOException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "server-syncificator-self-update (dev.pluginsync)")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while contacting " + url, e);
        }
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " from " + url);
        }
        return response.body();
    }

    private static String requireString(JsonObject object, String field, String context) throws IOException {
        if (!object.has(field) || !object.get(field).isJsonPrimitive()) {
            throw new IOException(context + " is missing required field '" + field + "'");
        }
        return object.get(field).getAsString();
    }
}
