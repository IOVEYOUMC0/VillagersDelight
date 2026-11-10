package com.huidu.villagersdelight.common;

import com.huidu.villagersdelight.core.CeItemAccess;
import com.huidu.villagersdelight.core.VillagersDelightPlugin;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.sensing.NearestItemSensor;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.bukkit.Bukkit;
import net.minecraft.world.entity.npc.villager.Villager;

import java.util.List;
import java.util.Optional;

// Sets NEAREST_VISIBLE_WANTED_ITEM so GoToWantedItem walks the villager towards a target. CE custom
// items must not pass through the vanilla wantsToPickUp gate (their base material is not in the
// villager_picks_up tag), so they are admitted by their custom id instead. Vanilla items keep the
// native wantsToPickUp path unchanged.
public final class VillagerWantedItemSensor extends NearestItemSensor {

    @Override
    protected void doTick(ServerLevel level, Mob mob) {
        Brain<?> brain = mob.getBrain();
        List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class,
                mob.getBoundingBox().inflate(32.0, 16.0, 32.0), item ->
                        Bukkit.isOwnedByCurrentRegion(item.getBukkitEntity()) && item.closerThan(mob, 32.0)
                                && isCollectable(mob, level, item.getItem()));
        // Only the nearest visible item is needed. The old full sort was O(n log n) on every sensor tick;
        // this stable single pass preserves the original query order for equal-distance candidates.
        ItemEntity nearest = null;
        double nearestDistance = Double.POSITIVE_INFINITY;
        for (ItemEntity item : items) {
            double distance = mob.distanceToSqr(item);
            if (distance >= nearestDistance || !mob.hasLineOfSight(item)) {
                continue;
            }
            nearest = item;
            nearestDistance = distance;
        }
        brain.setMemory(MemoryModuleType.NEAREST_VISIBLE_WANTED_ITEM, Optional.ofNullable(nearest));
    }

    private static boolean isCollectable(Mob mob, ServerLevel level, ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        var custom = CeItemAccess.customItemId(VillagerItems.bukkitStack(stack));
        if (custom != null) {
            return VillagersDelightPlugin.isPickupConfigured(custom.toString())
                    && mob instanceof Villager villager && villager.getInventory().canAddItem(stack);
        }
        return mob.wantsToPickUp(level, stack);
    }
}
