package dev.pluginsync.core.json;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Shared Gson instance + small helpers for reading/writing config and manifest JSON to disk. */
public final class JsonCodec {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private JsonCodec() {
    }

    public static Gson gson() {
        return GSON;
    }

    public static <T> T fromJson(String json, Class<T> type) {
        return GSON.fromJson(json, type);
    }

    public static String toJson(Object value) {
        return GSON.toJson(value);
    }

    public static <T> T readFile(Path path, Class<T> type) throws IOException {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            T value = GSON.fromJson(reader, type);
            if (value == null) {
                throw new IOException("File was empty or 'null': " + path);
            }
            return value;
        }
    }

    /** Writes atomically (temp file + move) so a crash mid-write can't corrupt the config/state file. */
    public static void writeFile(Path path, Object value) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = Files.createTempFile(parent, path.getFileName().toString(), ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(value, writer);
            }
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // A failed write used to abandon its scratch file next to the real one for good, so
            // every retry left another ".json<random>.tmp" behind in the user's config folder.
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException cleanupFailure) {
                e.addSuppressed(cleanupFailure);
            }
            // FileSystemException renders as a bare "source -> target" when it has no reason
            // attached, which hides *which* failure it was. toString() keeps the type, so an
            // AccessDeniedException stops being indistinguishable from any other move failure.
            throw new IOException("Failed to write " + path + ": " + e, e);
        }
    }
}
