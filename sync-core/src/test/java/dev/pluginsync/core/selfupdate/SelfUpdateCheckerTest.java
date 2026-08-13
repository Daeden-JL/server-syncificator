package dev.pluginsync.core.selfupdate;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelfUpdateCheckerTest {

    private static final String JAR_PREFIX = "daedens-server-syncificator-neoforge-1.21.1-";

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    /**
     * Binds first so the release JSON can embed absolute URLs pointing back at this same server -
     * real GitHub asset URLs are always absolute, and {@link java.net.http.HttpRequest} rejects a
     * relative one outright.
     */
    private String start(Function<String, String> releaseJsonFactory, Map<String, String> responses) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newSingleThreadExecutor());
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

        server.createContext("/releases/latest", exchange -> respond(exchange, releaseJsonFactory.apply(baseUrl)));
        for (Map.Entry<String, String> entry : responses.entrySet()) {
            server.createContext(entry.getKey(), exchange -> respond(exchange, entry.getValue()));
        }
        server.start();
        return baseUrl;
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String releaseJson(String baseUrl, String tag, String jarName) {
        return """
                {
                  "tag_name": "%s",
                  "assets": [
                    {"name": "%s", "browser_download_url": "%s/downloads/jar", "size": 1234},
                    {"name": "checksums.txt", "browser_download_url": "%s/downloads/checksums", "size": 99}
                  ]
                }
                """.formatted(tag, jarName, baseUrl, baseUrl);
    }

    @Test
    void findsANewerRelease() throws IOException {
        String jarName = JAR_PREFIX + "0.1.5.jar";
        String baseUrl = start(
                url -> releaseJson(url, "v0.1.5", jarName),
                Map.of("/downloads/checksums", "deadbeef  " + jarName + "\n"));

        SelfUpdateChecker checker = new SelfUpdateChecker(baseUrl);
        Optional<SelfUpdateChecker.AvailableUpdate> update = checker.checkForUpdate("0.1.4", JAR_PREFIX);

        assertTrue(update.isPresent());
        assertEquals("0.1.5", update.get().version());
        assertEquals(jarName, update.get().jarFileName());
        assertEquals(baseUrl + "/downloads/jar", update.get().downloadUrl());
        assertEquals("deadbeef", update.get().sha256());
    }

    @Test
    void returnsEmptyWhenTheLatestReleaseIsNotNewer() throws IOException {
        String jarName = JAR_PREFIX + "0.1.4.jar";
        String baseUrl = start(url -> releaseJson(url, "v0.1.4", jarName), Map.of());

        SelfUpdateChecker checker = new SelfUpdateChecker(baseUrl);
        Optional<SelfUpdateChecker.AvailableUpdate> update = checker.checkForUpdate("0.1.4", JAR_PREFIX);

        assertTrue(update.isEmpty());
    }

    @Test
    void throwsWhenNoAssetMatchesThisLoader() throws IOException {
        String baseUrl = start(
                url -> releaseJson(url, "v0.1.5", "daedens-server-syncificator-forge-1.20.1-0.1.5.jar"),
                Map.of("/downloads/checksums", "deadbeef  something.jar\n"));

        SelfUpdateChecker checker = new SelfUpdateChecker(baseUrl);
        assertThrows(IOException.class, () -> checker.checkForUpdate("0.1.4", JAR_PREFIX));
    }

    @Test
    void throwsWhenChecksumsHasNoEntryForTheJar() throws IOException {
        String jarName = JAR_PREFIX + "0.1.5.jar";
        String baseUrl = start(
                url -> releaseJson(url, "v0.1.5", jarName),
                Map.of("/downloads/checksums", "deadbeef  some-other-file.jar\n"));

        SelfUpdateChecker checker = new SelfUpdateChecker(baseUrl);
        assertThrows(IOException.class, () -> checker.checkForUpdate("0.1.4", JAR_PREFIX));
    }
}
