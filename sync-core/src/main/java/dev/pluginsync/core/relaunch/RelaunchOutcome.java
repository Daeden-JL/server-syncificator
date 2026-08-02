package dev.pluginsync.core.relaunch;

/**
 * Result of {@link RelaunchHelper#relaunchWithPendingOperations}. The trampoline script always
 * gets launched and will apply the pending renames/deletes once this JVM exits - the two variants
 * only differ in whether that script can also restart Minecraft afterward, which requires the
 * platform to expose the original launch command line (never true on Windows).
 */
public sealed interface RelaunchOutcome {

    /** The trampoline will apply pending operations and then relaunch Minecraft on its own. */
    record Relaunched(Process process) implements RelaunchOutcome {
    }

    /**
     * The trampoline will apply pending operations once this JVM exits, but can't restart
     * Minecraft afterward - the player closing the game themselves is what lets it finish.
     */
    record ApplyScheduledOnly(Process process) implements RelaunchOutcome {
    }
}
