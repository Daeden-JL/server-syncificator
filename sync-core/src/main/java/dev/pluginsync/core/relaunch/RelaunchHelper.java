package dev.pluginsync.core.relaunch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Best-effort helper for restarting the client JVM after a sync that changed the mods folder.
 *
 * <p>Forge and NeoForge both load every mod jar once, at JVM startup, onto classloaders that
 * can't be safely torn down and rebuilt mid-session - so applying a mod-jar change fundamentally
 * requires a fresh JVM. This reconstructs the original launch command from
 * {@link ProcessHandle#current()} (executable + arguments + working directory), appends a marker
 * system property so the relaunched process can tell it just came from a sync (and skip
 * auto-syncing again immediately), starts the new process attached to the same console, and
 * leaves it to the caller to then exit the current JVM.
 *
 * <p>This is inherently best-effort, and on Windows it never works: the JDK only populates
 * {@link ProcessHandle.Info#arguments()} on platforms that expose a process's command line, so
 * {@link #relaunch()} always throws there. That isn't a failure of the sync - the mods folder is
 * already updated by the time this is called - so callers must present it as "restart to apply",
 * not as an error.
 */
public final class RelaunchHelper {

    /** Set as a {@code -D} system property on the relaunched process; check with {@link #isPostRelaunch()}. */
    public static final String POST_RELAUNCH_PROPERTY = "pluginsync.postRelaunch";

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private RelaunchHelper() {
    }

    public static boolean isPostRelaunch() {
        return Boolean.getBoolean(POST_RELAUNCH_PROPERTY);
    }

    /**
     * Starts a new process that re-runs the current JVM's launch command, inheriting its
     * environment, working directory, and console. Does not stop the current JVM - the caller is
     * responsible for exiting once the new process has been confirmed to start.
     *
     * @throws IOException if the current process's command line isn't available (some restricted
     *     environments hide this - always true on Windows), or the new process fails to start.
     */
    public static Process relaunch() throws IOException {
        ProcessBuilder builder = new ProcessBuilder(buildRelaunchCommand());
        builder.inheritIO();
        builder.directory(Path.of(System.getProperty("user.dir")).toFile());
        return builder.start();
    }

    /**
     * Like {@link #relaunch()}, but for when some files couldn't be applied immediately because
     * they were locked by this JVM - on Windows, every mod jar the game already has loaded this
     * session, for as long as the JVM is alive (not just from a race), can't be renamed or deleted
     * until that JVM actually exits.
     *
     * <p>Instead of starting Minecraft directly, this generates and launches a small detached
     * script that waits for the current process to exit and then applies the pending file
     * operations - since the rename/delete genuinely cannot happen from inside this still-running
     * JVM. Applying those operations does not depend on being able to relaunch Minecraft
     * afterward: on Windows the launch command line is never exposed
     * ({@link ProcessHandle.Info#arguments()} is always empty there), so the script still gets
     * generated and launched to apply the pending operations, it just omits the final relaunch
     * step. Callers must check the returned {@link RelaunchOutcome} to know whether they can
     * expect Minecraft to come back up on its own or need to tell the player to close and reopen
     * the game themselves.
     */
    public static RelaunchOutcome relaunchWithPendingOperations(PendingOperations operations) throws IOException {
        List<String> relaunchCommand = tryBuildRelaunchCommand();
        long currentPid = ProcessHandle.current().pid();

        Path scriptPath = WINDOWS
                ? writeWindowsScript(currentPid, operations, relaunchCommand)
                : writePosixScript(currentPid, operations, relaunchCommand);

        List<String> scriptCommand = WINDOWS
                ? List.of("cmd.exe", "/c", scriptPath.toString())
                : List.of("/bin/sh", scriptPath.toString());

        ProcessBuilder builder = new ProcessBuilder(scriptCommand);
        builder.directory(Path.of(System.getProperty("user.dir")).toFile());
        Process process = builder.start();

        return relaunchCommand != null
                ? new RelaunchOutcome.Relaunched(process)
                : new RelaunchOutcome.ApplyScheduledOnly(process);
    }

    /** Same as {@link #buildRelaunchCommand()}, but reports unavailability via {@code null} instead of throwing. */
    private static List<String> tryBuildRelaunchCommand() {
        try {
            return buildRelaunchCommand();
        } catch (IOException e) {
            return null;
        }
    }

    private static List<String> buildRelaunchCommand() throws IOException {
        ProcessHandle.Info info = ProcessHandle.current().info();
        String command = info.command().orElseThrow(
                () -> new IOException("Current process command is not available - cannot self-relaunch"));
        // Windows never populates this: the JDK only implements arguments() where the OS exposes a
        // process's command line (/proc/<pid>/cmdline on Linux), so self-relaunch is unavailable
        // there entirely rather than intermittently. Callers must treat it as "ask the user to
        // restart", not as a sync failure.
        String[] arguments = info.arguments().orElseThrow(
                () -> new IOException("this platform does not expose the launch command line"));

        List<String> fullCommand = new ArrayList<>(arguments.length + 2);
        fullCommand.add(command);
        // Inserted immediately after the executable so it's parsed as a JVM option regardless of
        // where -jar/-cp/mainclass appears later in the original argument list.
        fullCommand.add("-D" + POST_RELAUNCH_PROPERTY + "=true");
        fullCommand.addAll(List.of(arguments));
        return fullCommand;
    }

    static Path writeWindowsScript(long pid, PendingOperations operations, List<String> relaunchCommand) throws IOException {
        StringBuilder script = new StringBuilder();
        script.append("@echo off\r\n");
        script.append(":wait_pid\r\n");
        script.append("tasklist /FI \"PID eq ").append(pid).append("\" 2>NUL | findstr /I \"").append(pid).append("\" >NUL\r\n");
        script.append("if not errorlevel 1 (\r\n");
        script.append("    timeout /t 1 /nobreak >NUL\r\n");
        script.append("    goto wait_pid\r\n");
        script.append(")\r\n");
        // move/del on an already-applied or already-absent path is a harmless no-op (stderr
        // suppressed), so blindly retrying the whole batch a few times is enough to ride out any
        // brief lag between this JVM's process exiting and Windows actually releasing its file
        // locks (e.g. antivirus scanning), without needing conditional per-file retry logic.
        script.append("for /L %%i in (1,1,5) do (\r\n");
        for (PendingOperations.Rename rename : operations.renames()) {
            script.append("    move /y \"").append(rename.tempPath()).append("\" \"").append(rename.finalPath()).append("\" >nul 2>&1\r\n");
        }
        for (String deletePath : operations.deletes()) {
            script.append("    del /f /q \"").append(deletePath).append("\" >nul 2>&1\r\n");
        }
        script.append("    timeout /t 1 /nobreak >nul\r\n");
        script.append(")\r\n");
        if (relaunchCommand != null) {
            script.append("start \"\" ").append(windowsQuoteAll(relaunchCommand)).append("\r\n");
        }

        Path scriptPath = Files.createTempFile("pluginsync-relaunch-", ".bat");
        Files.writeString(scriptPath, script.toString());
        return scriptPath;
    }

    private static String windowsQuoteAll(List<String> command) {
        StringBuilder joined = new StringBuilder();
        for (String part : command) {
            if (!joined.isEmpty()) {
                joined.append(' ');
            }
            joined.append('"').append(part.replace("\"", "\"\"")).append('"');
        }
        return joined.toString();
    }

    static Path writePosixScript(long pid, PendingOperations operations, List<String> relaunchCommand) throws IOException {
        StringBuilder script = new StringBuilder();
        script.append("#!/bin/sh\n");
        script.append("while kill -0 ").append(pid).append(" 2>/dev/null; do\n");
        script.append("  sleep 1\n");
        script.append("done\n");
        script.append("i=0\n");
        script.append("while [ $i -lt 5 ]; do\n");
        for (PendingOperations.Rename rename : operations.renames()) {
            script.append("  mv -f '").append(posixQuote(rename.tempPath())).append("' '").append(posixQuote(rename.finalPath())).append("' 2>/dev/null\n");
        }
        for (String deletePath : operations.deletes()) {
            script.append("  rm -f '").append(posixQuote(deletePath)).append("' 2>/dev/null\n");
        }
        script.append("  sleep 1\n");
        script.append("  i=$((i + 1))\n");
        script.append("done\n");
        if (relaunchCommand != null) {
            script.append("nohup").append(posixQuoteAll(relaunchCommand)).append(" >/dev/null 2>&1 &\n");
        }

        Path scriptPath = Files.createTempFile("pluginsync-relaunch-", ".sh");
        Files.writeString(scriptPath, script.toString());
        scriptPath.toFile().setExecutable(true);
        return scriptPath;
    }

    private static String posixQuote(String value) {
        return value.replace("'", "'\\''");
    }

    private static String posixQuoteAll(List<String> command) {
        StringBuilder joined = new StringBuilder();
        for (String part : command) {
            joined.append(" '").append(posixQuote(part)).append('\'');
        }
        return joined.toString();
    }
}
