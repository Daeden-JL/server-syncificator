package dev.pluginsync.core.server;

import dev.pluginsync.core.model.SyncManifest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ManifestHttpServerTest {

    private ManifestHttpServer server;
    private final HttpClient client = HttpClient.newHttpClient();

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    private String startServing(Path modsDir) throws IOException {
        server = new ManifestHttpServer("127.0.0.1", 0, modsDir, () -> SyncManifest.of("s", "", List.of()));
        server.start();
        return "http://127.0.0.1:" + server.port();
    }

    /** Sends a raw, already-encoded path so the test controls exactly what goes on the wire. */
    private HttpResponse<byte[]> get(String baseUrl, String encodedPath) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + encodedPath)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    void servesAFileWhoseNameContainsAPlus(@TempDir Path modsDir) throws Exception {
        Files.writeString(modsDir.resolve("fabric-api-0.116.7+2.2.4.jar"), "body", StandardCharsets.UTF_8);
        String baseUrl = startServing(modsDir);

        HttpResponse<byte[]> response = get(baseUrl,
                "/daedens-server-syncificator/v1/files/fabric-api-0.116.7%2B2.2.4.jar");

        assertEquals(200, response.statusCode());
        assertEquals("body", new String(response.body(), StandardCharsets.UTF_8));
    }

    @Test
    void servesAFileWhoseNameContainsASpace(@TempDir Path modsDir) throws Exception {
        Files.writeString(modsDir.resolve("my mod.jar"), "spaced", StandardCharsets.UTF_8);
        String baseUrl = startServing(modsDir);

        HttpResponse<byte[]> response = get(baseUrl, "/daedens-server-syncificator/v1/files/my%20mod.jar");

        assertEquals(200, response.statusCode());
        assertEquals("spaced", new String(response.body(), StandardCharsets.UTF_8));
    }

    @Test
    void refusesPathTraversal(@TempDir Path tempDir) throws Exception {
        Path modsDir = Files.createDirectories(tempDir.resolve("mods"));
        Files.writeString(tempDir.resolve("secret.jar"), "should never be served", StandardCharsets.UTF_8);
        String baseUrl = startServing(modsDir);

        HttpResponse<byte[]> response = get(baseUrl, "/daedens-server-syncificator/v1/files/..%2Fsecret.jar");

        assertNotEquals(200, response.statusCode(), "must not serve a file outside the mods folder");
        assertNotEquals("should never be served", new String(response.body(), StandardCharsets.UTF_8));
    }

    @Test
    void refusesADoubleEncodedTraversal(@TempDir Path tempDir) throws Exception {
        Path modsDir = Files.createDirectories(tempDir.resolve("mods"));
        Files.writeString(tempDir.resolve("secret.jar"), "should never be served", StandardCharsets.UTF_8);
        String baseUrl = startServing(modsDir);

        // %252F would become '/' only if something decoded twice - the exact bug just removed.
        HttpResponse<byte[]> response = get(baseUrl, "/daedens-server-syncificator/v1/files/..%252Fsecret.jar");

        assertNotEquals(200, response.statusCode());
    }

    @Test
    void refusesNonJarFiles(@TempDir Path modsDir) throws Exception {
        Files.writeString(modsDir.resolve("server.properties"), "secrets", StandardCharsets.UTF_8);
        String baseUrl = startServing(modsDir);

        HttpResponse<byte[]> response = get(baseUrl, "/daedens-server-syncificator/v1/files/server.properties");

        assertEquals(400, response.statusCode(), "only *.jar is ever exposed");
    }

    @Test
    void returns404ForAJarThatIsNotThere(@TempDir Path modsDir) throws Exception {
        String baseUrl = startServing(modsDir);

        HttpResponse<byte[]> response = get(baseUrl, "/daedens-server-syncificator/v1/files/absent.jar");

        assertEquals(404, response.statusCode());
    }
}
