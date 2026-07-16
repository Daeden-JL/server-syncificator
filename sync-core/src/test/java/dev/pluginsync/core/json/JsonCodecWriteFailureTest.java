package dev.pluginsync.core.json;

import dev.pluginsync.core.model.ManagedState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonCodecWriteFailureTest {

    private static List<Path> tempFilesIn(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".tmp")).toList();
        }
    }

    /** Makes the final move fail: a non-empty directory can't be replaced by a file. */
    private static Path unwritableTarget(Path dir) throws IOException {
        Path target = dir.resolve("state.json");
        Files.createDirectories(target);
        Files.writeString(target.resolve("occupant.txt"), "blocks the move");
        return target;
    }

    @Test
    void aFailedWriteLeavesNoTempFileBehind(@TempDir Path tempDir) throws IOException {
        Path target = unwritableTarget(tempDir);

        assertThrows(IOException.class, () -> JsonCodec.writeFile(target, new ManagedState()));

        assertEquals(List.of(), tempFilesIn(tempDir), "the scratch file must not be abandoned next to the config");
    }

    @Test
    void repeatedFailedWritesDoNotAccumulateTempFiles(@TempDir Path tempDir) throws IOException {
        Path target = unwritableTarget(tempDir);

        for (int i = 0; i < 5; i++) {
            assertThrows(IOException.class, () -> JsonCodec.writeFile(target, new ManagedState()));
        }

        assertEquals(List.of(), tempFilesIn(tempDir));
    }

    @Test
    void failureMessageIdentifiesTheFileAndTheUnderlyingCause(@TempDir Path tempDir) throws IOException {
        Path target = unwritableTarget(tempDir);

        IOException thrown = assertThrows(IOException.class, () -> JsonCodec.writeFile(target, new ManagedState()));

        assertTrue(thrown.getMessage().contains("state.json"),
                "should name the file it failed to write: " + thrown.getMessage());
        // The bare FileSystemException message is just "source -> target"; the type is what tells
        // you whether it was a permissions problem, a lock, or something else.
        assertTrue(thrown.getMessage().contains("Exception"),
                "should carry the underlying exception type: " + thrown.getMessage());
    }

    @Test
    void successfulWriteAlsoLeavesNoTempFile(@TempDir Path tempDir) throws IOException {
        Path target = tempDir.resolve("state.json");

        JsonCodec.writeFile(target, new ManagedState());

        assertTrue(Files.isRegularFile(target));
        assertEquals(List.of(), tempFilesIn(tempDir));
    }
}
