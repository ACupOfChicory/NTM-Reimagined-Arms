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
        // port/continuum: NTM Continuum on Golem Core. The node name carries the loader, the version is given apart
        // (else Stonecutter reads "-neoforge" as a pre-release). Its own buildscript: build.gradle.kts stays main's.
        version("26.1-neoforge", "26.1").buildscript = "build.continuum.gradle.kts"
        vcsVersion = "26.2"
    }
}

rootProject.name = "NTM-Reimagined-Arms"
