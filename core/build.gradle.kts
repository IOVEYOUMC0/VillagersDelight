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

// CraftEngine is pinned to the vendored 26.8 jar shared from ../FarmersDelight/libs/.

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    compileOnly("org.jetbrains:annotations:26.1.0")
    // CraftEngine — pinned to the vendored 26.8 jar (shared from ../FarmersDelight/libs; 26.8-SNAPSHOT is unpublished).
    compileOnly(files("../../FarmersDelight/libs/craft-engine-26.8.jar"))
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
