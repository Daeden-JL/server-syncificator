pluginManagement {
    repositories {
        gradlePluginPortal()
        // ForgeGradle isn't published to the Gradle Plugin Portal - it only resolves from
        // Forge's own Maven. NeoForge's ModDev plugin is on the Plugin Portal, but its repo is
        // listed too as a documented fallback (matches the official NeoForge MDK template).
        maven("https://maven.minecraftforge.net/")
        maven("https://maven.neoforged.net/releases")
    }
}

rootProject.name = "plugin-sync"

include("sync-core")

// Loader adapter modules require ForgeGradle / the NeoForge ModDev plugin, which resolve
// artifacts from Mojang/Forge/NeoForge Maven repositories. Those hosts are unreachable from the
// sandbox this project was originally scaffolded in, so these modules could only be built/tested
// in GitHub Actions CI (see .github/workflows/build.yml) - not locally in that environment. If
// you're building somewhere without normal internet access to Mojang/Forge/NeoForge, comment
// these back out; see docs/ADDING_A_LOADER_VERSION.md.
include("loader:forge-1.20.1")
include("loader:neoforge-1.21.1")
