package com.huidu.villagersdelight.impl1211;

import com.google.common.collect.ImmutableSet;
import com.huidu.villagersdelight.core.CropRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.sensing.Sensor;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.core.block.ImmutableBlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

// Drop-in replacement for the vanilla SecondaryPoiSensor. The vanilla one only counts the
// profession's secondary POI block (farmland), so a farm without vanilla farmland never populates
// SECONDARY_JOB_SITE and the farm behavior never starts. This sensor also accepts any CE soil
// the configured crops can be planted on.
public final class VillagerSecondaryPoiSensor extends Sensor<Villager> {

    public VillagerSecondaryPoiSensor() {
        super(40);
    }

    @Override
    protected void doTick(ServerLevel level, Villager entity) {
        ResourceKey<Level> resourceKey = level.dimension();
        BlockPos blockPos = entity.blockPosition();
        List<GlobalPos> list = new ArrayList<>();
        for (int i1 = -4; i1 <= 4; i1++) {
            for (int i2 = -2; i2 <= 2; i2++) {
                for (int i3 = -4; i3 <= 4; i3++) {
                    BlockPos blockPos1 = blockPos.offset(i1, i2, i3);
                    BlockState state = level.getBlockState(blockPos1);
                    if (state.isAir()) {
                        continue;
                    }
                    if (state.getBlock() instanceof FarmBlock) {
                        list.add(GlobalPos.of(resourceKey, blockPos1));
                        continue;
                    }
                    // The state was fetched above; parse the CE block straight from it instead of
                    // re-querying the chunk (BlockStateUtils just instanceof-checks the wrapper).
                    ImmutableBlockState ceState = BlockStateUtils.getOptionalCustomBlockState(state).orElse(null);
                    if (ceState != null && CropRegistry.isPlantableSoil(ceState)) {
                        list.add(GlobalPos.of(resourceKey, blockPos1));
                    }
                }
            }
        }
        Brain<?> brain = entity.getBrain();
        if (list.isEmpty()) {
            brain.eraseMemory(MemoryModuleType.SECONDARY_JOB_SITE);
        } else {
            brain.setMemory(MemoryModuleType.SECONDARY_JOB_SITE, list);
        }
    }

    @Override
    public Set<MemoryModuleType<?>> requires() {
        return ImmutableSet.of(MemoryModuleType.SECONDARY_JOB_SITE);
    }
}