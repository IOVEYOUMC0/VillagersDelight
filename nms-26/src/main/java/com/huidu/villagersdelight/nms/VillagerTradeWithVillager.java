package com.huidu.villagersdelight.impl26;

import com.google.common.collect.ImmutableSet;
import com.huidu.villagersdelight.impl26.common.VillagerAiSettings;
import com.huidu.villagersdelight.impl26.common.VillagerItems;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.BehaviorUtils;
import net.minecraft.world.entity.ai.behavior.TradeWithVillager;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Set;
import java.util.stream.Collectors;

// Keeps villager walking, gossip and half-stack sharing behavior.
// Copy shared stacks with their components so custom item IDs and metadata survive the throw.
public final class VillagerTradeWithVillager extends TradeWithVillager {

    private Set<Item> trades = ImmutableSet.of();

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, Villager body) {
        if (!super.checkExtraStartConditions(level, body)) return false;
        return body.getBrain().getMemory(MemoryModuleType.INTERACTION_TARGET)
                .filter(target -> org.bukkit.Bukkit.isOwnedByCurrentRegion(target.getBukkitEntity())).isPresent();
    }

    @Override
    protected void start(ServerLevel level, Villager myBody, long timestamp) {
        Villager target = (Villager) myBody.getBrain().getMemory(MemoryModuleType.INTERACTION_TARGET).get();
        BehaviorUtils.lockGazeAndWalkToEachOther(myBody, target, 0.5F, 2);
        this.trades = figureOutWhatIAmWillingToTrade(myBody, target);
    }

    @Override
    protected void tick(ServerLevel level, Villager body, long timestamp) {
        Villager target = (Villager) body.getBrain().getMemory(MemoryModuleType.INTERACTION_TARGET).get();
        if (!org.bukkit.Bukkit.isOwnedByCurrentRegion(target.getBukkitEntity())) return;
        if (!(body.distanceToSqr(target) > 5.0)) {
            BehaviorUtils.lockGazeAndWalkToEachOther(body, target, 0.5F, 2);
            body.gossip(level, target, timestamp);
            boolean isFarmer = body.getVillagerData().profession().is(VillagerProfession.FARMER);
            // Both counts come from one inventory pass; the food throw can take wheat with it, so the wheat
            // count is only refreshed when that throw actually ran.
            int[] totals = VillagerItems.foodPointsAndWheat(body);
            boolean threwFood = false;
            if (totals[0] >= 24 && (isFarmer || VillagerItems.foodPointsInInventory(target) < 12)) {
                throwHalfStack(body, Villager.FOOD_POINTS.keySet(), target, true);
                threwFood = true;
            }
            if (isFarmer) {
                int wheat = threwFood ? VillagerItems.countHeld(body, "minecraft:wheat") : totals[1];
                if (wheat > Items.WHEAT.getDefaultMaxStackSize() / 2) {
                    throwHalfStack(body, ImmutableSet.of(Items.WHEAT), target, false);
                }
            }
            if (!this.trades.isEmpty() && body.getInventory().hasAnyOf(this.trades)) {
                throwHalfStack(body, this.trades, target, false);
            }
        }
    }

    private static Set<Item> figureOutWhatIAmWillingToTrade(Villager myBody, Villager target) {
        ImmutableSet<Item> targetItems = target.getVillagerData().profession().value().requestedItems();
        ImmutableSet<Item> selfItems = myBody.getVillagerData().profession().value().requestedItems();
        return targetItems.stream().filter(entry -> !selfItems.contains(entry)).collect(Collectors.toSet());
    }

    // Vanilla half-stack rule, but the thrown stack is a copy of the original so CE items keep
    // their custom identity instead of being rebuilt as a plain vanilla item.
    private static void throwHalfStack(Villager villager, Set<Item> items, LivingEntity target, boolean food) {
        SimpleContainer inventory = villager.getInventory();
        ItemStack toThrow = ItemStack.EMPTY;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack itemStack = inventory.getItem(i);
            if (!itemStack.isEmpty()) {
                Item item = itemStack.getItem();
                boolean custom = com.huidu.villagersdelight.core.CeItemAccess.customItemId(
                        org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftMirror(itemStack)) != null;
                if (food ? VillagerItems.foodValue(itemStack) > 0 : !custom && items.contains(item)) {
                    int count;
                    if (itemStack.getCount() > itemStack.getMaxStackSize() / 2) {
                        count = itemStack.getCount() / 2;
                    } else {
                        if (itemStack.getCount() <= 24) {
                            continue;
                        }
                        count = itemStack.getCount() - 24;
                    }
                    if (custom) {
                        String id = VillagerItems.itemId(itemStack);
                        int reserve = VillagerAiSettings.FOOD_RULES.reserve(id, VillagerItems.isFarmer(villager));
                        count = Math.min(count, Math.max(0, VillagerItems.countHeld(villager, id) - reserve));
                    }
                    if (count <= 0) continue;
                    toThrow = itemStack.split(count);
                    break;
                }
            }
        }
        if (!toThrow.isEmpty()) {
            BehaviorUtils.throwItem(villager, toThrow, target.position());
        }
    }
}
