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
// block with an "age", "growth", or "stage" property. The lookup paths used by the NMS layer are O(1): one
// instanceof against DelegatingBlockState plus a map get.
public final class CropRegistry {

    private static volatile CropRegistry instance;

    private final Map<Key, FDCrop> crops;
    private final Set<Key> extraSoils;
    private final Map<Key, List<ItemStack>> harvestDrops;
    // Derived indexes. The villager farm scan asks these questions once per scanned cell, and every
    // input is fixed for the lifetime of a snapshot, so they are built once instead of scanned linearly.
    private final Map<Key, FDCrop> cropsBySeed;
    private final List<FDCrop> waterCrops;
    private final Set<Key> plantableSoils;

    private CropRegistry(Map<Key, FDCrop> crops, Set<Key> extraSoils, Map<Key, List<ItemStack>> harvestDrops,
                         Map<Key, FDCrop> cropsBySeed, List<FDCrop> waterCrops, Set<Key> plantableSoils) {
        this.crops = crops;
        this.extraSoils = extraSoils;
        this.harvestDrops = harvestDrops;
        this.cropsBySeed = cropsBySeed;
        this.waterCrops = waterCrops;
        this.plantableSoils = plantableSoils;
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
        return reg.cropsBySeed.get(seedItem);
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
        return reg.plantableSoils.contains(belowState.owner().value().id());
    }

    // All configured crops that plant on a fluid (water configured). Used to match a fluid spot
    // that any of them can plant on; each crop carries its own fluid and soil rules.
    public static List<FDCrop> waterCrops() {
        CropRegistry reg = instance;
        if (reg == null) {
            return List.of();
        }
        return reg.waterCrops;
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
            Property<?> ageProperty = findAgeProperty(definition);
            if (ageProperty == null) {
                VillagersDelightPlugin.debug("crop " + id + " skipped: no numeric age/growth/stage property");
                continue;
            }
            int ageMax = maxOf(ageProperty);
            boolean hasHalf = definition.hasProperty("half");
            Set<Key> soils = new HashSet<>(entry.getValue().soils());
            soils.addAll(cfg.extraSoils());
            crops.put(id, new FDCrop(id, ageMax, hasHalf, entry.getValue().seed(), soils, parseHarvestMode(entry.getValue().harvestMode()), entry.getValue().water(), entry.getValue().plantBlock()));
            VillagersDelightPlugin.debug("crop " + id + " registered (age max " + ageMax + ", seed " + entry.getValue().seed() + ", soils " + soils + ")");
        }
        Map<Key, FDCrop> bySeed = new HashMap<>();
        List<FDCrop> water = new ArrayList<>();
        Set<Key> soilIndex = new HashSet<>(cfg.extraSoils());
        for (FDCrop crop : crops.values()) {
            if (crop.seedItem() != null) {
                bySeed.putIfAbsent(crop.seedItem(), crop);
            }
            if (crop.water() != null) {
                water.add(crop);
            }
            soilIndex.addAll(crop.soils());
        }
        return new CropRegistry(crops, cfg.extraSoils(), cfg.harvestDrops(),
                Map.copyOf(bySeed), List.copyOf(water), Set.copyOf(soilIndex));
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

    @Nullable
    private static Property<?> findAgeProperty(BlockDefinition definition) {
        for (String name : new String[]{"age", "growth", "stage"}) {
            if (!definition.hasProperty(name)) {
                continue;
            }
            Property<?> property = definition.getProperty(name);
            if (property != null && maxOf(property) > 0) {
                return property;
            }
        }
        return null;
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
