package dev.pluginsync.core.sync;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SyncStatusTest {

    @AfterEach
    void tearDown() {
        // SyncStatus is process-global by design, so a leaked state would bleed into other tests.
        SyncStatus.reset();
    }

    @Test
    void defaultsToUnknownSoAnUnconfiguredClientShowsNothing() {
        SyncStatus.reset();

        assertEquals(SyncStatus.State.UNKNOWN, SyncStatus.get().state());
        assertNull(SyncStatus.get().detail());
    }

    @Test
    void retainsStateAndDetail() {
        SyncStatus.set(SyncStatus.State.FAILED, "connection refused");

        SyncStatus.Snapshot snapshot = SyncStatus.get();
        assertEquals(SyncStatus.State.FAILED, snapshot.state());
        assertEquals("connection refused", snapshot.detail());
    }

    @Test
    void setWithoutDetailLeavesDetailNull() {
        SyncStatus.set(SyncStatus.State.UP_TO_DATE);

        assertEquals(SyncStatus.State.UP_TO_DATE, SyncStatus.get().state());
        assertNull(SyncStatus.get().detail());
    }

    @Test
    void laterSetReplacesEarlierStateAndDetail() {
        SyncStatus.set(SyncStatus.State.FAILED, "connection refused");
        SyncStatus.set(SyncStatus.State.UP_TO_DATE);

        SyncStatus.Snapshot snapshot = SyncStatus.get();
        assertEquals(SyncStatus.State.UP_TO_DATE, snapshot.state());
        assertNull(snapshot.detail(), "stale failure detail must not survive a later success");
    }

    @Test
    void snapshotIsUnaffectedByLaterWrites() {
        SyncStatus.set(SyncStatus.State.RESTART_REQUIRED, "automatic restart failed");
        SyncStatus.Snapshot held = SyncStatus.get();

        SyncStatus.set(SyncStatus.State.FAILED, "something else");

        assertEquals(SyncStatus.State.RESTART_REQUIRED, held.state());
        assertEquals("automatic restart failed", held.detail());
    }
}
