package com.huidu.villagersdelight.core;

import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.jetbrains.annotations.Nullable;

// Thin wrapper over the CraftEngine Bukkit API used by the villager AI. Keeps all CE access in one
// place so the NMS layer only sees plain Bukkit types and FDCrop.
public final class CeBlockAccess {

    private CeBlockAccess() {
    }

    // Low-frequency Bukkit-side lookup of a custom block state at a location.
    @Nullable
    public static ImmutableBlockState customStateAt(Block block) {
        return CraftEngineBlocks.getCustomBlockState(block);
    }

    // Places a custom crop at its first growth stage. Returns false if the block id is unknown.
    public static boolean placeCrop(Location location, Key blockId, int age, boolean playSound) {
        BlockDefinition definition = CraftEngineBlocks.byId(blockId);
        if (definition == null) {
            return false;
        }
        ImmutableBlockState state = definition.defaultState();
        Property<Integer> ageProperty = BlockBehaviorFactory.getOptionalProperty(definition, "age", Integer.class);
        if (ageProperty != null) {
            state = state.with(ageProperty, age);
        }
        return CraftEngineBlocks.place(location, state, playSound);
    }

    // Removes a custom block from the world. No drops are produced by CE here; the caller decides.
    public static boolean removeBlock(Block block) {
        return CraftEngineBlocks.remove(block);
    }

    // Places a specific custom block state (e.g. a reset lower half after harvesting the upper one).
    public static boolean placeState(Block block, ImmutableBlockState state) {
        return CraftEngineBlocks.place(block.getLocation(), state, false);
    }

    // NMS-level block state a custom block would occupy, for use as the "to-be-placed" state in
    // Bukkit pre-placement events. Returns null if the block id is unknown.
    @Nullable
    public static Object placeStateMinecraft(Key blockId) {
        BlockDefinition definition = CraftEngineBlocks.byId(blockId);
        return definition == null ? null : definition.defaultState().customBlockState().minecraftState();
    }
}
