plugins {
    id("java")
    id("io.github.goooler.shadow") version "8.1.7"
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.21"
}

group = "com.huidu.villagersdelight"
version = "0.1.1"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.momirealms.net/releases/")
}

// CraftEngine is resolved from Maven. Overridable so a compatibility check can build the same sources
// against another release without editing this file:  gradlew build -PceVersion=26.8.2
val ceVersion = providers.gradleProperty("ceVersion").getOrElse("26.9.1")

dependencies {
    paperweight.paperDevBundle("1.21.4-R0.1-SNAPSHOT")
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
// 1.21.4 also keeps Villager in net.minecraft.world.entity.npc, while the shared sources use the
// 1.21.11+ npc.villager package, so that import is rewritten in the same pass. Sync keeps the
// generated folder free of stale files.
val layerNmsSources = layout.buildDirectory.dir("generated/nms-common")
val prepareLayerNmsSources = tasks.register<Sync>("prepareLayerNmsSources") {
    from("../nms-common/src/main/java")
    into(layerNmsSources)
    filter { line: String ->
        line.replace("com.huidu.villagersdelight.common", "com.huidu.villagersdelight.impl214.common")
            .replace("net.minecraft.world.entity.npc.villager.Villager", "net.minecraft.world.entity.npc.Villager")
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
    archiveClassifier.set("1.21.4")
}

tasks.jar {
    enabled = false
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
