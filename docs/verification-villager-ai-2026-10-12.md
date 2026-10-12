# Live backpack and vanilla AI verification

Verified on 2026-10-12. The backpack uses the villager's real eight slots through a native chest menu; closing releases the session without writing an old copy back. Slot nine rejects pickup, placement and Shift merging. Access requires both entities to belong to the current region.

Vanilla checks used each layer's mapped Paper sources: HarvestFarmland, WorkAtComposter, UseBonemeal, TradeWithVillager, InventoryCarrier, VillagerGoalPackages and SecondaryPoiSensor. Fixes preserve native planting sounds, profession guards, pickup permissions/events, profession secondary POIs and the default compost reserve of ten. Removing replacement behaviors restores the native class and Gate weight; recorded sensors are restored only into the original Brain. Vanilla food consumption and unrelated activities remain native.

## Commands and results

Run sequentially, from each module:

```text
core/gradlew clean build --console=plain
nms-1.21.5/gradlew clean build --console=plain
nms-1211/gradlew clean build --console=plain
nms-26/gradlew clean build --console=plain
launcher/gradlew clean build --console=plain
```

- All five builds passed. Core's FoodRules, CraftMirror and BehaviorWithdraw assertion gates passed. The existing core build and withdrawal check were preserved byte-for-byte.
- The 26 layer ran three JUnit tests in two suites: zero failures, errors or skipped tests. They exercise real SimpleContainer edits, ownership loss and native behavior restoration inside/outside Gates. Other layers and launcher have no test sources.
- The universal jar contains all three layer-specific shared packages; launcher classes target Java 21 and the 26 layer targets Java 25.
- Workspace checks passed 10/10. Fourteen artifacts resolved their CE references in 26.8.2, 26.9, 26.9.1, 26.9.2 and all three local 26.10 snapshots. This is not an NMS compatibility check.
- Purpur 26.2-2622 with CE snapshot `beef44fd` passed 17 runtime assertions: live native menu edits/close/Shift, an actual mature wheat harvest, eight planting events, native potato pickup and non-farmer CE tomato pickup with event remaining zero.

The runtime menu used an unconnected ServerPlayer fixture. The isolated farming fixture supplied JOB_SITE/WORK and remained active without online players. These are server integration checks, not client protocol or natural full-day village tests. Production does not override Paper's activation range. The first final-age harvest assertion was unsuitable for random work and regrowth; the final probe observes successful harvest events instead.

## Limits

Minecraft 1.21.5, 1.21.11 and 26.1.2 compile anchors passed; only 26.2 was run here. Versions 1.21.9/1.21.10 are rejected by the current startup probe. This change adds no claim for untested patch versions or 26.3 runtime.

Folia cross-region interactions, client dragging/hotkeys/creative clicks, natural schedules/breeding and third-party AI combinations need live tests. Use a complete restart for upgrades; Folia runtime hot-disable cannot guarantee behavior withdrawal through a disabled plugin scheduler. Restored native behaviors start with fresh internal state.

Existing operator values are preserved. Set `villager-ai.compost.minimum-kept-per-item: 10` to adopt the vanilla reserve; the separate custom food seed reserve remains 32 by default.
