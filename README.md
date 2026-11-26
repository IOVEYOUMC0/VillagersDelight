<h1 align="center">VillagersDelight</h1>

<p align="center">
  <img src="https://img.shields.io/badge/Minecraft-see%20compatibility%20table-3fb950" alt="Minecraft: see compatibility table">
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
- `nms-1.21.5/` — paperweight build for MC 1.21.5, the lower bound of the 1.21.5–1.21.8 layer; produces the final plugin jar (bundles core).
- `nms-1211/` — paperweight build for MC 1.21.11, produces the final plugin jar (bundles core).
- `nms-26/` — paperweight build for MC 26.1.2/26.2, produces the final plugin jar.
- `nms-263/` — paperweight build for MC 26.3 stable, produces the final plugin jar.
- `launcher/` — merges core and all versioned implementations into one universal plugin jar.

The shared NMS behaviour sources (`nms-common/`) are compiled into a layer-specific package
(`impl215.common`, `impl1211.common`, `impl26.common`, `impl263.common`). One merged jar carries all
four layers, so a single shared copy of those classes could only ever match one dev bundle: 26.x
widened `BlockState.is`/`ItemStack.is` to `Object`, which does not exist on 1.21.5/1.21.11.

Install either the universal launcher jar or exactly one versioned jar matching the server version.

## Public API for other plugins

`core` also produces an api-only artifact:

```text
core/build/libs/villagersdelight-core-0.1.1-api.jar
```

Other Paper plugins can compile against it (`compileOnly`) and read the configured crops without
depending on CraftEngine or VD internals:

```java
import com.huidu.villagersdelight.api.CropInfo;
import com.huidu.villagersdelight.api.VillagersDelightApi;

VillagersDelightApi api = VillagersDelightApi.get();
if (api.isAvailable()) {
    for (CropInfo crop : api.crops()) {
        // plain ids: crop.blockId(), crop.seedItem(), crop.soils(),
        // crop.harvestMode(), crop.water(), crop.plantBlock()
    }
}
```

- `apiVersion()` is currently `1`.
- All ids are plain `namespace:path` strings; no CraftEngine type appears in the API.
- `crops()`, `crop(String)`, `cropBySeed(String)`, `extraSoils()` and `plantableSoils()` are
  read-only snapshots refreshed by a VillagersDelight reload.
- An absent/not-yet-ready plugin returns an empty list/null/false instead of throwing.
- `HarvestMode` is `BREAK`, `PICK` or `TALL`.
- Live block queries: `cropAt(Block)`, `isMature(Block)`, `age(Block)` (`-1` when not a crop),
  `isUpperHalf(Block)`, `isLowerHalf(Block)` and `isTallCrop(Block)`. They check region ownership and
  fail closed on the wrong Folia thread.
- `harvest(Block)` handles both TALL halves, resets the lower stalk, replants PICK crops, spawns the
  returned drops at the harvested block, and returns a `HarvestResult` whose `drops()` are
  caller-owned copies. `harvestPreview(Block)` / `harvest(Block, false)` return copies without
  spawning. CE event-function drops are captured as well as the block loot table.
- `canPlant(Block, ItemStack)` / `canPlant(Block, String)` validate the current soil/water requirement
  without placing or consuming anything.
- `plant(Block, ItemStack)` validates soil/water, places the configured plant block at age 0, and
  consumes one item from the passed stack on success. `plant(Block, String seedItemId)` does the same
  but consumes nothing.
- For a long-lived reference use `handle("namespace:crop")`; a `CropHandle` stores only the id and
  resolves the current definition after a reload. A `CropInfo` is an immutable snapshot and stays
  readable but does not update itself.

The universal launcher and each versioned `nms-*` jar still bundle the API classes; only the separate
api jar is needed when another plugin wants a small compile-only dependency.

## Compatibility verification

The [code-path verification](docs/verification-code-paths-2026-10-12.md) also covers crop-rule
reloads with unchanged inventory and one bonemeal attempt per work cycle.

The version selector maps 1.21.5–1.21.8 to `impl215`, 1.21.9–1.21.11 to `impl1211`,
26.1.2–26.2 to `impl26`, and 26.3 to `impl263` (built against the 26.3 stable dev bundle).
Legacy `pickup.use-datapack-tag` mode writes the pickup pack for the next full restart; it never
reloads server data automatically. Metadata uses official release formats (1.21.5: 71, 1.21.6: 80,
1.21.7/8: 81). Custom-ID pickup is still available through `pickup.use-datapack-tag: false`; the
default remains tag mode.

