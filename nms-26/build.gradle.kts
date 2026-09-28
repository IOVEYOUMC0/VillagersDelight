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

// The Minecraft/Paper dev bundle this layer compiles against. Overridable so a compatibility check can
// build the same sources against another release without editing this file:
//   gradlew build -PdevBundle=26.3.build.41-alpha
val devBundle = providers.gradleProperty("devBundle").getOrElse("26.1.2.build.74-stable")

// CraftEngine is resolved from Maven. Overridable so a compatibility check can build the same sources
// against another release without editing this file:  gradlew build -PceVersion=26.8.2
val ceVersion = providers.gradleProperty("ceVersion").getOrElse("26.9.1")

dependencies {
    paperweight.paperDevBundle(devBundle)
    compileOnly("org.jetbrains:annotations:26.1.0")
    // CraftEngine from the official Maven repository.
    compileOnly("net.momirealms:craft-engine-bukkit:$ceVersion")
    compileOnly("net.momirealms:craft-engine-core:$ceVersion")
    compileOnly("net.momirealms:craft-engine-bukkit-proxy:$ceVersion")
}

// The core layer (version-agnostic) lives under ../core and is compiled into this jar together
// with the NMS implementation, so the final plugin jar is self-contained.
// The shared NMS behaviour sources live in ../nms-common and are compiled into every adapter jar, but
// into a layer-specific package: all three layers are merged into one plugin jar, and a single shared
// com.huidu.villagersdelight.common copy can only be built against one dev bundle (26.x widened
// BlockState.is to Object), so it would fail with NoSuchMethodError on the servers of the other layers.
// The package is rewritten in the sources because this layer compiles at release 25, which the shadow
// relocator cannot read.
val layerNmsSources = layout.buildDirectory.dir("generated/nms-common")
val prepareLayerNmsSources = tasks.register<Sync>("prepareLayerNmsSources") {
    from("../nms-common/src/main/java")
    into(layerNmsSources)
    filter { line: String ->
        line.replace("com.huidu.villagersdelight.common", "com.huidu.villagersdelight.impl26.common")
    }
}

sourceSets {
    main {
        java.srcDir("../core/src/main/java")
        java.srcDir(layerNmsSources)
        resources.srcDir("../core/src/main/resources")
    }
}

tasks.named("compileJava") {
    dependsOn(prepareLayerNmsSources)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
    options.compilerArgs.add("-Xlint:deprecation")
}

tasks.shadowJar {
    archiveBaseName.set("villagersdelight")
    // 26.x-compatible: the jar targets any 26+ server, not one patch build.
    archiveClassifier.set("26")
}

tasks.jar {
    enabled = false
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
