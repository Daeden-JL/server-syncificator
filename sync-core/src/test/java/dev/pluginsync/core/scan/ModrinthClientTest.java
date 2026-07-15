package dev.pluginsync.core.scan;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Exercises {@link ModrinthClient} against a local fake server, since api.modrinth.com is unreachable in CI/sandboxes. */
class ModrinthClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String startFakeModrinth(String responseBody, int statusCode) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v2/version/", exchange -> {
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statusCode, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v2";
    }

    @Test
    void resolvesPrimaryFile() throws IOException {
        String json = """
                {
                  "id": "abc123",
                  "files": [
                    {"primary": false, "filename": "extra.jar", "url": "http://cdn/extra.jar", "size": 5, "hashes": {"sha256": "1111"}},
                    {"primary": true, "filename": "mod.jar", "url": "http://cdn/mod.jar", "size": 42, "hashes": {"sha256": "abcdef"}}
                  ]
                }
                """;
        String baseUrl = startFakeModrinth(json, 200);

        ModrinthClient.ResolvedFile resolved = new ModrinthClient(baseUrl).resolveVersion("abc123");

        assertEquals("mod.jar", resolved.fileName());
        assertEquals("http://cdn/mod.jar", resolved.downloadUrl());
        assertEquals("abcdef", resolved.sha256());
        assertEquals(42, resolved.size());
    }

    @Test
    void fallsBackToFirstFileWhenNonePrimary() throws IOException {
        String json = """
                {
                  "id": "abc123",
                  "files": [
                    {"filename": "only.jar", "url": "http://cdn/only.jar", "size": 7, "hashes": {"sha256": "beef"}}
                  ]
                }
                """;
        String baseUrl = startFakeModrinth(json, 200);

        ModrinthClient.ResolvedFile resolved = new ModrinthClient(baseUrl).resolveVersion("abc123");

        assertEquals("only.jar", resolved.fileName());
    }

    @Test
    void throwsOnNon200() throws IOException {
        String baseUrl = startFakeModrinth("not found", 404);
        ModrinthClient client = new ModrinthClient(baseUrl);

        assertThrows(IOException.class, () -> client.resolveVersion("missing"));
    }
}
