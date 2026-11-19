pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.neoforged.net/releases/") { name = "NeoForged" }
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie Releases" }
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.9.8"
}

// One Minecraft version is built: 26.2. Another one comes in two ways, both on core/ (below), which every version
// compiles as it is, the same files with no //? in them (CoreBoundaryTest):
//  - a version near 26.2 (26.1): a version here and in stonecutter.properties.toml, src/ shared with //? comments
//    where the game differs, its own build script if the loader differs: version("26.1-neoforge", "26.1").buildscript;
//  - a far one (1.21.1, 1.12.2, 1.7.10: a different game and build plugin): its own build under port/<version>/ with
//    its own adapter, compiling core/src/main/java as a source directory, at release 21 or above (core/build.gradle.kts,
//    compileForPorts), and giving its own classes for what core loads (META-INF/services, CoreServicesFoundTest).
stonecutter {
    create(rootProject) {
        versions("26.2")
        vcsVersion = "26.2"
    }
}

rootProject.name = "NTM-Reimagined-Arms"

// core/: the code every version shares, built alone without Minecraft, and at the ports' release (core/build.gradle.kts)
include("core")
