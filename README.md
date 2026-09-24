# VillagersDelight

Makes farmer villagers recognise and farm CraftEngine-based custom crops (FarmersDelight crops,
rich soil farmland, etc.) on Paper/Purpur servers. Extends vanilla farming and social food sharing;
unrelated AI and third-party behavior subclasses are left in place.

The [FD villager comparison](FD-villager-comparison.md) records the mod behavior, fixes, intentional
extensions and the limits of offline validation. Food defaults match FD: cabbage, tomato and onion
give one breeding point, rice gives two, and rice panicles are pickup-only. Configure these under
`villager-ai.food.points`. Farmers retain `minimum-kept-seeds` custom seeds (32 by default) and may
eat the surplus; other professions may eat the same foods without a planting reserve. Vanilla
bread, carrots, potatoes and beetroot still use Minecraft's own consumption logic.

## Modules

- `core/` — version-agnostic: crop index over CraftEngine, CE block access, config, plugin lifecycle.
- `nms-1.21.4/` — paperweight build for MC 1.21.4, produces the final plugin jar (bundles core).
- `nms-1211/` — paperweight build for MC 1.21.11, produces the final plugin jar (bundles core).
- `nms-26/` — paperweight build for MC 26.x (26.1.2), produces the final plugin jar.
- `launcher/` — merges core and all versioned implementations into one universal plugin jar.

Install either the universal launcher jar or exactly one versioned jar matching the server version.

## Compatibility verification

Checked on 2026-09-17 against the current universal jar:

| Minecraft | Evidence | Limit |
| --- | --- | --- |
| 1.21.4 | Builds against the matching Paper dev bundle | Full behavior regression not run in this audit |
| 1.21.5–1.21.10 | Unsupported | No NMS layer is selected; the 1.21.4 implementation is not reused because its `VillagerData.getProfession()` call is absent on these versions |
| 1.21.11 | Builds against the matching Paper dev bundle | Full behavior regression not run in this audit |
| 26.1.2 | Builds against the matching Paper dev bundle | Full behavior regression not run in this audit |
| 26.2 | Existing Folia 26.2 startup log confirms `impl26` installation | Startup alone does not verify harvesting, sharing, consumption or inventory editing |

Version selection and `api-version` are not binary compatibility guarantees. The 1.21.5–1.21.10
range needs NMS adaptation and validation before it can be advertised as supported.

## Build

Each module is an independent Gradle project (paperweight versions are bound to the MC version,
so they cannot share one build). Run `./gradlew shadowJar` inside the module you need.

- `nms-1.21.4` — Java 21 toolchain, Gradle 9.0, dev bundle `1.21.4-R0.1-SNAPSHOT`.
- `nms-1211` — Java 21 toolchain, Gradle 9.1, dev bundle `1.21.11-R0.1-SNAPSHOT`.
- `nms-26` — Java 25 toolchain (auto-downloaded via foojay), Gradle 9.1, dev bundle
  `26.1.2.build.74-stable`. MC 26.1+ requires Java 25, so this module compiles with release 25.

CraftEngine is compiled against the official Maven 26.9.1 artifacts and
`../FarmersDelight/libs`; earlier CraftEngine artifacts have incompatible event APIs.

Versioned output: `nms-*/build/libs/villagersdelight-0.1.0-<mc>.jar`.

Universal output: build `core` and all three NMS modules first, then run `launcher/gradlew shadowJar`.
The merged jar is `launcher/build/libs/villagersdelight-0.1.0.jar`.
Prebuilt universal jars are available from [GitHub Releases](https://github.com/IOVEYOUMC0/VillagersDelight/releases).
