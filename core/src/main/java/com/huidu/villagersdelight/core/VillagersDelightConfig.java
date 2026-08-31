package com.huidu.villagersdelight.core;

import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.item.BukkitItemDefinition;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Configuration snapshot loaded from config.yml on enable. Crops are an explicit list; every CE
// block the villagers may harvest and plant must be declared here with its seed and optional
// extra soils (besides vanilla farmland and the global extra-soils).
public final class VillagersDelightConfig {

    // One configured crop: the seed item (CE custom or vanilla), any crop-specific soils, the
    // harvest mode (break / pick / tall), the fluid it may plant on (null = dry soil only),
    // and the block placed when planting (null = crop).
    public record CropEntry(Key seed, List<Key> soils, String harvestMode, @Nullable Key water, @Nullable Key plantBlock) {
    }

    private final Map<Key, CropEntry> cropEntries;
    private final Set<Key> disabledCrops;
    private final Set<Key> extraSoils;
    private final Map<Key, List<ItemStack>> harvestDrops;
    private final long rescanSeconds;
    private final boolean debug;
    private final boolean pickupEnabled;
    private final List<Key> pickupCrops;
    private final List<Key> pickupFoods;
    private final boolean shareEnabled;
    private final List<Key> shareItems;
    private final List<Key> compostItems;
    private final boolean customCropsEnabled;

    private VillagersDelightConfig(
            Map<Key, CropEntry> cropEntries,
            Set<Key> disabledCrops,
            Set<Key> extraSoils,
            Map<Key, List<ItemStack>> harvestDrops,
            long rescanSeconds,
            boolean pickupEnabled,
            List<Key> pickupCrops,
            List<Key> pickupFoods,
            boolean shareEnabled,
            List<Key> shareItems,
            List<Key> compostItems,
            boolean debug,
            boolean customCropsEnabled
    ) {
        this.cropEntries = cropEntries;
        this.disabledCrops = disabledCrops;
        this.extraSoils = extraSoils;
        this.harvestDrops = harvestDrops;
        this.rescanSeconds = rescanSeconds;
        this.pickupEnabled = pickupEnabled;
        this.pickupCrops = pickupCrops;
        this.pickupFoods = pickupFoods;
        this.shareEnabled = shareEnabled;
        this.shareItems = shareItems;
        this.compostItems = compostItems;
        this.debug = debug;
        this.customCropsEnabled = customCropsEnabled;
    }

    public static VillagersDelightConfig load(FileConfiguration yaml) {
        Map<Key, CropEntry> cropEntries = new HashMap<>();
        ConfigurationSection cropsSection = yaml.getConfigurationSection("crops");
        if (cropsSection != null) {
            for (String cropId : cropsSection.getKeys(false)) {
                String seed = cropsSection.getString(cropId + ".seed");
                if (seed == null) {
                    continue;
                }
                List<Key> soils = new ArrayList<>();
                for (String soil : cropsSection.getStringList(cropId + ".soils")) {
                    soils.add(Key.of(soil));
                }
                String harvestMode = cropsSection.getString(cropId + ".harvest-mode", "break");
                String water = cropsSection.getString(cropId + ".water");
                String plantBlock = cropsSection.getString(cropId + ".plant-block");
                cropEntries.put(Key.of(cropId), new CropEntry(Key.of(seed), soils, harvestMode, water == null ? null : Key.of(water), plantBlock == null ? null : Key.of(plantBlock)));
            }
        }

        Set<Key> disabled = new HashSet<>();
        for (String id : yaml.getStringList("disabled-crops")) {
            disabled.add(Key.of(id));
        }

        Set<Key> extraSoils = new HashSet<>();
        for (String id : yaml.getStringList("extra-soils")) {
            extraSoils.add(Key.of(id));
        }

        Map<Key, List<ItemStack>> drops = new HashMap<>();
        ConfigurationSection dropsSection = yaml.getConfigurationSection("harvest-drops");
        if (dropsSection != null) {
            for (String cropId : dropsSection.getKeys(false)) {
                drops.put(Key.of(cropId), parseDrops(dropsSection.getStringList(cropId)));
            }
        }

        List<Key> pickupCrops = new ArrayList<>();
        for (String id : yaml.getStringList("pickup.crops")) {
            pickupCrops.add(Key.of(id));
        }

        List<Key> pickupFoods = new ArrayList<>();
        for (String id : yaml.getStringList("pickup.foods")) {
            pickupFoods.add(Key.of(id));
        }

        List<Key> shareItems = new ArrayList<>();
        for (String id : yaml.getStringList("share-items.items")) {
            shareItems.add(Key.of(id));
        }

        List<Key> compostItems = new ArrayList<>();
        for (String id : yaml.getStringList("compost-items")) {
            compostItems.add(Key.of(id));
        }

        return new VillagersDelightConfig(
                cropEntries,
                disabled,
                extraSoils,
                drops,
                yaml.getLong("rescan-seconds", 30L),
                yaml.getBoolean("pickup.enabled", false),
                pickupCrops,
                pickupFoods,
                yaml.getBoolean("share-items.enabled", true),
                shareItems,
                compostItems,
                yaml.getBoolean("debug", false),
                yaml.getBoolean("custom-crops.enabled", true)
        );
    }

