package com.huidu.villagersdelight.impl26;

import com.google.common.collect.ImmutableSet;
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

// Drop-in replacement for the vanilla TradeWithVillager behaviour. Keeps the vanilla social
// interaction (walk to each other, gossip) and the half-stack throwing rules, but throws a copy
// of the original stack instead of a freshly built plain vanilla one. The vanilla rebuild drops
// all NBT, so CraftEngine items lost their custom identity and were thrown as their underlying
// material (e.g. an onion as a steak).
public final class VillagerTradeWithVillager extends TradeWithVillager {

    private Set<Item> trades = ImmutableSet.of();

    @Override
    protected void start(ServerLevel level, Villager myBody, long timestamp) {
        Villager target = (Villager) myBody.getBrain().getMemory(MemoryModuleType.INTERACTION_TARGET).get();
        BehaviorUtils.lockGazeAndWalkToEachOther(myBody, target, 0.5F, 2);
        this.trades = figureOutWhatIAmWillingToTrade(myBody, target);
    }

    @Override
    protected void tick(ServerLevel level, Villager body, long timestamp) {
        Villager target = (Villager) body.getBrain().getMemory(MemoryModuleType.INTERACTION_TARGET).get();
        if (!(body.distanceToSqr(target) > 5.0)) {
            BehaviorUtils.lockGazeAndWalkToEachOther(body, target, 0.5F, 2);
            body.gossip(level, target, timestamp);
            boolean isFarmer = body.getVillagerData().profession().is(VillagerProfession.FARMER);
            if (body.hasExcessFood() && (isFarmer || target.wantsMoreFood())) {
                throwHalfStack(body, Villager.FOOD_POINTS.keySet(), target);
            }
            if (isFarmer && body.getInventory().countItem(Items.WHEAT) > Items.WHEAT.getDefaultMaxStackSize() / 2) {
                throwHalfStack(body, ImmutableSet.of(Items.WHEAT), target);
            }
            if (!this.trades.isEmpty() && body.getInventory().hasAnyOf(this.trades)) {
                throwHalfStack(body, this.trades, target);
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
    private static void throwHalfStack(Villager villager, Set<Item> items, LivingEntity target) {
        SimpleContainer inventory = villager.getInventory();
        ItemStack toThrow = ItemStack.EMPTY;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack itemStack = inventory.getItem(i);
            if (!itemStack.isEmpty()) {
                Item item = itemStack.getItem();
                if (items.contains(item)) {
                    int count;
                    if (itemStack.getCount() > itemStack.getMaxStackSize() / 2) {
                        count = itemStack.getCount() / 2;
                    } else {
                        if (itemStack.getCount() <= 24) {
                            continue;
                        }
                        count = itemStack.getCount() - 24;
                    }
                    itemStack.shrink(count);
                    toThrow = itemStack.copy();
                    toThrow.setCount(count);
                    break;
                }
            }
        }
        if (!toThrow.isEmpty()) {
            BehaviorUtils.throwItem(villager, toThrow, target.position());
        }
    }
}
