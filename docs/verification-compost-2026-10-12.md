# Full-inventory compost verification

Verified on 2026-10-12. Core no longer removes plantable items from the operator's compost list. The NMS loop already protects a shared per-item reserve; seeds must not be excluded before reaching that loop. New defaults include cabbage seeds, tomato seeds and rice. Existing operator lists and explicit reserve values are preserved.

## Source and build checks

Compared WorkAtPoi, WorkAtComposter, ComposterBlock and VillagerGoalPackages from each module's mapped Paper server source. Native farmer composting uses wheat/beetroot seeds, keeps ten per type and attempts at most twenty per work. CE items are an extension, not an upstream FD farmer behavior. A full inventory of carrots does not trigger native composting.

Run sequentially inside each module:

```text
core/gradlew clean build --console=plain
nms-1.21.5/gradlew clean build --console=plain
nms-1211/gradlew clean build --console=plain
nms-26/gradlew clean build --console=plain
launcher/gradlew clean build --console=plain
```

All five builds passed. Core's three assertion gates passed, including checks for the seed compost defaults and preservation of an explicit reserve of 32. The 26 layer's three JUnit tests passed with zero failures, errors or skips; the other layers and launcher have no test sources. Workspace validation passed 10/10. Fourteen artifacts passed seven CE symbol groups from 26.8.2 to all three local 26.10 snapshots. Those symbol checks do not validate NMS versions.

## Runtime scope

Purpur 26.2-2622 and CE snapshot `beef44fd` reproduced the old defect with the deployed configuration: eight stacks of onion, 512 items total, zero compost attempts after workstation invocation. Onion was both configured for composting and registered as a seed.

The fixed probe uses the production config path and actual CE items. Deterministic event outcomes cover onion, cabbage seeds, tomato seeds and rice, shared ten-item reserves, component preservation, vanilla wheat/beetroot seeds, the twenty-attempt budget, unlisted identities, cancelled item/block events, failed rolls, missing JOB_SITE and explicit reserve 32. Native scheduled maturation reaches level eight; extraction drops bone meal before accepting more items.

The final WORK-scheduler fixture keeps a full CE inventory, supplies a reachable JOB_SITE and records actual compost events over repeated work cycles. Only the offline fixture bypasses Paper's activation range. It does not call useWorkstation during that scheduled phase. Final counts and server cleanup are recorded below.

## Limits

Direct workstation checks isolate transaction rules; they are not natural schedule tests. The scheduled fixture provides memories and activation, so user village pathing, full-day schedules, online client interaction, third-party AI and Folia cross-region behavior still need live checks. Versions 1.21.9/1.21.10 remain unsupported by the current startup probe. No runtime check was added for 1.21.5, 1.21.11 or 26.3.

No production per-tick scan, scheduler or new reflection was added. Upgrades require a complete restart. Existing server configuration is backed up and retained; new list entries are not silently added to operator lists.

Final runtime result: 28 fixed-package assertions passed. The scheduled phase observed 24 compost events in 400 ticks and held 476 items, down from 512; food consumption may also reduce that inventory total. Since one work invocation attempts at most twenty items, the events demonstrate repeated native work. The baseline separately passed four reproduction assertions. No probe error occurred; the server retained its existing CustomFishing initialization error.

The tested universal jar is installed in the original test server, with its SHA-256 recorded in the local deployment manifest. The existing four-item compost list and explicit reserve 32 are byte-for-byte unchanged. Temporary probe jars and both probe worlds are archived outside the load paths; the server was stopped normally after confirming zero online players. Wiki, local configuration skill and current documentation were synchronized.
