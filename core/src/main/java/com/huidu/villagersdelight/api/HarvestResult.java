package com.huidu.villagersdelight.api;

import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Result of {@link VillagersDelightApi#harvest(org.bukkit.block.Block)}.
 *
 * <p>The returned stacks are copies owned by the caller. {@code harvest(Block)} spawns the same
 * drops at the harvested block as well; use {@code harvestPreview(Block)} (or
 * {@code harvest(Block, false)}) when only copies are wanted. When {@code harvested} is false the
 * block was not a configured mature crop and was left untouched.</p>
 */
public record HarvestResult(boolean harvested, List<ItemStack> drops) {

    public HarvestResult {
        drops = drops == null ? List.of() : drops.stream().map(ItemStack::clone).toList();
    }

    public static HarvestResult failure() {
        return new HarvestResult(false, List.of());
    }

    public static HarvestResult success(List<ItemStack> drops) {
        return new HarvestResult(true, drops);
    }
}
