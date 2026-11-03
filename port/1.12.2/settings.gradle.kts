pluginManagement {
    repositories {
        maven {
            // RetroFuturaGradle
            name = "GTNH Maven"
            url = uri("https://nexus.gtnewhorizons.com/repository/public/")
            mavenContent {
                includeGroup("com.gtnewhorizons")
                includeGroupByRegex("com\\.gtnewhorizons\\..+")
            }
        }
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// NTM Reimagined Arms for Minecraft 1.12.2 (NTM Community Edition): a build of its own next to the 26.2 one at the
// repository root and the 1.7.10 one in port/1.7.10
rootProject.name = "reimagined-arms-1.12.2"
