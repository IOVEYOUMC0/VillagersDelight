package com.huidu.villagersdelight.core;

import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
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
        if (stack == null || stack.isEmpty() || !CraftEngineItems.isCustomItem(stack)) {
            return null;
        }
        return CraftEngineItems.getCustomItemId(stack);
    }

    public static boolean isItem(ItemStack stack, Key itemId) {
        Key id = customItemId(stack);
        return id != null && id.equals(itemId);
    }
}
