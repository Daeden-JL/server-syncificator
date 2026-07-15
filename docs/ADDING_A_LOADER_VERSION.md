# Enabling the loader modules / adding another Minecraft version

## Enabling the two reference modules (Forge 1.20.1, NeoForge 1.21.1)

These aren't wired into the root build by default because building them requires
`maven.minecraftforge.net`, `maven.neoforged.net`, and Mojang's `piston-meta` service, which
weren't reachable in the sandbox this repo was scaffolded in. On a machine with normal internet
access:

1. Uncomment the two `include(...)` lines in `settings.gradle.kts`.
2. Run `./gradlew build`. ForgeGradle/NeoForge ModDev will download the Minecraft/mapping/loader
   artifacts on first run (this takes a while and several GB of disk the first time).
3. Fix up any compile errors from API drift - the loader modules were written against documented
   API shapes from memory, not compiled, so treat the first build as a review pass. Likely trouble
   spots: exact `FMLJavaModLoadingContext`/constructor-injection details for NeoForge (the mod
   constructor event-bus wiring is the part most likely to have shifted between NeoForge patch
   versions), and the Forge/NeoForge version numbers pinned in each `build.gradle`.

## Adding another Minecraft version or loader

The pattern is: everything hard lives in `sync-core` (already version-agnostic - it has no
Minecraft dependency at all). A new loader module is a thin adapter, structurally identical to the
two that exist:

1. Copy `loader/forge-1.20.1` (or `neoforge-1.21.1`, whichever is closer to your target) to
   `loader/<loader>-<mc-version>`.
2. Update `build.gradle`: bump the Forge/NeoForge version, the Minecraft version, and (Forge only)
   the Parchment mappings version to match.
3. Update `mods.toml`/`neoforge.mods.toml`'s Minecraft version range.
4. Port the four Java classes:
   - `PluginSync<Loader>.java` - mod entrypoint, wires the mod event bus. Rarely changes between
     patch versions of the same loader.
   - `ClientSyncManager.java` - intercepts the title screen. Rarely changes.
   - `ServerLifecycleHandler.java` - starts the manifest HTTP server on dedicated-server startup.
     Loader/version-independent logic; only the `FMLPaths`/event-class imports change.
   - `SyncProgressScreen.java` - the GUI. This is the one most likely to need real changes if a
     future Minecraft version changes the `Screen`/`GuiGraphics` rendering API (it hasn't changed
     since `GuiGraphics` was introduced in 1.20, so 1.20.x-1.21.x should all be near-identical).
5. Add `include("loader:<loader>-<mc-version>")` to `settings.gradle.kts`.

None of this touches `sync-core`, the wire protocol, or the test suite - a new loader module is
purely additive.
