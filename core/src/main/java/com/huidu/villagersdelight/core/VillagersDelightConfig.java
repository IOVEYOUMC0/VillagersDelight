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
import java.util.regex.Pattern;

// Configuration snapshot loaded from config.yml on enable. Crops are an explicit list; every CE
// block the villagers may harvest and plant must be declared here with its seed and optional
// extra soils (besides vanilla farmland and the global extra-soils).
public final class VillagersDelightConfig {

    private static final Pattern ITEM_ID = Pattern.compile("^[a-z0-9_.-]+:[a-z0-9/._-]+$");

    // One configured crop: the seed item (CE custom or vanilla), any crop-specific soils, the
    // harvest mode (break / pick / tall), the fluid it may plant on (null = dry soil only),
    // and the block placed when planting (null = crop).
    public record CropEntry(Key seed, List<Key> soils, String harvestMode, @Nullable Key water, @Nullable Key plantBlock) {
    }

    public record BehaviorSettings(boolean foodEnabled, double foodCheckChance, boolean protectCustomSeeds,
                                   int compostMaxItemsPerWork, int compostMinimumKeptPerItem, double compostDefaultChance,
                                   double sharingChance, double sharingRange, double sharingKeepFraction,
                                   int bonemealRetryDelayTicks, int bonemealWorkDurationTicks,
                                   int farmRetargetDelayTicks, int farmStopCooldownTicks, int farmWorkDurationTicks) {
    }

    private static final BehaviorSettings DEFAULT_BEHAVIOR = new BehaviorSettings(
            true, 0.05, true, 20, 32, 0.3, 0.1, 6.0, 0.5, 40, 80, 20, 40, 200);

    private final Map<Key, CropEntry> cropEntries;
    private final Set<Key> disabledCrops;
    private final Set<Key> extraSoils;
    private final Map<Key, List<ItemStack>> harvestDrops;
    private final boolean debug;
    private final boolean pickupEnabled;
    private final List<Key> pickupCrops;
    private final List<Key> pickupFoods;
    private final boolean shareEnabled;
    private final List<Key> shareItems;
    private final List<Key> compostItems;
    private final boolean customCropsEnabled;
    private final BehaviorSettings behaviorSettings;

    private VillagersDelightConfig(
            Map<Key, CropEntry> cropEntries,
            Set<Key> disabledCrops,
            Set<Key> extraSoils,
            Map<Key, List<ItemStack>> harvestDrops,
            boolean pickupEnabled,
            List<Key> pickupCrops,
            List<Key> pickupFoods,
            boolean shareEnabled,
            List<Key> shareItems,
            List<Key> compostItems,
            boolean debug,
            boolean customCropsEnabled,
            BehaviorSettings behaviorSettings
    ) {
        this.cropEntries = cropEntries;
        this.disabledCrops = disabledCrops;
        this.extraSoils = extraSoils;
        this.harvestDrops = harvestDrops;
        this.pickupEnabled = pickupEnabled;
        this.pickupCrops = pickupCrops;
        this.pickupFoods = pickupFoods;
        this.shareEnabled = shareEnabled;
        this.shareItems = shareItems;
        this.compostItems = compostItems;
        this.debug = debug;
        this.customCropsEnabled = customCropsEnabled;
        this.behaviorSettings = behaviorSettings;
    }

