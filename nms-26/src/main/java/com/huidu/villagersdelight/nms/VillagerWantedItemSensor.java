package com.huidu.villagersdelight.impl26;

import net.momirealms.craftengine.bukkit.item.BukkitItemManager;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.util.Key;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.sensing.NearestItemSensor;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.bukkit.craftbukkit.inventory.CraftItemStack;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

// Wanted-item sensor that only marks configured seeds as worth walking to. The base-class scan
// accepts every item whose base material was added to the farmer profession (e.g. knowledge book);
// this override filters those down to the actual seed items, so villagers ignore same-base items.
public final class VillagerWantedItemSensor extends NearestItemSensor {

    @Override
    protected void doTick(ServerLevel level, Mob mob) {
        Brain<?> brain = mob.getBrain();
        List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class,
                mob.getBoundingBox().inflate(32.0, 16.0, 32.0), item -> item.closerThan(mob, 32.0));
        items.sort(Comparator.comparingDouble(mob::distanceToSqr));
        Optional<ItemEntity> target = items.stream()
                .filter(item -> mob.wantsToPickUp(level, item.getItem()))
                .filter(item -> isAllowed(item.getItem()))
                .findFirst();
        brain.setMemory(MemoryModuleType.NEAREST_VISIBLE_WANTED_ITEM, target);
    }

    private static boolean isAllowed(ItemStack stack) {
        Set<String> seeds = NmsVillagerAi.ALLOWED_SEEDS;
        if (seeds.isEmpty()) {
            return true;
        }
        org.bukkit.inventory.ItemStack bukkit = CraftItemStack.asBukkitCopy(stack);
        Item item = BukkitItemManager.instance().wrap(bukkit);
        Key customId = item == null ? null : item.customId().orElse(null);
        if (customId != null) {
            return seeds.contains(customId.toString());
        }
        // Vanilla item: wanted only if it is itself a configured seed/food, never just because a CE item
        // shares its base material (otherwise villagers walk to plain steak / nether brick).
        return seeds.contains(bukkit.getType().getKey().toString());
    }
}