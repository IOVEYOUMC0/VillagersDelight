import java.util.zip.ZipFile

plugins {
    id("java")
    id("io.github.goooler.shadow") version "8.1.7"
}

group = "com.huidu.villagersdelight"
version = "0.1.0"

repositories {
    mavenCentral()
}

// Versioned NMS jars are merged into one self-contained plugin jar. Every NMS jar also carries its own
// copy of the core classes (each module compiles ../core/src into itself so the versioned jars stay
// standalone), so the merge keeps the FIRST copy and the core jar must be listed first: nms-26 compiles
// core at release 25 for MC 26.x, and letting that copy win would make the plugin's own main class
// unloadable on the Java 21 servers the other two NMS layers target.
val coreJar = file("../core/build/libs/villagersdelight-core-0.1.0.jar")
val nms26Jar = file("../nms-26/build/libs/villagersdelight-0.1.0-26.jar")
val nms1211Jar = file("../nms-1211/build/libs/villagersdelight-0.1.0-1.21.11.jar")
val nms1214Jar = file("../nms-1.21.4/build/libs/villagersdelight-0.1.0-1.21.4.jar")

dependencies {
    implementation(files(coreJar))
    implementation(files(nms26Jar))
    implementation(files(nms1211Jar))
    implementation(files(nms1214Jar))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.jar {
    enabled = false
}

// Java 21 class file major version. The merged jar's main class must stay loadable on the oldest
// supported server, so this is asserted rather than assumed.
val javaTwentyOneMajor = 65
val mainClassEntry = "com/huidu/villagersdelight/core/VillagersDelightPlugin.class"

tasks.shadowJar {
    archiveFileName.set("villagersdelight-0.1.0.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    mergeServiceFiles()

    doFirst {
        // Require every input JAR so duplicate filtering cannot select newer-Java core classes
        // from an NMS module when the Java 21 core artifact is missing.
        listOf(coreJar, nms26Jar, nms1211Jar, nms1214Jar).forEach { jar ->
            check(jar.isFile) {
                "Missing input jar ${jar.path}. Build core and all three NMS modules before the launcher."
            }
        }
    }

    doLast {
        val merged = archiveFile.get().asFile
        ZipFile(merged).use { zip ->
            val entry = zip.getEntry(mainClassEntry)
                ?: error("Merged jar has no $mainClassEntry")
            val header = zip.getInputStream(entry).use { it.readNBytes(8) }
            val major = ((header[6].toInt() and 0xFF) shl 8) or (header[7].toInt() and 0xFF)
            check(major == javaTwentyOneMajor) {
                "Merged jar's main class is class file version $major, expected $javaTwentyOneMajor " +
                    "(Java 21). The core classes were taken from an NMS jar built for a newer release."
            }
        }
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
