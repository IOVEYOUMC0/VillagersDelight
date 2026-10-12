# Villager code-path verification

The three farming adapters now invalidate decoded seed slots when the CropRegistry snapshot changes. The shared bonemeal behavior tries only the first bonemeal stack, including unsuccessful attempts. No inventory write-back, new scheduler, dependency or disk configuration migration was added.

Validation:

- Sequential `clean build`: core, nms-1.21.5, nms-1211, nms-26, launcher.
- Core food rules, mirror resolution and behavior withdrawal assertion gates passed. nms-26: 2 JUnit suites, 3 tests, 0 failures/errors/skipped. The other modules have no JUnit cases.
- Purpur 26.2-2622, CraftEngine snapshot `beef44fd`: 17 fixed-package path assertions and 28 existing compost regression assertions passed. Native WORK produced 22 compost events over 400 ticks. The baseline package separately reproduced both defects with 14 assertions.
- The path probe uses real CE items, a real villager inventory and the generated CE crop block. It temporarily disables/re-enables the crop registry without changing inventory, then substitutes a counting CE bonemeal behavior returning false. All configuration/behavior substitutions are restored in `finally`; the invalid target was checked 8 times before the fix and once after it.
- The valid vanilla-crop check consumed exactly one item from the first stack, left later stacks intact and increased crop age.
- FD workspace checks: 10/10. CE symbols: 14 artifacts against 26.8.2, 26.9, 26.9.1, 26.9.2 and all three archived 26.10 snapshots; 0 missing classes/members/abstract implementations.

The tested universal jar replaced the original stopped test server's VD jar. The server was stopped normally after verifying no players were online. Runtime probes and their isolated dimensions were removed from loading paths; the operator's configuration is unchanged.

Evidence outside this repository: `../.codex/tmp/vd-code-path-review-20261012/`. Server rollback archive: `Desktop/test/codex-backups/vd-paths-20261012/`. The existing CustomFishing initialization failure was present before these tests and is outside this change.

Not checked: live Folia regions, client screens, a complete natural village day, or legacy 1.21.9/1.21.10 support. CE custom-food timing and farmer seed reserves remain intentional extensions of vanilla behavior. Legacy injected pickup datapacks still require the documented removal and restart when changing pickup mode.
