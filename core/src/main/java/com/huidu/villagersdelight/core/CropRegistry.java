package com.huidu.villagersdelight.core;

import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.DelegatingBlockState;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Snapshot of the configured CE crops the villager AI should harvest and plant. Built from the
// explicit crop list in config.yml (no auto-detection); each entry must resolve to a loaded CE
// block with an "age" property. The lookup paths used by the NMS layer are O(1): one
// instanceof against DelegatingBlockState plus a map get.
public final class CropRegistry {

    private static volatile CropRegistry instance;

    private final Map<Key, FDCrop> crops;
    private final Set<Key> extraSoils;
    private final Map<Key, List<ItemStack>> harvestDrops;

    private CropRegistry(Map<Key, FDCrop> crops, Set<Key> extraSoils, Map<Key, List<ItemStack>> harvestDrops) {
        this.crops = crops;
        this.extraSoils = extraSoils;
        this.harvestDrops = harvestDrops;
    }

    public static void reload(VillagersDelightPlugin plugin) {
        instance = build(plugin.config());
    }

    @Nullable
    public static CropRegistry instance() {
        return instance;
    }

    // Fast path for the NMS layer: resolves the crop behind a raw NMS block state, or null.
    @Nullable
    public static FDCrop cropOf(ImmutableBlockState state) {
        CropRegistry reg = instance;
        if (reg == null || state == null) {
            return null;
        }
        return reg.crops.get(state.owner().value().id());
    }

    @Nullable
    public static FDCrop cropById(Key blockId) {
        CropRegistry reg = instance;
        return reg == null ? null : reg.crops.get(blockId);
    }

    // Returns the crop behind a raw NMS block state only if it is mature (harvestable).
    @Nullable
    public static FDCrop matureCropOf(ImmutableBlockState state) {
        CropRegistry reg = instance;
        if (reg == null || state == null) {
            return null;
        }
        FDCrop crop = reg.crops.get(state.owner().value().id());
        return crop != null && crop.isMature(state) ? crop : null;
    }

    // Finds the crop a seed item (CE custom or vanilla) plants, or null.
    @Nullable
    public static FDCrop cropBySeed(Key seedItem) {
        CropRegistry reg = instance;
        if (reg == null || seedItem == null) {
            return null;
        }
        for (FDCrop crop : reg.crops.values()) {
            if (seedItem.equals(crop.seedItem())) {
                return crop;
            }
        }
        return null;
    }

    // Whether the CE soil block below a planting spot is allowed for the given crop. Vanilla
    // farmland is handled by the NMS layer itself and is always allowed.
    public static boolean canPlantOn(FDCrop crop, ImmutableBlockState belowState) {
        CropRegistry reg = instance;
        if (reg == null || crop == null || belowState == null) {
            return false;
        }
        return crop.soils().contains(belowState.owner().value().id());
    }

    // Whether the CE soil below a planting spot can host at least one configured crop. Used for
    // selecting planting targets in the villager scan.
    public static boolean isPlantableSoil(ImmutableBlockState belowState) {
        CropRegistry reg = instance;
        if (reg == null || belowState == null) {
            return false;
        }
        Key id = belowState.owner().value().id();
        if (reg.extraSoils.contains(id)) {
            return true;
        }
        for (FDCrop crop : reg.crops.values()) {
            if (crop.soils().contains(id)) {
                return true;
            }
        }
        return false;
    }

    // All configured crops that plant on a fluid (water configured). Used to match a fluid spot
    // that any of them can plant on; each crop carries its own fluid and soil rules.
    public static List<FDCrop> waterCrops() {
        CropRegistry reg = instance;
        if (reg == null) {
            return List.of();
        }
        List<FDCrop> result = new ArrayList<>();
        for (FDCrop crop : reg.crops.values()) {
            if (crop.water() != null) {
                result.add(crop);
            }
        }
        return result;
    }

        @Nullable
    public List<ItemStack> dropsFor(Key cropId) {
        return this.harvestDrops.get(cropId);
    }

    public int cropCount() {
        return this.crops.size();
    }

    public List<FDCrop> allCrops() {
        return new ArrayList<>(this.crops.values());
    }

    private static CropRegistry build(VillagersDelightConfig cfg) {
        Map<Key, FDCrop> crops = new HashMap<>();
        for (Map.Entry<Key, VillagersDelightConfig.CropEntry> entry : cfg.crops().entrySet()) {
            Key id = entry.getKey();
            if (cfg.isCropDisabled(id)) {
                continue;
            }
            BlockDefinition definition = CraftEngineBlocks.byId(id);
            if (definition == null) {
                VillagersDelightPlugin.debug("crop " + id + " skipped: CE block not loaded");
                continue;
            }
            if (!definition.hasProperty("age")) {
                VillagersDelightPlugin.debug("crop " + id + " skipped: no age property");
                continue;
            }
            Property<?> ageProperty = definition.getProperty("age");
            int ageMax = maxOf(ageProperty);
            boolean hasHalf = definition.hasProperty("half");
            Set<Key> soils = new HashSet<>(entry.getValue().soils());
            soils.addAll(cfg.extraSoils());
            crops.put(id, new FDCrop(id, ageMax, hasHalf, entry.getValue().seed(), soils, parseHarvestMode(entry.getValue().harvestMode()), entry.getValue().water(), entry.getValue().plantBlock()));
            VillagersDelightPlugin.debug("crop " + id + " registered (age max " + ageMax + ", seed " + entry.getValue().seed() + ", soils " + soils + ")");
        }
        return new CropRegistry(crops, cfg.extraSoils(), cfg.harvestDrops());
    }

    private static int maxOf(Property<?> property) {
        int max = 0;
        for (Object value : property.possibleValues()) {
            if (value instanceof Number number && number.intValue() > max) {
                max = number.intValue();
            }
        }
        return max;
    }

    private static FDCrop.HarvestMode parseHarvestMode(String raw) {
        if (raw == null) {
            return FDCrop.HarvestMode.BREAK;
        }
        String mode = raw.trim().toLowerCase();
        if (mode.equals("pick")) {
            return FDCrop.HarvestMode.PICK;
        }
        if (mode.equals("tall")) {
            return FDCrop.HarvestMode.TALL;
        }
        return FDCrop.HarvestMode.BREAK;
    }
}
