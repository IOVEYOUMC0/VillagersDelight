package com.huidu.villagersdelight.core;

import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.util.Key;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

// Describes one recognized custom crop: its CE block id, the age property and its max value,
// the seed item villagers use to plant it (CE custom or vanilla), and the CE soils it may be
// planted on (vanilla farmland is always allowed and not listed here).
public final class FDCrop {

    // How villagers harvest this crop.
    // BREAK - break the block and let CE drop its loot (default).
    // PICK - harvest like right-click (e.g. tomatoes): break, drop loot, then re-plant at
    //        age 0 so the plant keeps producing.
    // TALL - two-block crop (e.g. rice): the lower half stays as the supporting stalk and only
    //        the mature upper half is harvested. Planted on a fluid whose bed is an accepted soil.
    public enum HarvestMode {
        BREAK, PICK, TALL
    }


    private final Key blockId;
    private final int ageMax;
    private final boolean hasHalf;
    @Nullable
    private final Key seedItem;
    private final Set<Key> soils;
    private final HarvestMode harvestMode;
    @Nullable
    private final Key water;
    @Nullable
    private final Key plantBlock;

    FDCrop(Key blockId, int ageMax, boolean hasHalf, @Nullable Key seedItem, Set<Key> soils, HarvestMode harvestMode, @Nullable Key water, @Nullable Key plantBlock) {
        this.blockId = blockId;
        this.ageMax = ageMax;
        this.hasHalf = hasHalf;
        this.seedItem = seedItem;
        this.soils = soils;
        this.harvestMode = harvestMode;
        this.water = water;
        this.plantBlock = plantBlock;
    }

    public Key blockId() {
        return this.blockId;
    }

    public int ageMax() {
        return this.ageMax;
    }

    @Nullable
    public Key seedItem() {
        return this.seedItem;
    }

    public Set<Key> soils() {
        return this.soils;
    }

    public HarvestMode harvestMode() {
        return this.harvestMode;
    }

    // Block placed when the villager plants this crop at age 0; defaults to the crop block id.
    public Key plantBlock() {
        return this.plantBlock != null ? this.plantBlock : this.blockId;
    }

    // Whether the given fluid id is accepted as the planting bed for this crop. A crop only
    // plants on fluid when it configured a water id; unconfigured crops never match fluid. The
    // The configured id must match exactly; callers additionally require a source fluid state.
    public boolean matchesWater(@Nullable Key fluidId) {
        if (this.water == null || fluidId == null) {
            return false;
        }
        return this.water.equals(fluidId);
    }

    // The configured planting fluid (null when this crop plants on dry soil only).
    @Nullable
    public Key water() {
        return this.water;
    }

    public int ageOf(ImmutableBlockState state) {
        Property<Integer> age = ageProperty(state);
        return age == null ? 0 : state.get(age);
    }

    @Nullable
    private static Property<Integer> ageProperty(ImmutableBlockState state) {
        for (String name : new String[]{"age", "growth", "stage"}) {
            Property<Integer> property = BlockBehaviorFactory.getOptionalProperty(state.owner().value(), name, Integer.class);
            if (property != null) {
                return property;
            }
        }
        return null;
    }

    public boolean isMature(ImmutableBlockState state) {
        if (this.harvestMode == HarvestMode.TALL) {
            // Two-block crops (rice) are harvested by their mature upper half only; the lower half
            // is the supporting stalk and stays in place (see resetAfterUpperHarvest).
            return this.isUpperHalf(state) && this.ageOf(state) >= this.ageMax - 1;
        }
        return this.ageOf(state) >= this.ageMax;
    }

    public boolean isUpperHalf(ImmutableBlockState state) {
        if (state == null || !this.hasHalf) {
            return false;
        }
        Property<?> half = state.getProperty("half");
        if (half == null) {
            return false;
        }
        Object value = state.get(half);
        return value != null && String.valueOf(value).equalsIgnoreCase("upper");
    }

    // After harvesting the upper half, reset the stalk below maturity and force its lower-half state
    // so it can regrow the upper crop.
    public ImmutableBlockState resetAfterUpperHarvest(ImmutableBlockState lower) {
        if (this.harvestMode != HarvestMode.TALL || lower == null) {
            return lower;
        }
        ImmutableBlockState reset = lower;
        Property<Integer> age = ageProperty(lower);
        if (age != null) {
            reset = reset.with(age, Math.max(0, this.ageMax - 1));
        }
        if (this.hasHalf) {
            try {
                Property<?> half = lower.getProperty("half");
                if (half != null) {
                    Object lowerValue = findValue(half, "lower");
                    if (lowerValue != null) {
                        reset = withRaw(reset, half, lowerValue);
                    }
                }
            } catch (IllegalArgumentException ex) {
                // Half property missing on this variant; leave the lower half unchanged.
            }
        }
        return reset;
    }

    private static ImmutableBlockState withRaw(ImmutableBlockState state, Property<?> property, Object value) {
        return ImmutableBlockState.with(state, property, value);
    }

    private static Object findValue(Property<?> property, String name) {
        for (Object value : property.possibleValues()) {
            if (value != null && String.valueOf(value).equalsIgnoreCase(name)) {
                return value;
            }
        }
        return null;
    }
}
