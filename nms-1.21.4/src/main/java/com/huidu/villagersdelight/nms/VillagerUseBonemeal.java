package com.huidu.villagersdelight.impl214;

import com.huidu.villagersdelight.core.CropRegistry;
import com.google.common.collect.ImmutableMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.BoneMealItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.momirealms.craftengine.core.block.ImmutableBlockState;

import java.util.Optional;

/** Vanilla bone-meal behavior with CE bonemealable blocks included in target selection. */
public final class VillagerUseBonemeal extends Behavior<Villager> {

    private Optional<BlockPos> cropPos = Optional.empty();
    private long nextWorkCycleTime;
    private long lastBonemealingSession;
    private int timeWorkedSoFar;

    public VillagerUseBonemeal() {
        super(ImmutableMap.of(
                MemoryModuleType.LOOK_TARGET, MemoryStatus.VALUE_ABSENT,
                MemoryModuleType.WALK_TARGET, MemoryStatus.VALUE_ABSENT));
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, Villager body) {
        if (body.tickCount % 10 != 0 || this.lastBonemealingSession != 0L
                && this.lastBonemealingSession + 160L > body.tickCount
                || body.getInventory().countItem(Items.BONE_MEAL) <= 0) {
            return false;
        }
        this.cropPos = this.pickNextTarget(level, body);
        return this.cropPos.isPresent();
    }

    private Optional<BlockPos> pickNextTarget(ServerLevel level, Villager body) {
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        Optional<BlockPos> result = Optional.empty();
        int count = 0;
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    mutable.setWithOffset(body.blockPosition(), x, y, z);
                    if (this.isValidTarget(mutable, level)
                            && level.getRandom().nextInt(++count) == 0) {
                        result = Optional.of(mutable.immutable());
                    }
                }
            }
        }
        return result;
    }

    private boolean isValidTarget(BlockPos pos, ServerLevel level) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof CropBlock crop) {
            return !crop.isMaxAge(state);
        }
        ImmutableBlockState ceState = VillagerFarmBehavior.ceStateAt(level, pos);
        return CropRegistry.cropOf(ceState) != null
                && state.getBlock() instanceof BonemealableBlock bonemealable
                && bonemealable.isValidBonemealTarget(level, pos, state);
    }

    @Override
    protected void start(ServerLevel level, Villager body, long timestamp) {
        this.setCurrentCropAsTarget(body);
        body.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BONE_MEAL));
        this.nextWorkCycleTime = timestamp + 20L;
        this.timeWorkedSoFar = 0;
    }

    private void setCurrentCropAsTarget(Villager body) {
        this.cropPos.ifPresent(pos -> {
            BlockPosTracker tracker = new BlockPosTracker(pos);
            body.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, tracker);
            body.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(tracker, 0.5F, 1));
        });
    }

    @Override
    protected void stop(ServerLevel level, Villager body, long timestamp) {
        body.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        this.lastBonemealingSession = body.tickCount;
    }

    @Override
    protected void tick(ServerLevel level, Villager body, long timestamp) {
        BlockPos target = this.cropPos.orElse(null);
        if (target != null && timestamp >= this.nextWorkCycleTime
                && target.closerToCenterThan(body.position(), 1.0)) {
            SimpleContainer inventory = body.getInventory();
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack boneMeal = inventory.getItem(i);
                if (boneMeal.is(Items.BONE_MEAL)
                        && BoneMealItem.growCrop(boneMeal, level, target)) {
                    level.levelEvent(1505, target, 15);
                    this.cropPos = this.pickNextTarget(level, body);
                    this.setCurrentCropAsTarget(body);
                    this.nextWorkCycleTime = timestamp + 40L;
                    break;
                }
            }
            this.timeWorkedSoFar++;
        }
    }

    @Override
    protected boolean canStillUse(ServerLevel level, Villager body, long timestamp) {
        return this.timeWorkedSoFar < 80 && this.cropPos.isPresent();
    }
}
