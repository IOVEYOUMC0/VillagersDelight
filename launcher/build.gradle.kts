plugins {
    id("java")
    id("io.github.goooler.shadow") version "8.1.7"
}

group = "com.huidu.villagersdelight"
version = "0.1.0"

repositories {
    mavenCentral()
}

// Versioned NMS jars are merged into one self-contained plugin jar; core classes are duplicated
// across the NMS jars and are skipped, keeping the first copy.
dependencies {
    implementation(files("../core/build/libs/villagersdelight-core-0.1.0.jar"))
    implementation(files("../nms-26/build/libs/villagersdelight-0.1.0-26.jar"))
    implementation(files("../nms-1211/build/libs/villagersdelight-0.1.0-1.21.11.jar"))
    implementation(files("../nms-1.21.4/build/libs/villagersdelight-0.1.0-1.21.4.jar"))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.jar {
    enabled = false
}

tasks.shadowJar {
    archiveFileName.set("villagersdelight-0.1.0.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    mergeServiceFiles()
}

tasks.build {
    dependsOn(tasks.shadowJar)
}