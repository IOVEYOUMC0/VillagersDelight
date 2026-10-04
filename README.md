<h1 align="center">VillagersDelight</h1>

<p align="center">
  <img src="https://img.shields.io/badge/Minecraft-1.21.5%20%E2%80%93%2026.x-3fb950" alt="Minecraft 1.21.5 – 26.x">
  <img src="https://img.shields.io/badge/Java-21%20%2F%2025-orange" alt="Java 21 / 25">
  <img src="https://img.shields.io/badge/Paper%20%2F%20Purpur-supported-blueviolet" alt="Paper / Purpur">
  <img src="https://img.shields.io/badge/CraftEngine-26.9.1-5865F2" alt="CraftEngine 26.9.1">
</p>

<p align="center"><i>Makes farmer villagers recognise and farm CraftEngine-based custom crops on Paper/Purpur servers.</i></p>

---

## About

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
- `nms-1.21.5/` — paperweight build for MC 1.21.5, the lower bound of the 1.21.5–1.21.10 layer; produces the final plugin jar (bundles core).
- `nms-1211/` — paperweight build for MC 1.21.11, produces the final plugin jar (bundles core).
- `nms-26/` — paperweight build for MC 26.x (26.1.2), produces the final plugin jar.
- `launcher/` — merges core and all versioned implementations into one universal plugin jar.

The shared NMS behaviour sources (`nms-common/`) are compiled into a layer-specific package
(`impl215.common`, `impl1211.common`, `impl26.common`). One merged jar carries all three layers, so a
single shared copy of those classes could only ever match one dev bundle: 26.x widened
`BlockState.is`/`ItemStack.is` to `Object`, which does not exist on 1.21.5/1.21.11.

Install either the universal launcher jar or exactly one versioned jar matching the server version.

## Compatibility verification

Layer coverage, and how far each row has actually been verified. The `nms-1211` and `nms-26` rows
record the 2026-09-17 audit; the `nms-1.21.5` row is the current 1.21.5 rebase, whose build and live
smoke test are still pending:

| Minecraft | Layer | Evidence | Limit |
| --- | --- | --- | --- |
| 1.21.5–1.21.10 | `nms-1.21.5` (compile anchor 1.21.5) | This layer is the implementation for the whole range. Its sources were checked against the 1.21.5 mappings: `VillagerData` is a record whose `profession()` returns `Holder<VillagerProfession>`, and `Villager`/`VillagerProfession` still live in `npc`. 1.21.6–1.21.10 select the same layer by version prefix; they have no per-version dev bundle, and 1.21.9/1.21.10 are refused at startup by the `npc.Villager` probe because the class moved to `npc.villager.Villager` | Not smoke-tested above 1.21.5, and the 1.21.5 build itself has not been run yet |
| 1.21.11 | `nms-1211` | Builds against the matching Paper dev bundle; the universal jar carries its own `impl1211` copy of the shared behaviour classes (checked by comparing the compiled `BlockState.is` descriptor in each layer) | Full behavior regression not run in this audit |
| 26.1.2 | `nms-26` | Builds against the matching Paper dev bundle | Full behavior regression not run in this audit |
| 26.2 | `nms-26` | Existing Folia 26.2 startup log confirms `impl26` installation | Startup alone does not verify harvesting, sharing, consumption or inventory editing |
| 26.3 | `nms-26` | Builds against dev bundle `26.3.build.41-alpha`, where `asCraftMirror` no longer exists. The `impl26` copy no longer reaches the mirror through CraftEngine's proxy: `VillagerItems.bukkitStack` resolves `CraftItemStack.asBukkitMirror` and falls back to the removed `asCraftMirror`, so a server that has neither fails with an `IllegalStateException` naming the version. The live 26.3 failure was CraftEngine 26.9.1 loading but its proxy binding the removed `asCraftMirror`, which threw `NoSuchMethodError` from `VillagerWantedItemSensor.isCollectable` | Six symbols changed on 26.3 — `CraftItemStack.asCraftMirror(ItemStack)`, `Villager.FOOD_POINTS`, `BonemealableBlock.isValidBonemealTarget` (now takes a `BonemealSource`), the `BlockPos(MutableBlockPos)` constructor, `ItemStack.getBukkitStack()` and `ComposterBlock.COMPOSTABLES`. The mirror is resolved at runtime here; no 26.3 server was started for this change, so harvesting, sharing, consumption and inventory editing are not re-observed on 26.3 |

Version selection and `api-version` are not binary compatibility guarantees, but they are not the same
thing as being unsupported either. The layers are `nms-1.21.5` for 1.21.5–1.21.10 (lower bound
1.21.5), `nms-1211` for 1.21.11+, and `nms-26` for 26.x. What the 1.21.6–1.21.10 part of the range
still lacks is a per-version build and a live run, not an implementation.

## Build

Each module is an independent Gradle project (paperweight versions are bound to the MC version,
so they cannot share one build). Run `./gradlew shadowJar` inside the module you need.

- `nms-1.21.5` — Java 21 toolchain, Gradle 9.0, dev bundle `1.21.5-R0.1-SNAPSHOT`.
- `nms-1211` — Java 21 toolchain, Gradle 9.1, dev bundle `1.21.11-R0.1-SNAPSHOT`.
- `nms-26` — Java 25 toolchain (auto-downloaded via foojay), Gradle 9.1, dev bundle
  `26.1.2.build.74-stable`. MC 26.1+ requires Java 25, so this module compiles with release 25.
  The bundle is overridable for compatibility checks against another release without editing the build:
  `./gradlew build -PdevBundle=26.3.build.41-alpha`.

CraftEngine is compiled against the official Maven 26.9.1 artifacts and
`../FarmersDelight/libs`; earlier CraftEngine artifacts have incompatible event APIs. The server-side
CraftEngine releases verified for this build are 26.8.2, 26.9 and 26.9.1 (every CraftEngine class and member
the jar references resolves in all three); pass `-PceVersion=<version>` to compile against another release.

Versioned output: `nms-*/build/libs/villagersdelight-0.1.1-<mc>.jar`.

Universal output: build `core` and all three NMS modules first, then run `launcher/gradlew shadowJar`.
The merged jar is `launcher/build/libs/villagersdelight-0.1.1.jar`.
Prebuilt universal jars are available from [GitHub Releases](https://github.com/IOVEYOUMC0/VillagersDelight/releases).

## Credits

VillagersDelight is **original to this project** — it is not a port of any mod, and it has no upstream
project or upstream authors to credit.

Its own code is licensed under the **GNU Affero General Public License v3.0 only** (AGPL-3.0), whose full
text is the root `LICENSE`. It ports no upstream content, so there is no third-party attribution to carry
alongside it.

It is the NMS-side companion to the FarmersDelight plugin, not a CraftEngine content pack: each
Minecraft version gets its own jar built against that version's Paper dev bundle (paperweight), and the
`launcher` module merges those layers into one universal jar. See [Modules](#modules) for the layer
layout and [Build](#build) for the toolchains.
