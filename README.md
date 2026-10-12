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

The current [live-backpack and vanilla-AI verification](docs/verification-villager-ai-2026-10-12.md)
records exact build and runtime scope. The universal launcher's version selection is not a binary
compatibility guarantee.

| Minecraft | Layer | Current evidence | Limit |
| --- | --- | --- | --- |
| 1.21.5 | `nms-1.21.5` | Matching Paper build passed | No live run in this check |
| 1.21.6–1.21.8 | `nms-1.21.5` selected | Uses the 1.21.5 compile anchor | No per-patch build or live verification |
| 1.21.9 / 1.21.10 | `nms-1.21.5` selected | Startup probe rejects the moved Villager class | Currently unsupported |
| 1.21.11 | `nms-1211` | Matching Paper build passed, layer-specific shared classes bundled | No live run in this check |
| 26.1.2 | `nms-26` | Matching Paper build and three native inventory/behavior tests passed | No new live run on this patch |
| 26.2 | `nms-26` | Purpur 26.2-2622 passed 17 native menu, harvest/replant and pickup assertions; prior Folia startup passed | Client interaction, natural full-day schedules and live Folia cross-region tests pending |
| 26.3 | `nms-26` | Earlier compile check passed; compatibility helpers handle known mirror/component changes | Not rebuilt against 26.3 or run on it in this check; compatible CE required |

The backpack's first eight slots are the villager's live inventory; closing does not write a snapshot
back. Slot nine is locked, including Shift merges. Editing requires player and villager ownership on
the current region. Minecraft manages the native menu and cursor without an additional sync timer.

Vanilla planting sounds, profession guards, pickup checks/events, secondary POIs and the compost
reserve are preserved. `villager-ai.compost.minimum-kept-per-item` defaults to **10**; existing
explicit values remain unchanged. The separate custom food seed reserve defaults to 32.

Only exact native behavior classes are replaced; unrelated activities and third-party subclasses
remain in place. Removal restores native behavior classes and Gate weights, with fresh internal
state. Use a complete stop/restart for upgrades: Folia runtime hot-disable cannot guarantee removal
through a disabled plugin scheduler. Third-party AI combinations still require their own checks.

## Build

Each module is an independent Gradle project (paperweight versions are bound to the MC version,
so they cannot share one build). Run `./gradlew shadowJar` inside the module you need.

- `nms-1.21.5` — Java 21 toolchain, Gradle 9.0, dev bundle `1.21.5-R0.1-SNAPSHOT`.
- `nms-1211` — Java 21 toolchain, Gradle 9.1, dev bundle `1.21.11-R0.1-SNAPSHOT`.
- `nms-26` — Java 25 toolchain (auto-downloaded via foojay), Gradle 9.1, dev bundle
  `26.1.2.build.74-stable`. MC 26.1+ requires Java 25, so this module compiles with release 25.
  The bundle is overridable for compatibility checks against another release without editing the build:
  `./gradlew build -PdevBundle=26.3.build.41-alpha`.

CraftEngine is compiled against the official Maven 26.9.1 artifacts. Current CE references resolve
in 26.8.2, 26.9, 26.9.1, 26.9.2 and all three local 26.10 snapshots; this does not verify Minecraft NMS
compatibility. Pass `-PceVersion=<version>` to change the compile dependency.

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