The current [live-backpack and vanilla-AI verification](docs/verification-villager-ai-2026-10-12.md)
records exact build and runtime scope. The universal launcher's version selection is not a binary
compatibility guarantee.

| Minecraft | Layer | Current evidence | Limit |
| --- | --- | --- | --- |
| 1.21.5 | `nms-1.21.5` | Matching Paper build passed | No live run in this check |
| 1.21.6–1.21.8 | `nms-1.21.5` selected | Uses the 1.21.5 compile anchor | No per-patch build or live verification |
| 1.21.9 / 1.21.10 | `nms-1211` selected | Uses the 1.21.11 layer; the Villager package matches | No per-patch live run |
| 1.21.11 | `nms-1211` | Matching Paper build passed, layer-specific shared classes bundled | No live run in this check |
| 26.1.2 | `nms-26` | Matching Paper build and three native inventory/behavior tests passed | No new live run on this patch |
| 26.2 | `nms-26` | Purpur 26.2-2622 passed 17 native menu/farming/pickup and 28 separate compost assertions; prior Folia startup passed | Client interaction, natural full-day schedules and live Folia cross-region tests pending |
| 26.3 | `nms-263` | Matching 26.3 stable dev bundle build and native inventory/behavior tests passed | No live server run in this check; compatible CE required |

The backpack's first eight slots are the villager's live inventory; closing does not write a snapshot
back. Slot nine is locked, including Shift merges. Editing requires player and villager ownership on
the current region. Minecraft manages the native menu and cursor without an additional sync timer.

Vanilla planting sounds, profession guards, pickup checks/events, secondary POIs and the compost
reserve are preserved. `villager-ai.compost.minimum-kept-per-item` defaults to **10**; existing
explicit values remain unchanged. The separate custom food seed reserve defaults to 32.

Configured plantable CE items are eligible for surplus composting; seed reserves apply across
inventory slots. New defaults include cabbage seeds, tomato seeds and rice. Existing lists are not
overwritten: add these IDs to `compost-items` if desired. Native farmers only compost surplus wheat
and beetroot seeds; a full bag of carrots or potatoes does not force composting. A reachable, claimed
composter and native WORK conditions are still required. The [full-inventory verification](docs/verification-compost-2026-10-12.md)
records 28 fixed-package server assertions, including repeated scheduled composting.

Only exact native behavior classes are replaced; unrelated activities and third-party subclasses
remain in place. Removal restores native behavior classes and Gate weights, with fresh internal
state. Use a complete stop/restart for upgrades: Folia runtime hot-disable cannot guarantee removal
through a disabled plugin scheduler. Third-party AI combinations still require their own checks.

## Build

Each module is an independent Gradle project (paperweight versions are bound to the MC version,
so they cannot share one build). Run `./gradlew shadowJar` inside the module you need.

- `nms-1.21.5` — Java 21 toolchain, Gradle 9.0, dev bundle `1.21.5-R0.1-SNAPSHOT`.
- `nms-1211` — Java 21 toolchain, Gradle 9.1, dev bundle `1.21.11-R0.1-SNAPSHOT`.
- `nms-26` — Java 25 toolchain (auto-downloaded via foojay), Gradle 9.1, default dev bundle
  `26.1.2.build.74-stable`. MC 26.1+ requires Java 25, so this module compiles with release 25.
  The bundle is overridable for compatibility checks against another release without editing the build:
  `./gradlew build -PdevBundle=26.3.build.175-stable`.
- `nms-263` — Java 25 toolchain, Gradle 9.1, dev bundle `26.3.build.175-stable`.

CraftEngine is compiled against the official Maven 26.9.1 artifacts. Current CE references resolve
in 26.8.2, 26.9, 26.9.1, 26.9.2 and all three local 26.10 snapshots; this does not verify Minecraft NMS
compatibility. Pass `-PceVersion=<version>` to change the compile dependency.

Versioned output: `nms-*/build/libs/villagersdelight-0.1.1-<mc>.jar`.

Universal output: build `core` and all four NMS modules first, then run `launcher/gradlew shadowJar`.
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
