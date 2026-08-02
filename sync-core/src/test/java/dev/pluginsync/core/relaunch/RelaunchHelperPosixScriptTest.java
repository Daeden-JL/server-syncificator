package dev.pluginsync.core.relaunch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Actually executes the generated POSIX trampoline script (this sandbox has /bin/sh, so unlike
 * the Windows .bat path this one can be genuinely exercised end to end, not just reviewed): starts
 * a stand-in "still running JVM" process, confirms the script waits for it to exit before touching
 * any files, then confirms it applies the pending rename/delete and finally runs the follow-up
 * "relaunch" command.
 */
@DisabledOnOs(OS.WINDOWS)
class RelaunchHelperPosixScriptTest {

    @Test
    void waitsForPidThenAppliesOperationsThenRelaunches(@TempDir Path tempDir) throws IOException, InterruptedException {
        Path pendingFile = tempDir.resolve("mod.jar.psync-pending");
        Path finalFile = tempDir.resolve("mod.jar");
        Path deleteMeFile = tempDir.resolve("removed-mod.jar");
        Path markerFile = tempDir.resolve("relaunched.marker");

        Files.writeString(pendingFile, "new-content");
        Files.writeString(deleteMeFile, "stale-mod");

        Process stillRunning = new ProcessBuilder("sleep", "2").start();

        PendingOperations operations = new PendingOperations();
        operations.renames().add(new PendingOperations.Rename(pendingFile.toString(), finalFile.toString()));
        operations.deletes().add(deleteMeFile.toString());

        List<String> relaunchCommand = List.of("touch", markerFile.toString());

        Path scriptPath = RelaunchHelper.writePosixScript(stillRunning.pid(), operations, relaunchCommand);
        Process script = new ProcessBuilder("/bin/sh", scriptPath.toString()).start();

        // The rename must not happen while the stand-in process is still alive.
        Thread.sleep(500);
        assertTrue(stillRunning.isAlive(), "test is only meaningful while the stand-in process is still running");
        assertFalse(Files.exists(finalFile), "script applied the rename before the stand-in process exited");

        boolean scriptFinished = script.waitFor(15, TimeUnit.SECONDS);
        assertTrue(scriptFinished, "trampoline script did not finish in time");
        assertFalse(stillRunning.isAlive());

        // touch/relaunch runs detached (backgrounded with nohup) - give it a moment to land.
        waitUntil(() -> Files.exists(markerFile), 5000);

        assertTrue(Files.exists(finalFile), "rename was not applied");
        assertEquals("new-content", Files.readString(finalFile));
        assertFalse(Files.exists(pendingFile), "temp file should be gone after the move");
        assertFalse(Files.exists(deleteMeFile), "stale file was not deleted");
        assertTrue(Files.exists(markerFile), "relaunch command never ran");
    }

    @Test
    void appliesOperationsEvenWithoutARelaunchCommand(@TempDir Path tempDir) throws IOException, InterruptedException {
        // Regression test: on Windows, ProcessHandle.Info.arguments() is always empty, so
        // RelaunchHelper.relaunchWithPendingOperations() must still apply staged file operations
        // via the trampoline even when it can't reconstruct a relaunch command (null here stands
        // in for that case) - it must not skip applying the operations just because it can't also
        // restart Minecraft afterward.
        Path pendingFile = tempDir.resolve("mod.jar.psync-pending");
        Path finalFile = tempDir.resolve("mod.jar");

        Files.writeString(pendingFile, "new-content");

        Process stillRunning = new ProcessBuilder("sleep", "1").start();

        PendingOperations operations = new PendingOperations();
        operations.renames().add(new PendingOperations.Rename(pendingFile.toString(), finalFile.toString()));

        Path scriptPath = RelaunchHelper.writePosixScript(stillRunning.pid(), operations, null);
        Process script = new ProcessBuilder("/bin/sh", scriptPath.toString()).start();

        boolean scriptFinished = script.waitFor(15, TimeUnit.SECONDS);
        assertTrue(scriptFinished, "trampoline script did not finish in time");

        assertTrue(Files.exists(finalFile), "rename was not applied even though no relaunch command was available");
        assertEquals("new-content", Files.readString(finalFile));
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
    }
}
