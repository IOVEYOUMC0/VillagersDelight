pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "io.papermc.paperweight.userdev") {
                useModule("io.papermc.paperweight:paperweight-userdev:${requested.version}")
            }
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "villagersdelight-nms-26"
