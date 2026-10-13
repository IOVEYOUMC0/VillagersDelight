# Server version selection and bread overflow

Verified on 2026-10-14. The pickup tag mode no longer calls reloadData and keeps its existing default true. Changed files require a full restart; optional custom-ID pickup remains available. Official data pack formats correct 1.21.7/8 to 81. Version matching is exact for listed 1.21 patches and retains the 26.x candidate prefix. 1.21.9/10 are excluded because their villager class package has no bundled matching layer.

The official 26.2 development bundle reproduced three compile errors for the removed Villager.forceDrops field. Bread overflow now creates an ItemEntity with the native default pickup delay and uses the common spawnAtLocation(level, entity) overload. This preserves cancellable EntityDropItemEvent behavior without writing that removed field.

- Official 26.2 target clean build: three JUnit tests passed; the check artifact is archived separately.
- All five normal modules then passed serial clean builds; default nms-26 build (26.1.2 anchor) restored, three JUnit tests passed with zero failures/errors/skips.
- Core check gates passed, including 24 version/format assertions plus existing food, mirrored-inventory and withdrawal checks.
- Paper 26.2-121: real native villager/inventory bread overflow was invoked directly for uncancelled and cancelled drops; 19 registry/layer/bread assertions passed, including wheat consumption, one event, three bread, native pickup delay and cancellation.
- Folia 26.2-7: the normal universal jar selected and installed impl26 and enabled successfully. This does not exercise natural WORK schedules or Folia bread execution.
- Universal jar passed all seven CE symbol groups. Original server configuration hash remained unchanged; the final jar is installed and the existing server stopped.

No NMS layer was added for 1.21.9/10. 1.21.6–8 remain candidates for the 1.21.5 anchor and were not individually run. A matching version prefix is not a future binary-compatibility guarantee. Evidence is under the workspace .codex/tmp/datapack-startup-20261014.
