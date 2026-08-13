# Architecture

## Why a restart is unavoidable

Forge and NeoForge both load every mod jar once, onto classloaders built at JVM startup. There is
no supported way to add, remove, or replace a mod jar in a running client. So "sync then apply"
fundamentally means "sync, then start a new JVM with the updated mods folder" - there's no
in-session trick that avoids this for actual mod-jar changes. `server-syncificator` embraces that: it syncs
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
adapter: a GUI screen that renders `SyncEvent`s, an overlay that renders the final `SyncStatus` as
a line on the title screen, a title-screen button that lets the player re-trigger that same GUI
screen manually ("Check for Updates", or "Sync Updates" once a sync has something pending), and two
lifecycle hooks (client title-screen interception, server startup). This is what makes "support
NeoForge and Forge together" tractable:
the hard logic (protocol, hashing, retries, safe deletion, NBT editing, relaunching) is written and
tested exactly once.

## Wire protocol

`GET {baseUrl}/daedens-server-syncificator/v1/manifest` → JSON `SyncManifest`:

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
      "downloadUrls": ["https://cdn.modrinth.com/data/.../jei.jar", "http://host:25585/daedens-server-syncificator/v1/files/jei-1.20.1.jar"],
      "side": "BOTH"
    }
  ]
}
```

`downloadUrls` is ordered by preference. A client tries each in turn until one both responds and
hashes to `sha256` - this is how "Modrinth or CurseForge or direct from the server" is expressed:
the server resolves external sources ahead of time (see `ManifestBuilder`/`ModrinthClient`) and
always appends its own `GET /daedens-server-syncificator/v1/files/{fileName}` as the last, guaranteed-available
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

## Server config (`config/daedens-server-syncificator-server.json`)

```json
{
  "serverName": "My Server",
  "motd": "",
  "httpPort": 25585,
  "httpBind": "0.0.0.0",
  "publicHost": "play.example.com",
  "autoServeModsFolder": true,
  "selfUpdateEnabled": true,
  "mods": [
    { "fileName": "jei-1.20.1.jar", "source": "MODRINTH", "modrinthVersionId": "abcdEFGH", "side": "BOTH" },
    { "fileName": "server-admin-tools.jar", "source": "DIRECT", "modrinthVersionId": null, "side": "SERVER_ONLY" }
  ]
}
```

`mods` is the single source of truth for *which* mods are advertised - `ManifestBuilder` builds the
manifest from this list and nothing else, so reading this file tells you exactly what clients will
be offered.

### `autoServeModsFolder` (default `true`)

On every server start, `ServerConfigStore#reconcileWithModsFolder` brings the list in step with the
mods folder and **writes it back to this file**:

- a jar with no entry gets one (`DIRECT`/`BOTH`) - so adding a mod is just dropping the jar in and
  restarting;
- an entry whose jar is gone is dropped, so the file keeps describing reality;
- **except** a `SERVER_ONLY` entry, which is kept even with no file behind it. That entry is a
  standing rule about a *name*, not a description of the folder: dropping it would mean putting that
  jar back later silently starts publishing it to clients.

Anything the admin edited - `SERVER_ONLY`, a Modrinth source - is preserved; reconciling only ever
adds or removes whole entries. An unchanged folder reports no change, so the file isn't rewritten on
every boot.

What is deliberately **not** persisted is the manifest itself. `sha256`, `size` and the download
URLs are derived from the jars on disk on each request, and change whenever a mod is updated;
freezing them into a hand-editable file would produce a config that lies about the files beside it,
and hashes nobody can maintain by hand.

Set `autoServeModsFolder` to `false` for a strict hand-managed allowlist: nothing is added
automatically, and a jar in the folder with no entry is never advertised.

- `source: "MODRINTH"` + `modrinthVersionId` - the server resolves that version's primary file via
  Modrinth's public API at manifest-build time. If the resolved hash doesn't match what's actually
  in the server's `mods` folder, the external URL is dropped and the entry falls back to
  direct-from-server only (see `ManifestBuilderTest#modrinthHashMismatchFallsBackToDirectUrlOnly`) -
  the file on disk is always the source of truth, never the third-party API.
- `source: "DIRECT"` - only ever served by this server.
- `side: "SERVER_ONLY"` entries are excluded from the manifest entirely (never advertised to
  clients).

