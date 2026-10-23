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

// One Minecraft version for now; the ports (Fabric, 1.21.1, 1.20.1) add theirs here and in stonecutter.properties.toml.
stonecutter {
    create(rootProject) {
        versions("26.2")
        vcsVersion = "26.2"
    }
}

rootProject.name = "NTM-Reimagined-Arms"
