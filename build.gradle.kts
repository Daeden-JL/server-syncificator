plugins {
    java
}

// The root project has no sources, so its jar is empty - but it's named after rootProject.name,
// which makes it look exactly like the mod jar you're meant to ship. Producing it only creates an
// opportunity to grab the wrong file and get "is not a valid mod file" from the loader.
tasks.jar {
    enabled = false
}

allprojects {
    group = "dev.pluginsync"
    version = "0.1.2"

    repositories {
        mavenCentral()
        maven("https://maven.minecraftforge.net/")
        maven("https://maven.neoforged.net/releases")
    }
}
