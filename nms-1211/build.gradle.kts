plugins {
    id("java")
    id("io.github.goooler.shadow") version "8.1.7"
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.21"
}

group = "com.huidu.villagersdelight"
version = "0.1.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.momirealms.net/releases/")
}

// CraftEngine is pinned to the official Maven 26.9.1 artifacts.

dependencies {
    paperweight.paperDevBundle("1.21.11-R0.1-SNAPSHOT")
    compileOnly("org.jetbrains:annotations:26.1.0")
    // CraftEngine 26.9.1 from the official Maven repository.
    compileOnly("net.momirealms:craft-engine-bukkit:26.9.1")
    compileOnly("net.momirealms:craft-engine-core:26.9.1")
    compileOnly("net.momirealms:craft-engine-bukkit-proxy:26.9.1")
}

// The core layer (version-agnostic) lives under ../core and is compiled into this jar together
// with the NMS implementation, so the final plugin jar is self-contained.
// The shared NMS behaviour sources live in ../nms-common and are compiled into every adapter jar.
sourceSets {
    main {
        java.srcDir("../core/src/main/java")
        java.srcDir("../nms-common/src/main/java")
        resources.srcDir("../core/src/main/resources")
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
    options.compilerArgs.add("-Xlint:deprecation")
}

tasks.shadowJar {
    archiveBaseName.set("villagersdelight")
    archiveClassifier.set("1.21.11")
}

tasks.jar {
    enabled = false
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
