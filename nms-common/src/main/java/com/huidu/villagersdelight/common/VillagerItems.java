package com.huidu.villagersdelight.common;

import com.huidu.villagersdelight.core.CeItemAccess;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import org.bukkit.craftbukkit.inventory.CraftItemStack;

/** Item and food helpers shared by the behaviours: every CE lookup goes through one CraftItemStack mirror. */
public final class VillagerItems {

    private VillagerItems() {
    }

    public static boolean isFarmer(Villager villager) {
        return ((org.bukkit.entity.Villager) villager.getBukkitEntity()).getProfession()
                == org.bukkit.entity.Villager.Profession.FARMER;
    }

    public static String itemId(ItemStack stack) {
        var bukkit = CraftItemStack.asCraftMirror(stack);
        var custom = CeItemAccess.customItemId(bukkit);
        return custom != null ? custom.toString() : bukkit.getType().getKey().toString();
    }

    public static int countHeld(Villager villager, String id) {
        SimpleContainer inventory = villager.getInventory();
        int total = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && id.equals(itemId(stack))) total += stack.getCount();
        }
        return total;
    }

    public static int foodValue(ItemStack stack) {
        if (stack.isEmpty()) return 0;
        var bukkit = CraftItemStack.asCraftMirror(stack);
        var custom = CeItemAccess.customItemId(bukkit);
        if (custom != null) return VillagerAiSettings.FOOD_RULES.value(custom.toString());
        return Villager.FOOD_POINTS.getOrDefault(stack.getItem(), VillagerAiSettings.FOOD_RULES.value(bukkit.getType().getKey().toString()));
    }

    public static int foodPointsInInventory(Villager villager) {
        SimpleContainer inventory = villager.getInventory();
        int total = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            total += stack.getCount() * foodValue(stack);
        }
        return total;
    }

    // Food points and the wheat count of one villager in a single pass: both resolve a CE id per slot, so
    // counting them together halves that work. Index 0 is the food points, index 1 the wheat count.
    public static int[] foodPointsAndWheat(Villager villager) {
        SimpleContainer inventory = villager.getInventory();
        int points = 0;
        int wheat = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            points += stack.getCount() * foodValue(stack);
            if ("minecraft:wheat".equals(itemId(stack))) {
                wheat += stack.getCount();
            }
        }
        return new int[]{points, wheat};
    }
}