    // Parses entries of the form item-id:count. A two-part id (namespace:path) resolves as a
    // CE custom item first, then falls back to a vanilla Bukkit material.
    private static List<ItemStack> parseDrops(List<String> entries) {
        List<ItemStack> result = new ArrayList<>(entries.size());
        for (String entry : entries) {
            String[] parts = entry.split(":");
            if (parts.length < 2) {
                continue;
            }
            int count = parts.length > 2 ? parseCount(parts[2]) : 1;
            Key customId = Key.of(parts[0] + ":" + parts[1]);
            BukkitItemDefinition definition = CraftEngineItems.byId(customId);
            if (definition != null) {
                ItemStack stack = definition.buildBukkitItem();
                stack.setAmount(Math.max(1, count));
                result.add(stack);
                continue;
            }
            Material material = Material.matchMaterial(parts[1]);
            if (material != null) {
                result.add(new ItemStack(material, Math.max(1, count)));
            }
        }
        return result;
    }

    private static int parseCount(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    public Map<Key, CropEntry> crops() {
        return this.cropEntries;
    }

    public boolean isCropDisabled(Key id) {
        return this.disabledCrops.contains(id);
    }

    @Nullable
    public Key seedFor(Key cropId) {
        CropEntry entry = this.cropEntries.get(cropId);
        return entry == null ? null : entry.seed();
    }

    public Set<Key> extraSoils() {
        return this.extraSoils;
    }

    public Map<Key, List<ItemStack>> harvestDrops() {
        return this.harvestDrops;
    }

    public long rescanSeconds() {
        return this.rescanSeconds;
    }

    public boolean debug() {
        return this.debug;
    }

    public boolean pickupEnabled() {
        return this.pickupEnabled;
    }

    public List<Key> pickupCrops() {
        return this.pickupCrops;
    }

    // Foods villagers collect: their base materials enter the villager_picks_up tag (so
    // wantsToPickUp accepts them) and count toward breeding food points.
    public List<Key> pickupFoods() {
        return this.pickupFoods;
    }

    // Whether villagers throw surplus configured items at nearby villagers (share-items).
    public boolean shareEnabled() {
        return this.shareEnabled;
    }

    // Items villagers share with nearby villagers; each entry is a CE custom or vanilla item id.
    public List<Key> shareItems() {
        return this.shareItems;
    }

    // Items villagers may compost into bone meal; each entry is a CE custom or vanilla item id.
    public List<Key> compostItems() {
        return this.compostItems;
    }

    public boolean customCropsEnabled() {
        return this.customCropsEnabled;
    }
}
