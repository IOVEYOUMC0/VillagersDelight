package com.huidu.villagersdelight.common;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.core.block.ImmutableBlockState;

/** CraftEngine block-state lookups shared by the farm and bonemeal behaviours. */
public final class VillagerBlockAccess {

    private VillagerBlockAccess() {
    }

    // Reads the CraftEngine custom block state at a position. The vanilla level state only holds
    // the base block, so custom crops and rich soil are resolved through the CE proxy.
    public static ImmutableBlockState ceStateAt(ServerLevel level, BlockPos pos) {
        return ceStateOf(level.getBlockState(pos));
    }

    // CraftEngine marks its own states by making the state object a DelegatingBlockState, so a state the
    // caller already holds unwraps directly. Callers that have one should use this instead of ceStateAt,
    // which would fetch the very same state from the chunk a second time.
    public static ImmutableBlockState ceStateOf(BlockState state) {
        try {
            return BlockStateUtils.getNullableCustomBlockState(state);
        } catch (RuntimeException | LinkageError t) {
            return null;
        }
    }

}
