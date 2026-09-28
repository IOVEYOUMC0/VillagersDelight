plugins {
    id("java")
}

group = "com.huidu.villagersdelight"
version = "0.1.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.momirealms.net/releases/")
}

// CraftEngine is resolved from Maven. Overridable so a compatibility check can build the same sources
// against another release without editing this file:  gradlew build -PceVersion=26.8.2
val ceVersion = providers.gradleProperty("ceVersion").getOrElse("26.9.1")

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    compileOnly("org.jetbrains:annotations:26.1.0")
    // CraftEngine from the official Maven repository.
    compileOnly("net.momirealms:craft-engine-bukkit:$ceVersion")
    // The bukkit artifact no longer bundles core, so the core classes come from their own jar.
    compileOnly("net.momirealms:craft-engine-core:$ceVersion")
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

tasks.jar {
    archiveBaseName.set("villagersdelight-core")
}

configurations.testImplementation {
    extendsFrom(configurations.compileOnly.get())
}

val checkFoodRules = tasks.register<JavaExec>("checkFoodRules") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.huidu.villagersdelight.core.VillagerFoodRulesCheck")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    enableAssertions = true
}

tasks.check {
    dependsOn(checkFoodRules)
}
