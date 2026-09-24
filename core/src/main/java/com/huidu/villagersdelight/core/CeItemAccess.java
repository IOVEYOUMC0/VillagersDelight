package com.huidu.villagersdelight.core;

import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.item.BukkitItemDefinition;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

// Item-side CraftEngine helpers for the villager AI (seed matching etc.).
public final class CeItemAccess {

    private CeItemAccess() {
    }

    // Custom item id behind a Bukkit item stack, or null if it is not a custom CE item.
    @Nullable
    public static Key customItemId(ItemStack stack) {
        // getCustomItemId already reports null for vanilla stacks (CE re-checks emptiness inside), so the
        // isCustomItem probe only wrapped the same stack a second time.
        return stack == null || stack.isEmpty() ? null : CraftEngineItems.getCustomItemId(stack);
    }

    public static boolean isItem(ItemStack stack, Key itemId) {
        Key id = customItemId(stack);
        return id != null && id.equals(itemId);
    }

    // CraftEngine's configured compost_probability, or null for vanilla items.
    @Nullable
    public static Float compostProbability(ItemStack stack) {
        Key id = customItemId(stack);
        if (id == null) {
            return null;
        }
        BukkitItemDefinition definition = CraftEngineItems.byItemStack(stack);
        return definition == null ? null : definition.settings().compostProbability();
    }
}
