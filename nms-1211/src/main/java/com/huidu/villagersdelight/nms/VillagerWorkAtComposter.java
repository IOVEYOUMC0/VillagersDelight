package com.huidu.villagersdelight.impl1211;

import com.huidu.villagersdelight.core.CeItemAccess;
import io.papermc.paper.event.entity.EntityCompostItemEvent;
import it.unimi.dsi.fastutil.objects.Object2FloatMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.ai.behavior.WorkAtComposter;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import org.bukkit.craftbukkit.block.CraftBlock;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.bukkit.craftbukkit.inventory.CraftItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

// Drop-in replacement for the vanilla WorkAtComposter. The vanilla behavior only compacts the two
// hard-coded seeds and relies on the global COMPOSTABLES table; extending that table would make
// the base material of CE items (e.g. nether bricks) compostable for everyone. The replacement keeps
// the vanilla table untouched and additionally accepts configured CE items matched by their CE
// custom id, reproducing the vanilla fill logic against the real composter block.
public final class VillagerWorkAtComposter extends WorkAtComposter {

    private static final int MAX_ITEMS_PER_WORK = 20;
    private static final int MIN_STACK_KEPT = 10;

    @Override
    protected void useWorkstation(ServerLevel level, Villager body) {
        Optional<GlobalPos> jobSite = body.getBrain().getMemory(MemoryModuleType.JOB_SITE);
        if (jobSite.isEmpty()) {
            return;
        }
        GlobalPos jobSitePos = jobSite.get();
        BlockState blockState = level.getBlockState(jobSitePos.pos());
        if (blockState.is(Blocks.COMPOSTER)) {
            this.makeBread(level, body);
            this.compostItems(level, body, jobSitePos, blockState);
        }
    }

    // Vanilla walk: pick surplus compostable stacks from the tail of the inventory and push up to
    // 20 items into the composter; stop early once the composter is full (level 7).
    private void compostItems(ServerLevel level, Villager body, GlobalPos jobSitePos, BlockState blockState) {
        BlockPos pos = jobSitePos.pos();
        if (blockState.getValue(ComposterBlock.LEVEL) == 8) {
            blockState = ComposterBlock.extractProduce(body, blockState, level, pos);
        }
        int totalItemsToUse = MAX_ITEMS_PER_WORK;
        SimpleContainer inventory = body.getInventory();
        Map<String, Integer> itemsSeen = new HashMap<>();
        BlockState tempState = blockState;
        for (int i = inventory.getContainerSize() - 1; i >= 0 && totalItemsToUse > 0; i--) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || !isCompostable(stack)) {
                continue;
            }
            int totalItemCount = itemsSeen.merge(compostId(stack), stack.getCount(), Integer::sum);
            int itemsToUse = Math.min(Math.min(totalItemCount - MIN_STACK_KEPT, totalItemsToUse), stack.getCount());
            if (itemsToUse <= 0) {
                continue;
            }
            totalItemsToUse -= itemsToUse;
            for (int j = 0; j < itemsToUse; j++) {
                tempState = insertItem(level, body, tempState, stack, pos);
                if (tempState.getValue(ComposterBlock.LEVEL) == 7) {
                    spawnComposterFillEffects(level, blockState, pos, tempState);
                    return;
                }
            }
        }
        spawnComposterFillEffects(level, blockState, pos, tempState);
    }

    /**
     * Vanilla ComposterBlock.insertItem gates on the global COMPOSTABLES table, which would reject
     * CE items, so the fill logic is reproduced here with the isCompostable gate (vanilla entry or
     * configured CE id). Probability: the vanilla entry when present, a fixed default otherwise.
     */
    private static BlockState insertItem(ServerLevel level, Villager body, BlockState state, ItemStack stack, BlockPos pos) {
        int fillLevel = state.getValue(ComposterBlock.LEVEL);
        if (fillLevel >= 7 || !isCompostable(stack)) {
            return state;
        }
        double rand = level.getRandom().nextDouble();
        float chance = compostChance(stack);
        boolean willRaise = fillLevel == 0 && !(chance <= 0.0F) || rand < chance;
        EntityCompostItemEvent event = new EntityCompostItemEvent(
                body.getBukkitEntity(), CraftBlock.at(level, pos), stack.getBukkitStack(), willRaise);
        if (!event.callEvent()) {
            return state;
        }
        willRaise = event.willRaiseLevel();
        if (!willRaise) {
            return state;
        }
        int newLevel = fillLevel + 1;
        BlockState newState = state.setValue(ComposterBlock.LEVEL, newLevel);
        if (!CraftEventFactory.callEntityChangeBlockEvent(body, pos, newState)) {
            return state;
        }
        level.setBlock(pos, newState, 3);
        level.gameEvent(GameEvent.BLOCK_CHANGE, pos, GameEvent.Context.of(body, newState));
        if (newLevel == 7) {
            level.scheduleTick(pos, state.getBlock(), 20);
        }
        stack.shrink(1);
        return newState;
    }

    // A stack is compostable when the vanilla table accepts its item or its CE custom id is in the
    // configured set. The vanilla base material alone (e.g. nether bricks) is never enough.
    private static boolean isCompostable(ItemStack stack) {
        org.bukkit.inventory.ItemStack bukkit = CraftItemStack.asBukkitCopy(stack);
        net.momirealms.craftengine.core.util.Key custom = CeItemAccess.customItemId(bukkit);
        Set<String> ids = NmsVillagerAi.COMPOST_IDS;
        if (custom != null) {
            return ids.contains(custom.toString());
        }
        return stack.is(Items.WHEAT_SEEDS) || stack.is(Items.BEETROOT_SEEDS)
                || ids.contains(bukkit.getType().getKey().toString());
    }

    private static String compostId(ItemStack stack) {
        org.bukkit.inventory.ItemStack bukkit = CraftItemStack.asBukkitCopy(stack);
        net.momirealms.craftengine.core.util.Key custom = CeItemAccess.customItemId(bukkit);
        return custom != null ? custom.toString() : bukkit.getType().getKey().toString();
    }

    private static float compostChance(ItemStack stack) {
        if (CeItemAccess.customItemId(CraftItemStack.asBukkitCopy(stack)) != null) {
            return NmsVillagerAi.COMPOST_CHANCE;
        }
        Object2FloatMap<ItemLike> table = ComposterBlock.COMPOSTABLES;
        if (table.containsKey(stack.getItem())
                && (stack.is(Items.WHEAT_SEEDS) || stack.is(Items.BEETROOT_SEEDS))) {
            return table.getFloat(stack.getItem());
        }
        return NmsVillagerAi.COMPOST_CHANCE;
    }

    // Copied from vanilla: bakes surplus wheat into bread while working at the composter.
    private static void makeBread(ServerLevel level, Villager body) {
        SimpleContainer inventory = body.getInventory();
        if (inventory.countItem(Items.BREAD) > 36) {
            return;
        }
        int wheat = inventory.countItem(Items.WHEAT);
        int breadToMake = Math.min(3, wheat / 3);
        if (breadToMake == 0) {
            return;
        }
        inventory.removeItemType(Items.WHEAT, breadToMake * 3);
        ItemStack leftOver = inventory.addItem(new ItemStack(Items.BREAD, breadToMake));
        if (!leftOver.isEmpty()) {
            body.spawnAtLocation(level, leftOver, 0.5F);
        }
    }

    private static void spawnComposterFillEffects(ServerLevel level, BlockState oldState, BlockPos pos, BlockState newState) {
        level.levelEvent(1500, pos, newState != oldState ? 1 : 0);
    }
}
