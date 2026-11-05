pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.neoforged.net/releases/") { name = "NeoForged" }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// NTM Reimagined Arms for Minecraft 1.21.1 and NTM: Decentralized Edition (NeoForge 21.1): a build of its own next to
// the 26.2 one at the repository root, which it reads its shared sources from (README.md here)
rootProject.name = "reimagined-arms-1.21.1-de"