The server writes this file with an empty `publicHost` the first time it runs, and **refuses to
start serving** until an admin fills it in - clients need a reachable address to download from. See
`ServerLifecycleHandler`. With `autoServeModsFolder` left at its default, `publicHost` is the only
field that must be set by hand.

### `selfUpdateEnabled` (default `true`) - the server updates itself

`ServerSelfUpdateScheduler` checks `Daeden-JL/server-syncificator`'s GitHub releases on startup,
then once a day while the server keeps running (`SelfUpdateChecker`), and - if the latest release
is newer than the version currently running - downloads the matching loader jar and hash-verifies
it against a `checksums.txt` published alongside each release (GitHub's release API doesn't hand
back file hashes itself, so the release workflow generates and publishes one; see
`.github/workflows/release.yml`).

Because the new jar always lands under a brand-new file name (the version is baked into every
release's filename, same as always), downloading it never collides with anything on disk. Cleanup
afterward sweeps the mods folder for *every* jar matching this loader's naming prefix other than the
one just downloaded, rather than deleting one specific filename reconstructed from "the currently
running version" - observed for real on a server updating 0.1.5 -> 0.1.6, where 0.1.5's own jar
manifest didn't carry a resolvable version at all, so it went looking for a file that was never
going to exist and left its real jar sitting there. Sweeping by prefix means a stray copy gets
cleaned up regardless of what caused it to go unrecognized.

Removing a stale jar is the part that can be blocked: NeoForge/Forge keep every mod jar this JVM
has loaded open for the process's whole life, so deleting the file this code is presently running
from can fail on Windows. When that happens the removal is handed to
`RelaunchHelper#applyOperationsOnExit`, which waits for this JVM to actually exit and then deletes
it - deliberately not a relaunch of any kind (see `RelaunchOutcome`), since when this server process
starts again is an admin's decision, not something to act on here. Either way, the update only
takes effect the next time an admin restarts the server themselves; this never restarts it or kicks
players on its own.

Once the new jar is sitting in the server's mods folder, it's just another file
`ServerConfigStore#reconcileWithModsFolder` picks up on the next restart (see above) - clients then
receive it exactly the way they receive any other mod update, including the same deferred-apply
handling if their own copy happens to be locked (see "Why a restart is unavoidable" and
`RelaunchHelper`).

## Client config (`config/daedens-server-syncificator-client.json`)

```json
{
  "enabled": true,
  "serverHost": "play.example.com",
  "syncPort": 25585,
  "minecraftPort": 25565,
  "serverListName": "My Server",
  "autoRestart": true,
  "pinToServerList": true
}
```

The server is described as a host plus two ports, not a URL. `syncPort` is the companion HTTP
endpoint (`ServerConfig.DEFAULT_HTTP_PORT`, must match the server's `httpPort`); `minecraftPort` is
the game port, used only when pinning to the multiplayer list. Both the sync URL and the server-list
address are **derived** (`ClientConfig#syncBaseUrl`, `#serverAddress`) rather than stored, so the two
ports can't drift out of agreement with each other.

`ClientConfigStore#loadOrCreate` writes this file with an empty `serverHost` on first launch, and
the mod stays idle until one is set - a client with no host configured never contacts anything. It
can still be pre-baked into a client distribution (modpack, launcher profile); the difference is
that a hand-installed client now gets a file to edit instead of nothing at all.

Values are editable two ways, and both write the same file:

- directly, then restart;
- in-game via **Mods > Daeden's Server Syncificator > Config** (`ClientConfigScreen`, registered as
  a config-screen extension point per loader).

A hand-edited file is repaired on load by `ClientConfig#normalize()` (trims the host, replaces
out-of-range ports with defaults, tolerates explicit JSON `null`s), so a typo degrades to a default
rather than a malformed URL. An *unparseable* file is different: it's logged loudly and syncing is
skipped for the session, because silently treating it as "not configured" is what made the old
behaviour so hard to diagnose.

The sync decision is made once, when the title screen first opens, so config changes - from either
route - take effect on the next launch.

## CurseForge

Deferred by design choice (see the top-level conversation this was scoped from): CurseForge's API
requires a registered key with third-party-tool usage terms, whereas Modrinth's is open and
keyless. Adding it later means: add `CURSEFORGE` to `ModSource`, write a `CurseForgeClient`
alongside `ModrinthClient` with the same `resolveVersion(...) -> ResolvedFile` shape, and wire it
into `ManifestBuilder`'s source dispatch. Nothing else in the protocol, client, or GUI needs to
change - `downloadUrls` is already provider-agnostic.
