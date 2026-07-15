package dev.pluginsync.core.relaunch;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
 * <p>This is inherently best-effort: some launchers/sandboxes restrict reading back the full
 * command line, in which case {@link #relaunch()} throws and the caller should fall back to
 * telling the user to restart manually.
 */
public final class RelaunchHelper {

    /** Set as a {@code -D} system property on the relaunched process; check with {@link #isPostRelaunch()}. */
    public static final String POST_RELAUNCH_PROPERTY = "pluginsync.postRelaunch";

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
     *     environments hide this), or the new process fails to start.
     */
    public static Process relaunch() throws IOException {
        ProcessHandle.Info info = ProcessHandle.current().info();
        String command = info.command().orElseThrow(
                () -> new IOException("Current process command is not available - cannot self-relaunch"));
        String[] arguments = info.arguments().orElseThrow(
                () -> new IOException("Current process arguments are not available - cannot self-relaunch"));

        List<String> fullCommand = new ArrayList<>(arguments.length + 2);
        fullCommand.add(command);
        // Inserted immediately after the executable so it's parsed as a JVM option regardless of
        // where -jar/-cp/mainclass appears later in the original argument list.
        fullCommand.add("-D" + POST_RELAUNCH_PROPERTY + "=true");
        fullCommand.addAll(List.of(arguments));

        ProcessBuilder builder = new ProcessBuilder(fullCommand);
        builder.inheritIO();
        Path workingDir = Path.of(System.getProperty("user.dir"));
        builder.directory(workingDir.toFile());
        return builder.start();
    }
}
