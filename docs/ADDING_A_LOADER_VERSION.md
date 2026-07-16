# Enabling the loader modules / adding another Minecraft version

## The two reference modules (Forge 1.20.1, NeoForge 1.21.1)

Both are included in the root `settings.gradle.kts` by default and are built/tested by
[.github/workflows/build.yml](../.github/workflows/build.yml) on every push and pull request.
Building them requires `maven.minecraftforge.net`, `maven.neoforged.net`, and Mojang's
`piston-meta` service - reachable from GitHub Actions' runners, but not from every environment
(notably: sandboxed agent/CI environments with restrictive network policies). If you're working
somewhere without normal internet access to those hosts, comment the two `include(...)` lines back
out in `settings.gradle.kts` and rely on CI (or a machine with normal internet access) to verify
loader-module changes instead of a local `./gradlew build`.

On a machine (or CI run) with normal internet access, the first build downloads the
Minecraft/mapping/loader artifacts (this takes a while and several GB of disk the first time), then
compiles normally. If you're porting these modules to a new Forge/NeoForge patch version and hit
compile errors, likely trouble spots are: exact `FMLJavaModLoadingContext`/constructor-injection
details for NeoForge (the mod constructor event-bus wiring is the part most likely to have shifted
between NeoForge patch versions), and the Forge/NeoForge version numbers pinned in each
`build.gradle`.

## Adding another Minecraft version or loader

The pattern is: everything hard lives in `sync-core` (already version-agnostic - it has no
Minecraft dependency at all). A new loader module is a thin adapter, structurally identical to the
two that exist:

1. Copy `loader/forge-1.20.1` (or `neoforge-1.21.1`, whichever is closer to your target) to
   `loader/<loader>-<mc-version>`.
2. Update `build.gradle`: bump the Forge/NeoForge version, the Minecraft version, and (Forge only)
   the Parchment mappings version to match.
3. Update `mods.toml`/`neoforge.mods.toml`'s Minecraft version range.
4. Port the six Java classes:
   - `PluginSync<Loader>.java` - mod entrypoint, wires the mod event bus and registers the
     config-screen extension point. That extension point is the least portable thing here: Forge
     1.20.1 uses `ConfigScreenHandler.ConfigScreenFactory` via `ModLoadingContext`, NeoForge 1.21.1
     uses `IConfigScreenFactory` via the injected `ModContainer`. Expect to rewrite this call.
   - `ClientSyncManager.java` - intercepts the title screen, and owns the config file path. Rarely
     changes.
   - `ServerLifecycleHandler.java` - starts the manifest HTTP server on dedicated-server startup.
     Loader/version-independent logic; only the `FMLPaths`/event-class imports change.
   - `SyncProgressScreen.java` - the GUI. This is the one most likely to need real changes if a
     future Minecraft version changes the `Screen`/`GuiGraphics` rendering API (it hasn't changed
     since `GuiGraphics` was introduced in 1.20, so 1.20.x-1.21.x should all be near-identical).
   - `SyncStatusOverlay.java` - draws the bottom-right title-screen status line from
     `SyncStatus`. Same `GuiGraphics` caveat as above; it also hardcodes an offset that assumes
     vanilla draws its copyright line at `height - 10`, so check that if a version moves it.
   - `ClientConfigScreen.java` - the in-game config GUI. Mostly portable, with one known
     difference: on 1.20.1 `render` must call `renderBackground` itself, while on 1.21.x
     `Screen.render` already does it (calling it twice draws the dimmer twice).
5. Add `include("loader:<loader>-<mc-version>")` to `settings.gradle.kts`.

None of this touches `sync-core`, the wire protocol, or the test suite - a new loader module is
purely additive.
