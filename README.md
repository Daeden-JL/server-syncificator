# plugin-sync

Keeps a Forge/NeoForge client's mods folder in sync with a server before it connects: on
launch, the client fetches a manifest from a small companion HTTP endpoint the server exposes,
diffs it against the local mods folder, downloads whatever changed (from Modrinth's CDN or
directly from the server), and restarts itself to apply the change - no more "server updated,
now go manually re-download 40 jars."

## Status

This repository currently ships:

- **`sync-core`** - a complete, loader-agnostic Java library containing the whole sync protocol:
  manifest fetch/serve, diffing, hashing, downloading, a hand-rolled `servers.dat` NBT editor, and
  a JVM relaunch helper. It has no Minecraft/Forge/NeoForge dependency and is fully unit tested
  (`./gradlew :sync-core:test`).
- **`loader/forge-1.20.1`** and **`loader/neoforge-1.21.1`** - thin per-loader adapters (client GUI
  screen + server startup hook) that wire `sync-core` into an actual mod.

CurseForge support is deliberately deferred (see [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)) -
the manifest/download-URL format is provider-agnostic, so it's a matter of adding a new resolver
alongside `ModrinthClient`, not a redesign.

## Build environment note

`sync-core` only needs plain `mavenCentral()` access. The two `loader/*` modules additionally need
ForgeGradle / the NeoForge ModDev plugin, which pull artifacts from Mojang's `piston-meta`,
`maven.minecraftforge.net`, and `maven.neoforged.net` - hosts that aren't reachable from every
environment (notably: sandboxed agent/CI environments with restrictive network policies). Because
of that, the loader modules are built and tested in **GitHub Actions CI**
([.github/workflows/build.yml](.github/workflows/build.yml)), which does have normal internet
access, rather than being assumed to compile in whatever environment a change was authored in. Both
loader modules are included in the root `settings.gradle.kts` by default; if you're working
somewhere without normal internet access to Mojang/Forge/NeoForge, comment them back out locally -
see [docs/ADDING_A_LOADER_VERSION.md](docs/ADDING_A_LOADER_VERSION.md).

## Quick start

1. **Server**: drop the built `pluginsync-forge-1.20.1.jar` (or `-neoforge-1.21.1.jar`) into the
   server's `mods` folder and start it once. It writes `config/pluginsync-server.json` and then
   refuses to actually start serving until you fill in `publicHost` and list the mods clients
   should get - see [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#server-config) for the format.
2. Restart the server. It now serves a manifest on `http://<publicHost>:<httpPort>/plugin-sync/v1/manifest`.
3. **Client**: put the matching mod jar in the client's `mods` folder, plus a
   `config/pluginsync-client.json` pointing `syncBaseUrl` at the server from step 2 (this file is
   meant to be pre-baked into whatever you hand out as the "client pack" - end users don't write it
   by hand).
4. Launch. The client fetches the manifest, downloads whatever's missing/changed, pins the server
   to the top of the multiplayer list, and restarts once to apply the change.

## Repository layout

```
sync-core/                      loader-agnostic sync engine
loader/forge-1.20.1/            Forge adapter (built/tested in CI, see note above)
loader/neoforge-1.21.1/         NeoForge adapter (built/tested in CI, see note above)
.github/workflows/build.yml     CI: builds + tests all of the above on every push/PR
docs/
  ARCHITECTURE.md               protocol, config formats, safety properties
  ADDING_A_LOADER_VERSION.md    how to enable the loader modules / add more MC versions
```
