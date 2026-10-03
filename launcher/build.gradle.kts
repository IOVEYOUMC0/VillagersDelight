import java.util.zip.ZipFile

plugins {
    id("java")
    id("io.github.goooler.shadow") version "8.1.7"
}

group = "com.huidu.villagersdelight"
version = "0.1.1"

repositories {
    mavenCentral()
}

// Versioned NMS jars are merged into one self-contained plugin jar. Every NMS jar also carries its own
// copy of the core classes (each module compiles ../core/src into itself so the versioned jars stay
// standalone), so the merge keeps the FIRST copy and the core jar must be listed first: nms-26 compiles
// core at release 25 for MC 26.x, and letting that copy win would make the plugin's own main class
// unloadable on the Java 21 servers the other two NMS layers target. The shared NMS behaviour classes
// (../nms-common) are relocated into each layer's own package by the modules themselves, so the merge
// cannot collapse them to a single dev-bundle-specific copy.
val coreJar = file("../core/build/libs/villagersdelight-core-0.1.1.jar")
val nms26Jar = file("../nms-26/build/libs/villagersdelight-0.1.1-26.jar")
val nms1211Jar = file("../nms-1211/build/libs/villagersdelight-0.1.1-1.21.11.jar")
val nms1215Jar = file("../nms-1.21.5/build/libs/villagersdelight-0.1.1-1.21.5.jar")

dependencies {
    implementation(files(coreJar))
    implementation(files(nms26Jar))
    implementation(files(nms1211Jar))
    implementation(files(nms1215Jar))
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
    archiveFileName.set("villagersdelight-0.1.1.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    mergeServiceFiles()

    doFirst {
        // Require every input JAR so duplicate filtering cannot select newer-Java core classes
        // from an NMS module when the Java 21 core artifact is missing.
        listOf(coreJar, nms26Jar, nms1211Jar, nms1215Jar).forEach { jar ->
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
            // Every layer must have survived the merge with its own behaviour classes. A single shared
            // copy is compiled against one dev bundle only, and the method descriptors it references
            // (BlockState.is changed between 26.x and the 1.21.5-1.21.11 line) do not exist on the
            // other servers, which surfaces as NoSuchMethodError per villager at runtime.
            listOf("impl26", "impl1211", "impl215").forEach { layer ->
                check(zip.getEntry("com/huidu/villagersdelight/$layer/common/VillagerWorkAtComposter.class")
                    != null
                ) {
                    "Merged jar has no $layer copy of the shared NMS behaviour classes; the merge " +
                        "collapsed them and that layer would run another version's bytecode."
                }
            }
        }
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
