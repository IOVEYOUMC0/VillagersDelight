package com.huidu.villagersdelight.core;

import java.util.List;
import java.util.Set;

// Contract between the version-agnostic core and the NMS layer shipped in the same jar.
// Implemented reflectively by com.huidu.villagersdelight.nms.NmsVillagerAi.
public interface VillagerAiInjector {

    void install();

    void shutdown();

    // Configures the wanted-item sensor filter: villagers only set the NEAREST_VISIBLE_WANTED_ITEM
    // memory for CE items whose custom id is in seedKeys. Vanilla items are left untouched and an
    // empty set disables filtering (vanilla behaviour).
    void configurePickupFilter(Set<String> seedKeys);

    // Configures vanilla base item ids that count as one breeding food point. The NMS layer consumes
    // them into each villager's food level without modifying Minecraft's static FOOD_POINTS table.
    void configureFoodItems(java.util.List<String> itemIds);

    // Enables or disables the villager item-sharing behavior and sets the item ids villagers throw
    // at nearby villagers when holding more than one of them. Ids may be CE custom ids (namespaced,
    // matched against the CE identity of the held stack) or vanilla material keys. Empty item set
    // keeps the behavior installed but sharing nothing.
    void configureShareItems(boolean enabled, Set<String> itemIds);

    // Configures the CE item ids villagers may compost (VillagerWorkAtComposter) without modifying
    // the vanilla COMPOSTABLES table or exposing their base materials to global composting.
    void configureCeCompost(java.util.Set<String> ceItemIds);
}
