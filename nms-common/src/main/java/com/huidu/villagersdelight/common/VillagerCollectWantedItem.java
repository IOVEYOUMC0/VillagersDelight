package com.huidu.villagersdelight.common;

import com.huidu.villagersdelight.core.CeItemAccess;
import com.huidu.villagersdelight.core.VillagersDelightPlugin;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.bukkit.event.entity.EntityRemoveEvent;

import java.util.Map;

// Picks up a nearby wanted CE item by its custom id, bypassing the vanilla wantsToPickUp gate that
// only admits villager_picks_up tag or requested-items matches. This lets a CE seed/food keep a
// vanilla base material without widening the tag for every villager. Run close to the entity; the
// vanilla GoToWantedItem behaviour supplies the walk target, this behaviour only snaps up the item
// once within reach.
public final class VillagerCollectWantedItem extends Behavior<Villager> {

    private static final double PICKUP_REACH = 1.5;

    public VillagerCollectWantedItem() {
        super(Map.of());
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, Villager villager) {
        if (villager.getAge() != 0 || villager.isSleeping()) {
            return false;
        }
        ItemEntity item = villager.getBrain().getMemory(MemoryModuleType.NEAREST_VISIBLE_WANTED_ITEM).orElse(null);
        if (item == null || !item.isAlive() || item.hasPickUpDelay() || item.getOwner() != null) {
            return false;
        }
        return isCollectable(item) && villager.hasLineOfSight(item) && item.closerThan(villager, PICKUP_REACH);
    }

    @Override
    protected void start(ServerLevel level, Villager villager, long gameTime) {
        ItemEntity item = villager.getBrain().getMemory(MemoryModuleType.NEAREST_VISIBLE_WANTED_ITEM).orElse(null);
        if (item == null || !item.isAlive() || item.hasPickUpDelay()) {
            return;
        }
        ItemStack stack = item.getItem();
        SimpleContainer inventory = villager.getInventory();
        if (!inventory.canAddItem(stack)) {
            return;
        }
        // Mirror InventoryCarrier.pickUpItem but drop the wantsToPickUp gate so CE custom items,
        // whose base material is not tagged, can be collected. The event still fires cancellation.
        if (CraftEventFactory.callEntityPickupItemEvent(villager, item, stack.getCount(), false).isCancelled()) {
            return;
        }
        villager.onItemPickup(item);
        int count = stack.getCount();
        ItemStack remainder = inventory.addItem(stack);
        villager.take(item, count - remainder.getCount());
        if (remainder.isEmpty()) {
            item.discard(EntityRemoveEvent.Cause.PICKUP);
        } else {
            stack.setCount(remainder.getCount());
        }
        // Cool down so the villager does not repeatedly target the same spot after taking an item.
        villager.getBrain().setMemory(MemoryModuleType.ITEM_PICKUP_COOLDOWN_TICKS, 40);
    }

    // True when this item is a configured CE pickup target or a vanilla item the villager wants.
    // Vanilla items keep their native wantsToPickUp path; only CE items are gated by config here.
    private static boolean isCollectable(ItemEntity item) {
        ItemStack stack = item.getItem();
        if (stack.isEmpty()) {
            return false;
        }
        var bukkit = CraftItemStack.asCraftMirror(stack);
        var custom = CeItemAccess.customItemId(bukkit);
        if (custom != null) {
            return VillagersDelightPlugin.isPickupConfigured(custom.toString());
        }
        return false;
    }
}