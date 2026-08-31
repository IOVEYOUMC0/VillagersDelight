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

rootProject.name = "villagersdelight-nms-1.21.4"
