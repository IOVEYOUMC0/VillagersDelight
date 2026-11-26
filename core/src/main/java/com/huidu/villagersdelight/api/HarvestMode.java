package com.huidu.villagersdelight.api;

/** How VillagersDelight harvests a configured crop. */
public enum HarvestMode {
    /** Break the block and let CraftEngine drop its loot. */
    BREAK,
    /** Break, drop loot, then replant at age 0. */
    PICK,
    /** Two-block crop: harvest the mature upper half and keep the lower stalk. */
    TALL
}
