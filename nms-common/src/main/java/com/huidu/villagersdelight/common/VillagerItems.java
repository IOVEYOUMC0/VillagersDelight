package com.huidu.villagersdelight.common;

import com.huidu.villagersdelight.core.CeItemAccess;
import com.huidu.villagersdelight.core.CraftItemStackMirror;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.inventory.CraftItemStack;

import java.lang.reflect.Method;

/** Item and food helpers shared by the behaviours: every CE lookup goes through one item-stack mirror. */
public final class VillagerItems {

    // Paper 26.3 renamed CraftItemStack.asCraftMirror to asBukkitMirror and removed the old name, and
    // this one source is compiled into every layer, so neither name can be written here: naming the
    // 26.3 one would not compile on the 1.21.x dev bundles and naming the old one does not exist on a
    // 26.3 server. CraftEngine's proxy used to absorb that difference, but its binding names the
    // removed method and threw NoSuchMethodError from the first wanted-item sensor tick, so the
    // factory is resolved by name here instead - once, then cached, the same "resolve once, fail
    // loudly" shape as the main plugin's SchedulerAdapter.FoliaReflect.
    private static volatile Method mirror;

    private VillagerItems() {
    }

    private static Method mirror() {
        Method resolved = mirror;
        if (resolved == null) {
            synchronized (VillagerItems.class) {
                resolved = mirror;
                if (resolved == null) {
                    resolved = CraftItemStackMirror.require(CraftItemStack.class, ItemStack.class,
                            Bukkit.getBukkitVersion());
                    mirror = resolved;
                }
            }
        }
        return resolved;
    }

    /**
     * An NMS stack mirrored as a Bukkit stack, without copying its component patch.
     *
     *
     * The mirror factory is resolved once (asBukkitMirror where the server has it, else the pre-26.3
     * asCraftMirror) and a server with neither fails with an IllegalStateException naming the version
     * rather than an opaque NoSuchMethodError.
     *
     *
     * Never null: 26.3 mirrors an empty stack to null, older releases mirror it to an empty Bukkit
     * stack, and the empty case is normalised here to the value the old call sites observed - an AIR
     * stack, whose material key is minecraft:air.
     */
    public static org.bukkit.inventory.ItemStack bukkitStack(ItemStack stack) {
        if (stack == null) {
            return org.bukkit.inventory.ItemStack.empty();
        }
        try {
            org.bukkit.inventory.ItemStack mirrored =
                    (org.bukkit.inventory.ItemStack) mirror().invoke(null, stack);
            return CraftItemStackMirror.normalise(mirrored);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot mirror an item stack on this server ("
                    + Bukkit.getBukkitVersion() + ")", e);
        }
    }

    public static boolean isFarmer(Villager villager) {
        return ((org.bukkit.entity.Villager) villager.getBukkitEntity()).getProfession()
                == org.bukkit.entity.Villager.Profession.FARMER;
    }

    public static String itemId(ItemStack stack) {
        var bukkit = bukkitStack(stack);
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
        var bukkit = bukkitStack(stack);
        var custom = CeItemAccess.customItemId(bukkit);
        if (custom != null) return VillagerAiSettings.FOOD_RULES.value(custom.toString());
        // 26.3 moved the vanilla table (Villager.FOOD_POINTS) to the VILLAGER_FOOD item component, so
        // the lookup goes through the compat seam instead of the field.
        Integer vanilla = NmsCompat.vanillaVillagerFood(stack);
        return vanilla != null ? vanilla
                : VillagerAiSettings.FOOD_RULES.value(bukkit.getType().getKey().toString());
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
