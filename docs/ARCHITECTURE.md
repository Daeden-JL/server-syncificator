# Architecture

## Why a restart is unavoidable

Forge and NeoForge both load every mod jar once, onto classloaders built at JVM startup. There is
no supported way to add, remove, or replace a mod jar in a running client. So "sync then apply"
fundamentally means "sync, then start a new JVM with the updated mods folder" - there's no
in-session trick that avoids this for actual mod-jar changes. `plugin-sync` embraces that: it syncs
during the loading/title-screen phase, then relaunches itself once, automatically, before the
player ever reaches the "Play"/"Multiplayer" button.

## Components

```
                    HTTP (manifest + file fallback)
   ┌─────────────┐  ────────────────────────────►  ┌─────────────┐
   │   Client     │                                 │   Server    │
   │ (loader mod) │  ◄────────────────────────────  │ (loader mod)│
   └─────────────┘        mod jar bytes              └─────────────┘
         │                                                  │
         ▼                                                  ▼
   sync-core:                                          sync-core:
   SyncSession, Downloader,                             ManifestBuilder,
   DiffEngine, ServersDatEditor,                        ManifestHttpServer,
   RelaunchHelper                                       ModrinthClient
```

`sync-core` has zero Minecraft/Forge/NeoForge dependency. Everything loader-specific is a thin
adapter: a GUI screen that renders `SyncEvent`s, and two lifecycle hooks (client title-screen
interception, server startup). This is what makes "support NeoForge and Forge together" tractable:
the hard logic (protocol, hashing, retries, safe deletion, NBT editing, relaunching) is written and
tested exactly once.

## Wire protocol

`GET {baseUrl}/plugin-sync/v1/manifest` → JSON `SyncManifest`:

```json
{
  "protocolVersion": 1,
  "serverName": "My Server",
  "motd": "",
  "mods": [
    {
      "fileName": "jei-1.20.1.jar",
      "sha256": "...",
      "size": 123456,
      "downloadUrls": ["https://cdn.modrinth.com/data/.../jei.jar", "http://host:25585/plugin-sync/v1/files/jei-1.20.1.jar"],
      "side": "BOTH"
    }
  ]
}
```

`downloadUrls` is ordered by preference. A client tries each in turn until one both responds and
hashes to `sha256` - this is how "Modrinth or CurseForge or direct from the server" is expressed:
the server resolves external sources ahead of time (see `ManifestBuilder`/`ModrinthClient`) and
always appends its own `GET /plugin-sync/v1/files/{fileName}` as the last, guaranteed-available
entry. The client never talks to Modrinth (or any third party) directly - it only ever does plain
HTTP GETs against whatever URLs the server handed it, verifying every byte against the hash before
trusting it.

`protocolVersion` lets a client detect "this server speaks a manifest format I don't understand"
and refuse to sync (rather than silently doing something wrong) instead of guessing.

## Safety: what can and can't be deleted

Every managed file is tracked in `config/pluginsync-managed.json` on the client (filename → sha256
last installed). `DiffEngine` only ever proposes deleting a file that is *both* tracked there *and*
absent from the current manifest. A jar the player dropped in by hand is never touched, because
it's never in that file. See `DiffEngineTest` and the `unmanagedExtraModIsNeverDeleted` /
`removingModFromServerDeletesItLocallyButLeavesUserAddedJarsAlone` tests for the exact guarantee.

`ServersDatEditor` is similarly conservative: it only ever inserts/moves the one entry matching the
configured server's IP, preserving every other entry (and every NBT field on them, even ones this
codebase doesn't know about) byte-for-byte. If the file can't be parsed, nothing is written - see
`NbtIo`/`ServersDatEditor`.

## Server config (`config/pluginsync-server.json`)

```json
{
  "serverName": "My Server",
  "motd": "",
  "httpPort": 25585,
  "httpBind": "0.0.0.0",
  "publicHost": "play.example.com",
  "mods": [
    { "fileName": "jei-1.20.1.jar", "source": "MODRINTH", "modrinthVersionId": "abcdEFGH", "side": "BOTH" },
    { "fileName": "my-private-mod.jar", "source": "DIRECT", "modrinthVersionId": null, "side": "BOTH" },
    { "fileName": "server-admin-tools.jar", "source": "DIRECT", "modrinthVersionId": null, "side": "SERVER_ONLY" }
  ]
}
```

- `source: "MODRINTH"` + `modrinthVersionId` - the server resolves that version's primary file via
  Modrinth's public API at manifest-build time. If the resolved hash doesn't match what's actually
  in the server's `mods` folder, the external URL is dropped and the entry falls back to
  direct-from-server only (see `ManifestBuilderTest#modrinthHashMismatchFallsBackToDirectUrlOnly`) -
  the file on disk is always the source of truth, never the third-party API.
- `source: "DIRECT"` - only ever served by this server.
- `side: "SERVER_ONLY"` entries are excluded from the manifest entirely (never advertised to
  clients).

The server writes this file with an empty `publicHost` and an empty `mods` list the first time it
runs, and **refuses to start serving** until an admin fills those in - see
`ServerLifecycleHandler`.

## Client config (`config/pluginsync-client.json`)

```json
{
  "enabled": true,
  "syncBaseUrl": "http://play.example.com:25585",
  "serverAddress": "play.example.com",
  "serverListName": "My Server",
  "autoRestart": true,
  "pinToServerList": true
}
```

This file is meant to ship pre-filled as part of whatever client distribution you hand out (a
modpack, a launcher profile) - end users aren't expected to hand-write it.

## CurseForge

Deferred by design choice (see the top-level conversation this was scoped from): CurseForge's API
requires a registered key with third-party-tool usage terms, whereas Modrinth's is open and
keyless. Adding it later means: add `CURSEFORGE` to `ModSource`, write a `CurseForgeClient`
alongside `ModrinthClient` with the same `resolveVersion(...) -> ResolvedFile` shape, and wire it
into `ManifestBuilder`'s source dispatch. Nothing else in the protocol, client, or GUI needs to
change - `downloadUrls` is already provider-agnostic.