    public static VillagersDelightConfig load(FileConfiguration yaml) {
        Map<Key, CropEntry> cropEntries = new HashMap<>();
        ConfigurationSection cropsSection = yaml.getConfigurationSection("crops");
        if (cropsSection != null) {
            for (String cropId : cropsSection.getKeys(false)) {
                String seed = cropsSection.getString(cropId + ".seed");
                if (seed == null) {
                    throw new IllegalArgumentException("crops." + cropId + ".seed is required");
                }
                List<Key> soils = new ArrayList<>();
                for (String soil : cropsSection.getStringList(cropId + ".soils")) {
                    soils.add(parseKey(soil, "crops." + cropId + ".soils"));
                }
                String harvestMode = cropsSection.getString(cropId + ".harvest-mode", "break");
                if (!Set.of("break", "pick", "tall").contains(harvestMode)) {
                    throw new IllegalArgumentException("crops." + cropId + ".harvest-mode must be break, pick or tall");
                }
                String water = cropsSection.getString(cropId + ".water");
                String plantBlock = cropsSection.getString(cropId + ".plant-block");
                cropEntries.put(parseKey(cropId, "crops"), new CropEntry(parseKey(seed, "crops." + cropId + ".seed"), soils,
                        harvestMode, water == null ? null : parseKey(water, "crops." + cropId + ".water"),
                        plantBlock == null ? null : parseKey(plantBlock, "crops." + cropId + ".plant-block")));
            }
        }

        Set<Key> disabled = new HashSet<>();
        for (String id : yaml.getStringList("disabled-crops")) {
            disabled.add(parseKey(id, "disabled-crops"));
        }

        Set<Key> extraSoils = new HashSet<>();
        for (String id : yaml.getStringList("extra-soils")) {
            extraSoils.add(parseKey(id, "extra-soils"));
        }

        Map<Key, List<ItemStack>> drops = new HashMap<>();
        ConfigurationSection dropsSection = yaml.getConfigurationSection("harvest-drops");
        if (dropsSection != null) {
            for (String cropId : dropsSection.getKeys(false)) {
                drops.put(parseKey(cropId, "harvest-drops"), parseDrops(dropsSection.getStringList(cropId), "harvest-drops." + cropId));
            }
        }

        List<Key> pickupCrops = new ArrayList<>();
        for (String id : yaml.getStringList("pickup.crops")) {
            pickupCrops.add(parseKey(id, "pickup.crops"));
        }

        List<Key> pickupFoods = new ArrayList<>();
        for (String id : yaml.getStringList("pickup.foods")) {
            pickupFoods.add(parseKey(id, "pickup.foods"));
        }

        List<Key> shareItems = new ArrayList<>();
        for (String id : yaml.getStringList("share-items.items")) {
            shareItems.add(parseKey(id, "share-items.items"));
        }

        List<Key> compostItems = new ArrayList<>();
        for (String id : yaml.getStringList("compost-items")) {
            compostItems.add(parseKey(id, "compost-items"));
        }

        BehaviorSettings behavior = new BehaviorSettings(
                yaml.getBoolean("villager-ai.food.enabled", DEFAULT_BEHAVIOR.foodEnabled()),
                probability(yaml, "villager-ai.food.check-chance", DEFAULT_BEHAVIOR.foodCheckChance()),
                yaml.getBoolean("villager-ai.food.protect-custom-seeds", DEFAULT_BEHAVIOR.protectCustomSeeds()),
                positiveInt(yaml, "villager-ai.compost.max-items-per-work", DEFAULT_BEHAVIOR.compostMaxItemsPerWork()),
                nonNegativeInt(yaml, "villager-ai.compost.minimum-kept-per-item", DEFAULT_BEHAVIOR.compostMinimumKeptPerItem()),
                probability(yaml, "villager-ai.compost.default-chance", DEFAULT_BEHAVIOR.compostDefaultChance()),
                probability(yaml, "villager-ai.sharing.chance", DEFAULT_BEHAVIOR.sharingChance()),
                positiveDouble(yaml, "villager-ai.sharing.range", DEFAULT_BEHAVIOR.sharingRange()),
                probability(yaml, "villager-ai.sharing.keep-fraction", DEFAULT_BEHAVIOR.sharingKeepFraction()),
                nonNegativeInt(yaml, "villager-ai.bonemeal.retry-delay-ticks", DEFAULT_BEHAVIOR.bonemealRetryDelayTicks()),
                positiveInt(yaml, "villager-ai.bonemeal.work-duration-ticks", DEFAULT_BEHAVIOR.bonemealWorkDurationTicks()),
                nonNegativeInt(yaml, "villager-ai.farm.retarget-delay-ticks", DEFAULT_BEHAVIOR.farmRetargetDelayTicks()),
                nonNegativeInt(yaml, "villager-ai.farm.stop-cooldown-ticks", DEFAULT_BEHAVIOR.farmStopCooldownTicks()),
                positiveInt(yaml, "villager-ai.farm.work-duration-ticks", DEFAULT_BEHAVIOR.farmWorkDurationTicks()));

        return new VillagersDelightConfig(
                cropEntries,
                disabled,
                extraSoils,
                drops,
                yaml.getBoolean("pickup.enabled", false),
                pickupCrops,
                pickupFoods,
                yaml.getBoolean("share-items.enabled", true),
                shareItems,
                compostItems,
                yaml.getBoolean("debug", false),
                yaml.getBoolean("custom-crops.enabled", true),
                behavior
        );
    }

    private static double probability(FileConfiguration yaml, String path, double fallback) {
        double value = yaml.getDouble(path, fallback);
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(path + " must be between 0 and 1");
        }
        return value;
    }

    private static int positiveInt(FileConfiguration yaml, String path, int fallback) {
        int value = yaml.getInt(path, fallback);
        if (value < 1) throw new IllegalArgumentException(path + " must be at least 1");
        return value;
    }

    private static int nonNegativeInt(FileConfiguration yaml, String path, int fallback) {
        int value = yaml.getInt(path, fallback);
        if (value < 0) throw new IllegalArgumentException(path + " must be non-negative");
        return value;
    }

    private static double positiveDouble(FileConfiguration yaml, String path, double fallback) {
        double value = yaml.getDouble(path, fallback);
        if (!Double.isFinite(value) || value <= 0.0) throw new IllegalArgumentException(path + " must be greater than 0");
        return value;
    }

    // Parses entries of the form item-id:count. A two-part id (namespace:path) resolves as a
    // CE custom item first, then falls back to a vanilla Bukkit material.
    private static List<ItemStack> parseDrops(List<String> entries, String path) {
        List<ItemStack> result = new ArrayList<>(entries.size());
        for (String entry : entries) {
            String[] parts = entry.split(":");
            if (parts.length < 2 || parts.length > 3) {
                throw new IllegalArgumentException(path + " entry must be namespace:item[:count]: " + entry);
            }
            int count = parts.length > 2 ? parseCount(parts[2], path) : 1;
            Key customId = parseKey(parts[0] + ":" + parts[1], path);
            BukkitItemDefinition definition = CraftEngineItems.byId(customId);
            if (definition != null) {
                ItemStack stack = definition.buildBukkitItem();
                stack.setAmount(Math.max(1, count));
                result.add(stack);
                continue;
            }
            Material material = Material.matchMaterial(parts[1]);
            if (material == null) throw new IllegalArgumentException(path + " unknown item: " + entry);
            result.add(new ItemStack(material, Math.max(1, count)));
        }
        return result;
    }

    private static int parseCount(String raw, String path) {
        try {
            int count = Integer.parseInt(raw);
            if (count < 1) throw new NumberFormatException();
            return count;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(path + " count must be a positive integer: " + raw);
        }
    }

    private static Key parseKey(String raw, String path) {
        if (raw == null || !ITEM_ID.matcher(raw).matches()) {
            throw new IllegalArgumentException(path + " invalid namespaced id: " + raw);
        }
        try {
            return Key.of(raw);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(path + " invalid key '" + raw + "'", e);
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

    public BehaviorSettings behaviorSettings() {
        return this.behaviorSettings;
    }
}
