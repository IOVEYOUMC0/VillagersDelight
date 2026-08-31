package com.huidu.villagersdelight.impl26;

import com.huidu.villagersdelight.core.CeBlockAccess;
import com.huidu.villagersdelight.core.CeItemAccess;
import com.huidu.villagersdelight.core.CustomCropsCompat;
import com.huidu.villagersdelight.core.CropRegistry;
import com.huidu.villagersdelight.core.VillagersDelightPlugin;
import com.huidu.villagersdelight.core.FDCrop;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.behavior.HarvestFarmland;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.FluidState;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.plugin.context.ContextHolder;
import net.momirealms.craftengine.core.plugin.context.EventTrigger;
import net.momirealms.craftengine.core.plugin.context.PlayerOptionalContext;
import net.momirealms.craftengine.core.plugin.context.parameter.DirectContextParameters;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.WorldPosition;
import net.momirealms.craftengine.proxy.bukkit.craftbukkit.CraftWorldProxy;
import net.momirealms.craftengine.proxy.minecraft.core.BlockPosProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.BlockGetterProxy;
import org.bukkit.Location;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.bukkit.craftbukkit.inventory.CraftItemStack;

import java.util.ArrayList;
import java.util.List;

// Replacement for the vanilla HarvestFarmland (26.x). Reproduces the vanilla logic so Purpur /
// Paper behaviour (mob-griefing, EntityChangeBlockEvent) is preserved, and extends it with FD crop
// handling: mature CE crops are harvested, CE seeds are planted on farmland and rich soil.
public final class VillagerFarmBehavior extends HarvestFarmland {

