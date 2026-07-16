package dev.pluginsync.core.sync;

/**
 * Session-scoped record of how this JVM's sync attempt ended, so a loader can surface it after
 * {@link SyncSession} has finished and its progress screen has closed (typically: a line on the
 * title screen).
 *
 * <p>Deliberately process-global mutable state: the sync runs at most once per JVM session, on a
 * background thread, and the result has to outlive the screen that produced it. Reads come from
 * the client render thread while the write comes from the sync worker, hence the volatile field
 * and the immutable {@link Snapshot} - callers always see a consistent state/detail pair rather
 * than a half-updated one.
 */
public final class SyncStatus {

    /** What to tell the user about the last sync attempt. */
    public enum State {
        /** No sync ran: not configured, disabled, or nothing has reported in yet. */
        UNKNOWN,
        /** Sync ran (or was already satisfied) and the mods folder matches the server. */
        UP_TO_DATE,
        /** Sync changed the mods folder, but the game hasn't been restarted to apply it. */
        RESTART_REQUIRED,
        /** Sync could not complete; {@code detail} carries the reason. */
        FAILED
    }

    /** @param detail human-readable extra context, or null. Only meaningful for {@link State#FAILED}. */
    public record Snapshot(State state, String detail) {
    }

    private static final Snapshot UNKNOWN_SNAPSHOT = new Snapshot(State.UNKNOWN, null);

    private static volatile Snapshot current = UNKNOWN_SNAPSHOT;

    private SyncStatus() {
    }

    public static Snapshot get() {
        return current;
    }

    public static void set(State state, String detail) {
        current = new Snapshot(state, detail);
    }

    public static void set(State state) {
        set(state, null);
    }

    /** Resets to {@link State#UNKNOWN}. Exists for tests - a real session never needs it. */
    public static void reset() {
        current = UNKNOWN_SNAPSHOT;
    }
}
