package dev.pluginsync.core.http;

import dev.pluginsync.core.json.JsonCodec;
import dev.pluginsync.core.model.SyncManifest;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Client-side counterpart to {@code ManifestHttpServer}: fetches the manifest a server advertises. */
public final class ManifestHttpClient {

    private final HttpClient httpClient;

    public ManifestHttpClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    public ManifestHttpClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public static final class IncompatibleProtocolException extends IOException {
        public final int serverProtocolVersion;

        public IncompatibleProtocolException(int serverProtocolVersion) {
            super("Server manifest protocol version " + serverProtocolVersion + " is not supported by this client "
                    + "(expected " + SyncManifest.CURRENT_PROTOCOL_VERSION + ")");
            this.serverProtocolVersion = serverProtocolVersion;
        }
    }

    /**
     * Fetches the manifest from {@code baseUrl + "/daedens-server-syncificator/v1/manifest"}.
     *
     * @throws IncompatibleProtocolException if the server speaks a manifest protocol version this
     *     client doesn't understand - callers should surface this as "server plugin is newer/older,
     *     please update" rather than attempting to sync anyway.
     */
    public SyncManifest fetch(String baseUrl) throws IOException {
        String url = (baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl)
                + "/daedens-server-syncificator/v1/manifest";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
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
            throw new IOException("Server returned HTTP " + response.statusCode() + " for " + url);
        }

        SyncManifest manifest = JsonCodec.fromJson(response.body(), SyncManifest.class);
        if (!manifest.isCompatible()) {
            throw new IncompatibleProtocolException(manifest.protocolVersion());
        }
        return manifest;
    }
}
