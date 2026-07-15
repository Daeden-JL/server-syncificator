package dev.pluginsync.core.sync;

import dev.pluginsync.core.model.ModEntry;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;

/**
 * Downloads a single {@link ModEntry}, trying each of its URLs in priority order (external CDN
 * first, direct-from-server fallback last) until one succeeds and hashes to the expected sha256.
 */
public final class Downloader {

    /** Attempts per URL before moving on to the next candidate. */
    private static final int ATTEMPTS_PER_URL = 2;

    private final HttpClient httpClient;

    public interface ProgressListener {
        void onProgress(long bytesDownloaded, long totalBytes);
    }

    public Downloader() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build());
    }

    public Downloader(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /** Downloads {@code entry} into {@code destDir}, replacing any existing file of the same name. */
    public void download(ModEntry entry, Path destDir, ProgressListener progressListener) throws IOException {
        Files.createDirectories(destDir);
        Path finalPath = destDir.resolve(entry.fileName());
        Path tempPath = destDir.resolve(entry.fileName() + ".psync-tmp");

        IOException lastFailure = null;
        for (String url : entry.downloadUrls()) {
            for (int attempt = 1; attempt <= ATTEMPTS_PER_URL; attempt++) {
                try {
                    String actualHash = downloadOnce(url, tempPath, entry.size(), progressListener);
                    if (!entry.matchesHash(actualHash)) {
                        throw new IOException("Hash mismatch downloading " + entry.fileName() + " from " + url
                                + " (expected " + entry.sha256() + ", got " + actualHash + ")");
                    }
                    Files.move(tempPath, finalPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    return;
                } catch (IOException e) {
                    lastFailure = e;
                    Files.deleteIfExists(tempPath);
                }
            }
        }

        throw new IOException("All download sources failed for " + entry.fileName(), lastFailure);
    }

    private String downloadOnce(String url, Path tempPath, long expectedSize, ProgressListener progressListener) throws IOException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .GET()
                .build();

        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while downloading " + url, e);
        }

        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " downloading " + url);
        }

        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }

        long downloaded = 0;
        try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(tempPath)) {
            byte[] buffer = new byte[16384];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
                out.write(buffer, 0, read);
                downloaded += read;
                if (progressListener != null) {
                    progressListener.onProgress(downloaded, expectedSize);
                }
            }
        }

        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }
}
