package dev.pluginsync.core.config;

/** Where the server should try to resolve an external download URL for a mod from. */
public enum ModSource {
    /** No external source configured; the file is only ever served directly by this server. */
    DIRECT,
    /** Resolve via the public Modrinth API using a configured version ID. */
    MODRINTH
    // CURSEFORGE intentionally omitted for the first release: CurseForge requires a registered
    // API key with third-party usage terms. Add it here + a matching resolver once a key is
    // available, following the same pattern as ModrinthClient.
}
