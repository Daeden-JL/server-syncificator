package dev.pluginsync.core.sync;

/** Progress events emitted by {@link SyncSession}, consumed by a loader-specific GUI screen. */
public sealed interface SyncEvent {

    record Connecting(String baseUrl) implements SyncEvent {
    }

    record FetchingManifest() implements SyncEvent {
    }

    record Diffing() implements SyncEvent {
    }

    record Deleting(String fileName, int fileIndex, int totalFiles) implements SyncEvent {
    }

    record Downloading(String fileName, long bytesDownloaded, long totalBytes, int fileIndex, int totalFiles)
            implements SyncEvent {
    }

    record Finalizing() implements SyncEvent {
    }

    /** {@code restartRequired} is true whenever any file was added, updated, or removed. */
    record Complete(boolean restartRequired) implements SyncEvent {
    }

    record Failed(String message, Throwable cause) implements SyncEvent {
    }
}
