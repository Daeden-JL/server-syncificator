plugins {
    `java-library`
}

// Targets Java 17 bytecode (the minimum required by Forge/NeoForge for MC 1.20.1) via the
// --release flag rather than a toolchain, so the build doesn't require provisioning/downloading a
// specific JDK vendor - it compiles fine with any JDK 17+ running Gradle itself.
tasks.withType<JavaCompile> {
    options.release.set(17)
}

dependencies {
    api("com.google.code.gson:gson:2.11.0")

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
