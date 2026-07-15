package dev.pluginsync.core.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.pluginsync.core.json.JsonCodec;
import dev.pluginsync.core.model.SyncManifest;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Lightweight companion HTTP server, built entirely on the JDK's own {@code com.sun.net.httpserver}
 * (no extra runtime dependency, safe to embed in a Forge/NeoForge server mod). Serves the sync
 * manifest and raw mod file bytes as the guaranteed direct-download fallback.
 */
public final class ManifestHttpServer implements AutoCloseable {

    private final HttpServer httpServer;
    private final Path modsDir;

    public ManifestHttpServer(String bindAddress, int port, Path modsDir, Supplier<SyncManifest> manifestSupplier) throws IOException {
        this.modsDir = modsDir.toAbsolutePath().normalize();
        this.httpServer = HttpServer.create(new InetSocketAddress(bindAddress, port), 0);
        this.httpServer.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "plugin-sync-http");
            thread.setDaemon(true);
            return thread;
        }));
        this.httpServer.createContext("/plugin-sync/v1/manifest", exchange -> handleManifest(exchange, manifestSupplier));
        this.httpServer.createContext("/plugin-sync/v1/files/", this::handleFile);
    }

    public void start() {
        httpServer.start();
    }

    @Override
    public void close() {
        httpServer.stop(0);
    }

    public int port() {
        return httpServer.getAddress().getPort();
    }

    private void handleManifest(HttpExchange exchange, Supplier<SyncManifest> manifestSupplier) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed");
            return;
        }
        byte[] body = JsonCodec.toJson(manifestSupplier.get()).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private void handleFile(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed");
            return;
        }

        String path = exchange.getRequestURI().getPath();
        String prefix = "/plugin-sync/v1/files/";
        String rawName = path.substring(path.indexOf(prefix) + prefix.length());
        String fileName = URLDecoder.decode(rawName, StandardCharsets.UTF_8);

        // Reject anything that isn't a bare file name - no traversal, no subdirectories.
        if (fileName.isEmpty() || fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) {
            sendError(exchange, 400, "Bad Request");
            return;
        }

        Path target = modsDir.resolve(fileName).normalize();
        if (!target.startsWith(modsDir) || !Files.isRegularFile(target)) {
            sendError(exchange, 404, "Not Found");
            return;
        }

        exchange.getResponseHeaders().add("Content-Type", "application/java-archive");
        exchange.sendResponseHeaders(200, Files.size(target));
        try (OutputStream out = exchange.getResponseBody()) {
            Files.copy(target, out);
        }
    }

    private void sendError(HttpExchange exchange, int code, String message) throws IOException {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
