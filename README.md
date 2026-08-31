# VillagersDelight

Makes farmer villagers recognise and farm CraftEngine-based custom crops (FarmersDelight crops,
rich soil farmland, etc.) on Paper/Purpur servers, while preserving every vanilla / Purpur / third-party
villager AI behaviour.

## Modules

- `core/` — version-agnostic: crop index over CraftEngine, CE block access, config, plugin lifecycle.
- `nms-1.21.4/` — paperweight build for MC 1.21.4, produces the final plugin jar (bundles core).
- `nms-1211/` — paperweight build for MC 1.21.11, produces the final plugin jar (bundles core).
- `nms-26/` — paperweight build for MC 26.x (26.1.2), produces the final plugin jar.
- `launcher/` — merges core and all versioned implementations into one universal plugin jar.

Install either the universal launcher jar or exactly one versioned jar matching the server version.

## Build

Each module is an independent Gradle project (paperweight versions are bound to the MC version,
so they cannot share one build). Run `./gradlew shadowJar` inside the module you need.

- `nms-1.21.4` — Java 21 toolchain, Gradle 9.0, dev bundle `1.21.4-R0.1-SNAPSHOT`.
- `nms-1211` — Java 21 toolchain, Gradle 9.1, dev bundle `1.21.11-R0.1-SNAPSHOT`.
- `nms-26` — Java 25 toolchain (auto-downloaded via foojay), Gradle 9.1, dev bundle
  `26.1.2.build.74-stable`. MC 26.1+ requires Java 25, so this module compiles with release 25.

CraftEngine is pinned to the vendored 26.8 jars shared from
`../FarmersDelight/libs`; do not build or deploy this plugin against CE 26.7.x.

Versioned output: `nms-*/build/libs/villagersdelight-0.1.0-<mc>.jar`.

Universal output: build `core` and all three NMS modules first, then run `launcher/gradlew shadowJar`.
The merged jar is `launcher/build/libs/villagersdelight-0.1.0.jar`.
Prebuilt universal jars are available from [GitHub Releases](https://github.com/IOVEYOUMC0/VillagersDelight/releases).
