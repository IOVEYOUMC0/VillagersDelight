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

import java.util.Comparator;
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
                mob.getBoundingBox().inflate(32.0, 16.0, 32.0), item -> item.closerThan(mob, 32.0));
        // Drop the items this villager cannot collect before sorting: in an item-dense area the box holds far
        // more entities than the villager would ever want, and the filter is a set lookup for CE items.
        items.removeIf(item -> !isCollectable(mob, level, item.getItem()));
        items.sort(Comparator.comparingDouble(mob::distanceToSqr));
        Optional<ItemEntity> target = items.stream()
                .filter(item -> mob.hasLineOfSight(item))
                .findFirst();
        brain.setMemory(MemoryModuleType.NEAREST_VISIBLE_WANTED_ITEM, target);
    }

    private static boolean isCollectable(Mob mob, ServerLevel level, ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        var custom = CeItemAccess.customItemId(VillagerItems.bukkitStack(stack));
        if (custom != null) {
            return VillagersDelightPlugin.isPickupConfigured(custom.toString());
        }
        return mob.wantsToPickUp(level, stack);
    }
}
