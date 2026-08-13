package dev.pluginsync.core.selfupdate;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.pluginsync.core.scan.ModsFolderScanner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test of the self-update pipeline: a real HTTP server standing in for GitHub's
 * releases API and asset downloads, and a real {@link SelfUpdateSession} downloading,
 * hash-verifying, and applying the update against real files on disk.
 */
class SelfUpdateSessionTest {

    private static final String JAR_PREFIX = "daedens-server-syncificator-neoforge-1.21.1-";

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String start(String newJarContent) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newSingleThreadExecutor());
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        String newJarName = JAR_PREFIX + "0.1.5.jar";
        String sha256 = ModsFolderScanner.sha256Hex(newJarContent.getBytes(StandardCharsets.UTF_8));

        server.createContext("/releases/latest", exchange -> respond(exchange, """
                {
                  "tag_name": "v0.1.5",
                  "assets": [
                    {"name": "%s", "browser_download_url": "%s/downloads/jar", "size": %d},
                    {"name": "checksums.txt", "browser_download_url": "%s/downloads/checksums", "size": 99}
                  ]
                }
                """.formatted(newJarName, baseUrl, newJarContent.length(), baseUrl)));
        server.createContext("/downloads/jar", exchange -> respond(exchange, newJarContent));
        server.createContext("/downloads/checksums", exchange -> respond(exchange, sha256 + "  " + newJarName + "\n"));

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

    @Test
    void downloadsTheNewJarAndRemovesTheOldOne(@TempDir Path modsDir) throws IOException {
        String oldJarName = JAR_PREFIX + "0.1.4.jar";
        Path oldJarPath = modsDir.resolve(oldJarName);
        Files.writeString(oldJarPath, "old-version-content", StandardCharsets.UTF_8);

        String baseUrl = start("new-version-content");
        SelfUpdateSession session = new SelfUpdateSession(new SelfUpdateChecker(baseUrl));

        SelfUpdateSession.Result result = session.run("0.1.4", JAR_PREFIX, modsDir);

        assertTrue(result instanceof SelfUpdateSession.Result.Updated);
        SelfUpdateSession.Result.Updated updated = (SelfUpdateSession.Result.Updated) result;
        assertEquals("0.1.5", updated.newVersion());
        assertFalse(updated.pendingDelete(), "old jar was not locked, so removal should have been immediate");

        assertFalse(Files.exists(oldJarPath), "old jar should be gone");
        Path newJarPath = modsDir.resolve(JAR_PREFIX + "0.1.5.jar");
        assertTrue(Files.exists(newJarPath), "new jar should be present");
        assertEquals("new-version-content", Files.readString(newJarPath));
    }

    @Test
    void reportsUpToDateWhenNoNewerReleaseExists(@TempDir Path modsDir) throws IOException {
        String currentJarName = JAR_PREFIX + "0.1.5.jar";
        Path currentJarPath = modsDir.resolve(currentJarName);
        Files.writeString(currentJarPath, "current-content", StandardCharsets.UTF_8);

        String baseUrl = start("new-version-content");
        SelfUpdateSession session = new SelfUpdateSession(new SelfUpdateChecker(baseUrl));

        // Already running the version the fake release advertises - nothing to do.
        SelfUpdateSession.Result result = session.run("0.1.5", JAR_PREFIX, modsDir);

        assertTrue(result instanceof SelfUpdateSession.Result.UpToDate);
        assertTrue(Files.exists(currentJarPath), "nothing should have been touched");
    }

    @Test
    void sweepsAwayEveryStaleJarNotJustTheExpectedOne(@TempDir Path modsDir) throws IOException {
        // Regression test: observed on a real server updating 0.1.5 -> 0.1.6, where 0.1.5's own jar
        // manifest didn't resolve a version at all, so it went looking for a specific filename that
        // was never going to exist and left its real jar behind. Cleanup must not depend on
        // correctly guessing the one "current" filename - it should catch every stray copy of this
        // mod under this loader's prefix, including ones a caller's reported "current version"
        // wouldn't even point at.
        Path staleA = modsDir.resolve(JAR_PREFIX + "0.1.3.jar");
        Path staleB = modsDir.resolve(JAR_PREFIX + "0.0NONE.jar");
        Files.writeString(staleA, "stale-a", StandardCharsets.UTF_8);
        Files.writeString(staleB, "stale-b", StandardCharsets.UTF_8);
        Files.writeString(modsDir.resolve("unrelated-mod-1.0.jar"), "leave me alone", StandardCharsets.UTF_8);

        String baseUrl = start("new-version-content");
        SelfUpdateSession session = new SelfUpdateSession(new SelfUpdateChecker(baseUrl));

        // The caller's "current version" doesn't match either stale file's embedded version - that
        // must not stop them from being cleaned up.
        SelfUpdateSession.Result result = session.run("0.0NONE", JAR_PREFIX, modsDir);

        assertTrue(result instanceof SelfUpdateSession.Result.Updated);
        assertFalse(Files.exists(staleA), "every stale copy of this mod should be swept, not just one");
        assertFalse(Files.exists(staleB), "every stale copy of this mod should be swept, not just one");
        assertTrue(Files.exists(modsDir.resolve("unrelated-mod-1.0.jar")), "unrelated jars must never be touched");
        assertTrue(Files.exists(modsDir.resolve(JAR_PREFIX + "0.1.5.jar")), "new jar should be present");
    }
}
