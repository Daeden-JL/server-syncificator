rootProject.name = "plugin-sync"

include("sync-core")

// Loader adapter modules require ForgeGradle / NeoGradle, which resolve
// artifacts from Mojang/Forge/NeoForge Maven repositories. They are not part
// of the default build graph here because those repositories are not always
// reachable in every environment (notably: sandboxed CI/agent environments).
// Uncomment once building on a machine with full internet access, or see
// docs/ADDING_A_LOADER_VERSION.md.
// include("loader:forge-1.20.1")
// include("loader:neoforge-1.21.1")