    private BlockPos aboveFarmlandPos;
    private long nextOkStartTime;
    private int timeWorkedSoFar;
    private final List<BlockPos> validFarmlandAroundVillager = new ArrayList<>();

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, Villager villager) {
        if (!level.getGameRules().get(GameRules.MOB_GRIEFING)) {
            return false;
        }
        BlockPos.MutableBlockPos mut = villager.blockPosition().mutable();
        this.validFarmlandAroundVillager.clear();
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    mut.set(villager.getX() + x, villager.getY() + y, villager.getZ() + z);
                    if (this.isValidTarget(mut, level, villager)) {
                        this.validFarmlandAroundVillager.add(new BlockPos(mut));
                    }
                }
            }
        }
        this.aboveFarmlandPos = this.validFarmlandAroundVillager.isEmpty()
                ? null
                : this.validFarmlandAroundVillager.get(level.getRandom().nextInt(this.validFarmlandAroundVillager.size()));
        return this.aboveFarmlandPos != null;
    }

    private boolean isValidTarget(BlockPos pos, ServerLevel level, Villager villager) {
        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();
        // Harvest targets are valid regardless of what the villager holds.
        if (block instanceof CropBlock crop && crop.isMaxAge(state)) {
            return true;
        }
        ImmutableBlockState ceState = this.ceStateAt(level, pos);
        if (ceState != null && CropRegistry.matureCropOf(ceState) != null) {
            return true;
        }
        CustomCropsCompat.State customCrop = CustomCropsCompat.stateAt(level.getWorld(),
                pos.getX(), pos.getY(), pos.getZ());
        if (customCrop != null && customCrop.mature()) {
            return true;
        }
        // Plant targets are only valid if the villager carries a seed it can actually sow here; otherwise it
        // walks over and stares at a spot it can never plant (e.g. rice, a water crop, on dry rich soil).
        boolean plantSpot = (state.isAir() && this.isVanillaFarmland(level, pos))
                || this.isPlantableWater(level, pos)
                || (state.isAir() && this.isCePlantableSoil(level, pos.below()))
                || CustomCropsCompat.isPotentialPlantingSpot(new Location(level.getWorld(),
                pos.getX(), pos.getY(), pos.getZ()));
        return plantSpot && this.hasPlantableSeedFor(level, villager, pos);
    }

    // Read-only twin of plantSeeds' matching: whether any inventory slot holds a seed sowable at pos, so a
    // plant spot is only selected as a target when planting would actually happen.
    private boolean hasPlantableSeedFor(ServerLevel level, Villager villager, BlockPos pos) {
        SimpleContainer inventory = villager.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            FDCrop fdCrop = configuredSeed(stack);
            if (fdCrop != null && canPlantHere(level, pos, fdCrop)
                    && (!this.isVanillaSeed(stack) || !this.isVanillaFarmland(level, pos))) {
                return true;
            }
            String customCropId = CustomCropsCompat.cropId(CraftItemStack.asBukkitCopy(stack));
            if (customCropId != null && CustomCropsCompat.canPlantAt(
                    new Location(level.getWorld(), pos.getX(), pos.getY(), pos.getZ()), customCropId)) {
                return true;
            }
            if (customCropId == null && this.isVanillaSeed(stack) && this.isVanillaPlantingSpot(level, pos)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void start(ServerLevel level, Villager villager, long gameTime) {
        if (gameTime > this.nextOkStartTime && this.aboveFarmlandPos != null) {
            villager.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, new BlockPosTracker(this.aboveFarmlandPos));
            villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                    new WalkTarget(new BlockPosTracker(this.standableWalkTarget(level, this.aboveFarmlandPos, villager)), 0.5F, 1));
        }
    }

    @Override
    protected void stop(ServerLevel level, Villager villager, long gameTime) {
        villager.getBrain().eraseMemory(MemoryModuleType.LOOK_TARGET);
        villager.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        this.timeWorkedSoFar = 0;
        this.nextOkStartTime = gameTime + 40L;
    }

    @Override
    protected void tick(ServerLevel level, Villager villager, long gameTime) {
        if (this.aboveFarmlandPos == null
                || this.aboveFarmlandPos.closerToCenterThan(villager.position(),
                        this.isTallWaterCell(level, this.aboveFarmlandPos) ? 1.5 : 1.0)) {
            if (this.aboveFarmlandPos != null && gameTime > this.nextOkStartTime) {
                BlockState state = level.getBlockState(this.aboveFarmlandPos);
                Block block = state.getBlock();

                // Harvest a mature FD crop first; the CE block state is resolved through the
                // CraftEngine proxy because the vanilla state only shows the base block.
                ImmutableBlockState ceState = this.ceStateAt(level, this.aboveFarmlandPos);
                FDCrop fdCrop = ceState == null ? null : CropRegistry.matureCropOf(ceState);
                CustomCropsCompat.State customCrop = CustomCropsCompat.stateAt(level.getWorld(),
                        this.aboveFarmlandPos.getX(), this.aboveFarmlandPos.getY(), this.aboveFarmlandPos.getZ());
                boolean harvested = false;
                if (fdCrop != null) {
                    if (CraftEventFactory.callEntityChangeBlockEvent(villager, this.aboveFarmlandPos, state.getFluidState().createLegacyBlock())) {
                        this.harvestFdCrop(level, villager, this.aboveFarmlandPos, fdCrop);
                        harvested = true;
                        VillagersDelightPlugin.debug("harvest: " + fdCrop.blockId() + " at " + this.aboveFarmlandPos);
                    }
                } else if (customCrop != null && customCrop.mature()) {
                    if (CraftEventFactory.callEntityChangeBlockEvent(villager, this.aboveFarmlandPos, state)) {
                        harvested = CustomCropsCompat.harvest(new Location(level.getWorld(),
                                this.aboveFarmlandPos.getX(), this.aboveFarmlandPos.getY(), this.aboveFarmlandPos.getZ()));
                    }
                } else if (block instanceof CropBlock crop && crop.isMaxAge(state)) {
                    if (CraftEventFactory.callEntityChangeBlockEvent(villager, this.aboveFarmlandPos, state.getFluidState().createLegacyBlock())) {
                        level.destroyBlock(this.aboveFarmlandPos, true, villager);
                        harvested = true;
                    }
                }

                // Plant seeds on vanilla farmland, extra soil, or on a fluid spot whose bed
                // accepts the crop.
                boolean planted = false;
                if (state.isAir() && (this.isVanillaFarmland(level, this.aboveFarmlandPos)
                        || this.isCePlantableSoil(level, this.aboveFarmlandPos.below()))) {
                    planted = this.plantSeeds(level, villager, this.aboveFarmlandPos);
                } else if (this.isPlantableWater(level, this.aboveFarmlandPos)) {
                    planted = this.plantSeeds(level, villager, this.aboveFarmlandPos);
                } else if (CustomCropsCompat.isPotentialPlantingSpot(new Location(level.getWorld(),
                        this.aboveFarmlandPos.getX(), this.aboveFarmlandPos.getY(), this.aboveFarmlandPos.getZ()))) {
                    planted = this.plantSeeds(level, villager, this.aboveFarmlandPos);
                }

                // Move on if the crop is not mature yet, or if nothing productive happened at this
                // target this tick (an empty plantable spot the villager could not plant on for lack
                // of a matching seed) — otherwise it stands and stares until canStillUse expires. The
                // harvested guard leaves the just-harvested tick alone so the next tick can still
                // replant, matching vanilla's harvest-then-replant-then-move flow.
                if (block instanceof CropBlock crop && !crop.isMaxAge(state)) {
                    this.switchTarget(level, villager, gameTime);
                } else if (ceState != null && CropRegistry.cropOf(ceState) != null && CropRegistry.matureCropOf(ceState) == null) {
                    this.switchTarget(level, villager, gameTime);
                } else if (customCrop != null && !customCrop.mature()) {
                    this.switchTarget(level, villager, gameTime);
                } else if (!harvested && !planted) {
                    this.switchTarget(level, villager, gameTime);
                }
            }
            this.timeWorkedSoFar++;
        }
    }

    private void switchTarget(ServerLevel level, Villager villager, long gameTime) {
        this.validFarmlandAroundVillager.remove(this.aboveFarmlandPos);
        this.aboveFarmlandPos = this.validFarmlandAroundVillager.isEmpty()
                ? null
                : this.validFarmlandAroundVillager.get(level.getRandom().nextInt(this.validFarmlandAroundVillager.size()));
        if (this.aboveFarmlandPos != null) {
            this.nextOkStartTime = gameTime + 20L;
            villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                    new WalkTarget(new BlockPosTracker(this.standableWalkTarget(level, this.aboveFarmlandPos, villager)), 0.5F, 1));
            villager.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, new BlockPosTracker(this.aboveFarmlandPos));
        }
    }

    private void harvestFdCrop(ServerLevel level, Villager villager, BlockPos pos, FDCrop crop) {
        if (crop.harvestMode() == FDCrop.HarvestMode.TALL) {
            // Two-block crop: harvest only the mature upper half and reset the lower half so the
            // stalk regrows; the stalk itself is never removed.
            this.breakCeBlock(level, pos);
            this.resetLowerAfterUpperHarvest(level, pos.below(), crop);
            return;
        }
        if (crop.harvestMode() == FDCrop.HarvestMode.PICK) {
            // Right-click style harvest (tomatoes): trigger the CE right-click event so the
            // configured pick loot drops and the vine resets to age 0 like a player interaction.
            this.rightClickCeBlock(level, pos);
            return;
        }
        this.breakCeBlock(level, pos);
    }

    private void resetLowerAfterUpperHarvest(ServerLevel level, BlockPos lowerPos, FDCrop crop) {
        ImmutableBlockState lower = this.ceStateAt(level, lowerPos);
        if (lower == null) {
            return;
        }
        ImmutableBlockState reset = crop.resetAfterUpperHarvest(lower);
        if (reset != lower) {
            CeBlockAccess.placeState(level.getWorld().getBlockAt(lowerPos.getX(), lowerPos.getY(), lowerPos.getZ()), reset);
        }
    }

    // Whether the block at pos is the water cell of a planted TALL crop (its lower half). The cell
    // renders and flows like water, so a villager that paths into it swims in place; the farm
    // behavior must walk to a dry neighbor instead.
    private boolean isTallWaterCell(ServerLevel level, BlockPos pos) {
        ImmutableBlockState ce = this.ceStateAt(level, pos);
        if (ce == null) {
            return false;
        }
        FDCrop crop = CropRegistry.cropOf(ce);
        boolean water = crop != null && crop.harvestMode() == FDCrop.HarvestMode.TALL && !crop.isUpperHalf(ce);
        if (water) {
            BlockState state = level.getBlockState(pos);
            VillagersDelightPlugin.debug("water-cell " + pos
                    + " block=" + state.getBlock()
                    + " fluid=" + state.getFluidState()
                    + " fullCollision=" + state.isCollisionShapeFullBlock(level, pos)
                    + " collision=" + state.getCollisionShape(level, pos));
        }
        return water;
    }

    private boolean isStandableCell(ServerLevel level, BlockPos pos, Villager villager) {
        if (!level.getBlockState(pos).getFluidState().isEmpty()) {
            return false;
        }
        if (this.isTallWaterCell(level, pos)) {
            return false;
        }
        BlockState below = level.getBlockState(pos.below());
        return below.entityCanStandOn(level, pos.below(), villager);
    }

    // Walk target for a paddy crop (its water cell or an unplanted water spot): moves the target to
    // the nearest standable horizontal neighbor so the villager harvests/plants from dry land
    // instead of wading into the water and getting stuck spinning.
    private BlockPos standableWalkTarget(ServerLevel level, BlockPos target, Villager villager) {
        if (!this.isTallWaterCell(level, target) && !this.isPlantableWater(level, target)) {
            return target;
        }
        BlockPos best = null;
        int bestDist = Integer.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                BlockPos candidate = target.offset(dx, 0, dz);
                if (this.isStandableCell(level, candidate, villager)) {
                    int dist = dx * dx + dz * dz;
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = candidate;
                    }
                }
            }
        }
        return best != null ? best : target;
    }

    // Triggers the CE right-click event (use_on) on a custom block without a player, so PICK crops
    // drop their configured pick loot and reset to age 0 exactly like a player interaction.
    private void rightClickCeBlock(ServerLevel level, BlockPos pos) {
        ImmutableBlockState ceState = this.ceStateAt(level, pos);
        if (ceState == null) {
            return;
        }
        try {
            net.momirealms.craftengine.core.world.World ceWorld =
                    net.momirealms.craftengine.bukkit.api.BukkitAdaptor.adapt(level.getWorld());
            WorldPosition position = new WorldPosition(ceWorld,
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            PlayerOptionalContext context = PlayerOptionalContext.of(null, ContextHolder.builder()
                    .withParameter(DirectContextParameters.BLOCK,
                            new net.momirealms.craftengine.bukkit.world.BukkitExistingBlock(
                                    level.getWorld().getBlockAt(pos.getX(), pos.getY(), pos.getZ())))
                    .withParameter(DirectContextParameters.POSITION, position)
                    .withParameter(DirectContextParameters.CUSTOM_BLOCK_STATE, ceState));
            ceState.owner().value().execute(context, EventTrigger.RIGHT_CLICK);
        } catch (Throwable t) {
            VillagersDelightPlugin.debug("harvest: CE right-click event failed at " + pos + ": " + t);
        }
    }

    private void breakCeBlock(ServerLevel level, BlockPos pos) {
        // FD crops drop through CraftEngine's "on break" event functions (drop_loot), not through
        // block.loot(). Trigger the BREAK event with a player-less context so the configured loot
        // drops; player-dependent entries (fortune, has_player) fall back to their base drops.
        ImmutableBlockState ceState = this.ceStateAt(level, pos);
        if (ceState != null) {
            try {
                net.momirealms.craftengine.core.world.World ceWorld =
                        net.momirealms.craftengine.bukkit.api.BukkitAdaptor.adapt(level.getWorld());
                WorldPosition position = new WorldPosition(ceWorld,
                        pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                PlayerOptionalContext context = PlayerOptionalContext.of(null, ContextHolder.builder()
                        .withParameter(DirectContextParameters.BLOCK,
                                new net.momirealms.craftengine.bukkit.world.BukkitExistingBlock(
                                        level.getWorld().getBlockAt(pos.getX(), pos.getY(), pos.getZ())))
                        .withParameter(DirectContextParameters.POSITION, position)
                        .withParameter(DirectContextParameters.CUSTOM_BLOCK_STATE, ceState));
                ceState.owner().value().execute(context, EventTrigger.BREAK);
            } catch (Throwable t) {
                VillagersDelightPlugin.debug("harvest: CE break event drop failed at " + pos + ": " + t);
            }
        }
        // Break particles and sound. The BLOCK_BREAK_EFFECT level event drives the client break
        // feedback; CraftEngine intercepts it and maps the sound to the custom block's own break
        // sound, so a single call covers both effects.
        BlockState state = level.getBlockState(pos);
        level.levelEvent(net.minecraft.world.level.block.LevelEvent.PARTICLES_DESTROY_BLOCK, pos, Block.getId(state));
        CeBlockAccess.removeBlock(level.getWorld().getBlockAt(pos.getX(), pos.getY(), pos.getZ()));
    }

    private boolean plantSeeds(ServerLevel level, Villager villager, BlockPos pos) {
        SimpleContainer inventory = villager.getInventory();
        // Prefer vanilla-compatible seeds when both vanilla and addon seeds are available.
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);
                if (stack.isEmpty() || (pass == 0) != this.isVanillaSeed(stack)) {
                    continue;
                }
                FDCrop fdCrop = configuredSeed(stack);
                if (fdCrop != null && canPlantHere(level, pos, fdCrop)
                        && (!this.isVanillaSeed(stack) || !this.isVanillaFarmland(level, pos))) {
                    if (!this.plantFdCrop(level, villager, pos, fdCrop)) {
                        return false;
                    }
                    VillagersDelightPlugin.debug("plant: " + fdCrop.blockId() + " at " + pos + " from " + stack);
                    stack.shrink(1);
                    return true;
                }
                String customCropId = CustomCropsCompat.cropId(CraftItemStack.asBukkitCopy(stack));
                Location customCropLocation = new Location(level.getWorld(), pos.getX(), pos.getY(), pos.getZ());
                if (customCropId != null && CustomCropsCompat.canPlantAt(customCropLocation, customCropId)) {
                    if (!CraftEventFactory.callEntityChangeBlockEvent(villager, pos, level.getBlockState(pos))) {
                        return false;
                    }
                    if (!CustomCropsCompat.place(customCropLocation, customCropId)) {
                        return false;
                    }
                    level.gameEvent(GameEvent.BLOCK_PLACE, pos, GameEvent.Context.of(villager, level.getBlockState(pos)));
                    stack.shrink(1);
                    return true;
                }
                if (customCropId == null && this.isVanillaSeed(stack) && this.isVanillaPlantingSpot(level, pos)
                        && stack.getItem() instanceof BlockItem blockItem) {
                    BlockState toPlace = blockItem.getBlock().defaultBlockState();
                    if (!CraftEventFactory.callEntityChangeBlockEvent(villager, pos, toPlace)) {
                        return false;
                    }
                    level.setBlockAndUpdate(pos, toPlace);
                    level.gameEvent(GameEvent.BLOCK_PLACE, pos, GameEvent.Context.of(villager, toPlace));
                    stack.shrink(1);
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isVanillaSeed(ItemStack stack) {
        return CeItemAccess.customItemId(CraftItemStack.asBukkitCopy(stack)) == null
                && stack.is(ItemTags.VILLAGER_PLANTABLE_SEEDS) && stack.getItem() instanceof BlockItem;
    }

    private boolean isVanillaPlantingSpot(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).isAir() && level.getBlockState(pos).getFluidState().isEmpty()
                && (this.isVanillaFarmland(level, pos)
                || this.isCePlantableSoil(level, pos.below()));
    }

    private boolean isVanillaFarmland(ServerLevel level, BlockPos pos) {
        BlockPos belowPos = pos.below();
        return this.ceStateAt(level, belowPos) == null
                && level.getBlockState(belowPos).getBlock() instanceof FarmlandBlock;
    }

    private FDCrop configuredSeed(ItemStack stack) {
        Key custom = CeItemAccess.customItemId(CraftItemStack.asBukkitCopy(stack));
        if (custom != null) {
            return CropRegistry.cropBySeed(custom);
        }
        org.bukkit.inventory.ItemStack bukkit = CraftItemStack.asBukkitCopy(stack);
        if (bukkit.getType().isAir()) {
            return null;
        }
        return CropRegistry.cropBySeed(Key.of(bukkit.getType().getKey().toString()));
    }

    private boolean canPlantHere(ServerLevel level, BlockPos pos, FDCrop crop) {
        if (this.ceStateAt(level, pos) != null) {
            return false;
        }
        if (crop.water() != null) {
            return this.isPlantableWater(level, pos, crop);
        }
        // Without a configured water id the crop plants on dry soil only.
        if (!level.getBlockState(pos).isAir() || !level.getBlockState(pos).getFluidState().isEmpty()) {
            return false;
        }
        ImmutableBlockState ceBelow = this.ceStateAt(level, pos.below());
        if (ceBelow != null) {
            return CropRegistry.canPlantOn(crop, ceBelow);
        }
        return level.getBlockState(pos.below()).getBlock() instanceof FarmlandBlock;
    }

    // Whether a position is a planting spot for any crop with a configured water id: a fluid
    // matching the crop's water above an accepted soil bed.
    private boolean isPlantableWater(ServerLevel level, BlockPos pos) {
        for (FDCrop crop : CropRegistry.waterCrops()) {
            if (this.isPlantableWater(level, pos, crop)) {
                return true;
            }
        }
        return false;
    }

    private boolean isPlantableWater(ServerLevel level, BlockPos pos, FDCrop crop) {
        BlockState target = level.getBlockState(pos);
        if (!(target.getBlock() instanceof LiquidBlock) || this.ceStateAt(level, pos) != null) {
            return false;
        }
        FluidState fluidState = target.getFluidState();
        if (fluidState.isEmpty() || !crop.matchesWater(fluidKeyOf(fluidState))) {
            return false;
        }
        if (!fluidState.isSource()) {
            return false;
        }
        Key belowId = Key.of(level.getWorld().getBlockAt(pos.getX(), pos.getY() - 1, pos.getZ())
                .getType().getKey().toString());
        if (crop.soils().contains(belowId)) {
            return true;
        }
        ImmutableBlockState ceBelow = this.ceStateAt(level, pos.below());
        return ceBelow != null && crop.soils().contains(ceBelow.owner().value().id());
    }

    // Registry id of the fluid at a position, e.g. minecraft:water.
    private static Key fluidKeyOf(FluidState fluidState) {
        return Key.of(String.valueOf(BuiltInRegistries.FLUID.getKey(fluidState.getType())));
    }

    // Reads the CraftEngine custom block state at a position. The vanilla level state only holds
    // the base block, so custom crops and rich soil are resolved through the CE proxy.
    static ImmutableBlockState ceStateAt(ServerLevel level, BlockPos pos) {
        try {
            Object worldServer = CraftWorldProxy.INSTANCE.getWorld(level.getWorld());
            Object nmsState = BlockGetterProxy.INSTANCE.getBlockState(worldServer, BlockPosProxy.INSTANCE.newInstance(pos.getX(), pos.getY(), pos.getZ()));
            return BlockStateUtils.getOptionalCustomBlockState(nmsState).orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean isCePlantableSoil(ServerLevel level, BlockPos pos) {
        ImmutableBlockState ce = this.ceStateAt(level, pos);
        return ce != null && CropRegistry.isPlantableSoil(ce);
    }

    private boolean plantFdCrop(ServerLevel level, Villager villager, BlockPos pos, FDCrop fdCrop) {
        if (!this.canPlantHere(level, pos, fdCrop)) {
            return false;
        }
        Key plantBlock = fdCrop.plantBlock();
        Object placeState = CeBlockAccess.placeStateMinecraft(plantBlock);
        if (placeState instanceof BlockState blockState
                && CraftEventFactory.callEntityChangeBlockEvent(villager, pos, blockState)) {
            Location location = new Location(level.getWorld(), pos.getX(), pos.getY(), pos.getZ());
            CeBlockAccess.placeCrop(location, plantBlock, 0, true);
            level.gameEvent(GameEvent.BLOCK_PLACE, pos, GameEvent.Context.of(villager, level.getBlockState(pos)));
            return true;
        }
        return false;
    }

    @Override
    protected boolean canStillUse(ServerLevel level, Villager villager, long gameTime) {
        return this.timeWorkedSoFar < 200;
    }
}
