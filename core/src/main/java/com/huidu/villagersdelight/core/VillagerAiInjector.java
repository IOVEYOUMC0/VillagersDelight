package com.huidu.villagersdelight.core;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.Set;
import java.util.function.BooleanSupplier;

// Contract between the version-agnostic core and the NMS layer shipped in the same jar.
// Implemented reflectively by com.huidu.villagersdelight.nms.NmsVillagerAi.
public interface VillagerAiInjector {

    void install();

    void shutdown();

    Inventory openBackpack(Player player, Villager villager, Component title,
                           ItemStack padding, BooleanSupplier accessible);

    // Custom food points and farmer seed reserves; Minecraft's static food table is untouched.
    void configureFoodItems(VillagerFoodRules rules);

    // Configures the CE item ids villagers may compost (VillagerWorkAtComposter) without modifying
    // the vanilla COMPOSTABLES table or exposing their base materials to global composting.
    void configureCeCompost(Set<String> ceItemIds);

    void configureBehavior(VillagersDelightConfig.BehaviorSettings settings);

    // True when the NMS layer injects a custom-id pickup path, so the core can skip writing the
    // villager_picks_up data pack. Vanilla-id items still flow through the pickup tag in that mode.
    boolean supportsCustomIdPickup();

    // Enables/disables the injected custom-id collect behaviour. When disabled (legacy datapack-tag
    // mode) CE items are already let in by the widened tag and vanilla pickup handles them, so the
    // collect behaviour must stay out to avoid double-picking.
    void setCustomIdPickup(boolean enabled);

    // Removes injected behaviors whose runtime class is in behaviorTypes, descending into GateBehavior
    // shuffling lists. Used to withdraw a behavior whose config was turned off without a server restart.
    void uninstallBehaviors(Set<Class<?>> behaviorTypes);
}
