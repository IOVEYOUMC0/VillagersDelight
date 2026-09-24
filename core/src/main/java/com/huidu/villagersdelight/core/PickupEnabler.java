package com.huidu.villagersdelight.core;

import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.item.BukkitItemDefinition;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.World;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// Makes villager farmers pick up the configured crop seeds. Vanilla villagers only pick up items
// whose underlying material is in the villager_picks_up tag (or in the profession's requested
// items). Since 26.x removed runtime tag mutation, this generates a small data pack that appends
// the missing materials to the villager_picks_up tag and reloads data packs. CE seed items can
// therefore keep a vanilla base material that players cannot obtain or place (e.g. knowledge book).
public final class PickupEnabler {

    private PickupEnabler() {
    }

    public static void apply(VillagersDelightPlugin plugin, VillagersDelightConfig config) {
        if (!config.pickupEnabled()) {
            return;
        }
        CropRegistry registry = CropRegistry.instance();
        if (registry == null) {
            return;
        }
        // CraftEngine resolves item base materials lazily; loadedItems() is empty until CE finishes
        // its load pass. Writing before then persists a partial tag that oscillates boot to boot: the
        // material written last boot is already in the live tag (loaded from disk), gets filtered out
        // as "already present", and is dropped from the rewrite. Defer to the CraftEngineReloadEvent
        // re-run, which fires once CE items are loaded, and write the full set below.
        if (CraftEngineItems.loadedItems().isEmpty()) {
            return;
        }
        List<Key> enabledCrops = config.pickupCrops();
        // The full set of base materials the configured seeds and foods want in the tag. Writing the
        // complete set every time keeps the data pack idempotent: {"replace":false} unions with vanilla,
        // and re-listing a vanilla material is harmless.
        Set<Material> desired = new LinkedHashSet<>();
        for (FDCrop crop : registry.allCrops()) {
            if (!enabledCrops.isEmpty() && !enabledCrops.contains(crop.seedItem())
                    && !enabledCrops.contains(crop.blockId())) {
                continue;
            }
            Key seed = crop.seedItem();
            if (seed == null) {
                plugin.getLogger().warning("Pickup: crop " + crop.blockId() + " has no seed configured; villagers will not pick its seeds up");
                continue;
            }
            Material material = seedMaterial(seed);
            if (material == null) {
                plugin.getLogger().warning("Pickup: seed " + seed + " has no vanilla material; villagers may not pick it up");
                continue;
            }
            desired.add(material);
        }
        // Configured foods (harvest): their base materials go in the tag too so villagers can collect
        // them; the pickup listener still narrows this down to the exact configured ids.
        for (Key food : config.pickupFoods()) {
            Material material = seedMaterial(food);
            if (material == null) {
                plugin.getLogger().warning("Pickup: food " + food + " has no vanilla material; villagers may not pick it up");
                continue;
            }
            desired.add(material);
        }
        // Harvest-only items still need the pickup tag even when their food value is zero.
        for (var drops : config.harvestDrops().values()) {
            for (var drop : drops) {
                if (drop != null && !drop.getType().isAir()) desired.add(drop.getType());
            }
        }
        if (desired.isEmpty()) {
            return;
        }
        World world = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
        if (world == null) {
            plugin.getLogger().warning("Pickup: no world loaded, cannot write villager_picks_up tag data pack");
            return;
        }
        File root = new File(worldRoot(world), "datapacks/villagersdelight");
        // Skip the write and the full data pack reload when the injected tag is already on disk and
        // unchanged: a /ce reload re-enters this path, and the server-wide data pack reload it would
        // trigger otherwise freezes the main thread (villagersdelight TPS drop after every CE reload).
        if (dataPackMatches(root, desired)) {
            return;
        }
        if (!writeDataPack(root, desired)) {
            plugin.getLogger().warning("Pickup: failed to write " + root + "; villagers may not pick up CE seeds");
            return;
        }
        plugin.getLogger().info("Pickup: wrote villager_picks_up tag " + desired + " (data pack)");
        if (isRegionizedServer()) {
            plugin.getLogger().warning("Pickup: data pack written; runtime data reload is disabled on regionized servers. Restart the server to apply it");
            return;
        }
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> {
            try {
                Bukkit.reloadData();
            } catch (UnsupportedOperationException e) {
                plugin.getLogger().warning("Pickup: this server does not support runtime data reload; restart it to apply the data pack");
                return;
            }
            // Reloading resources is async; verify a moment later so the log tells us whether the
            // injected tag actually became visible to the vanilla tag registry.
            Bukkit.getGlobalRegionScheduler().runDelayed(plugin, verifyTask -> verifyInjectedTags(plugin, desired), 100L);
        }, 40L);
    }

    private static boolean dataPackMatches(File root, Set<Material> materials) {
        File tagFile = new File(root, "data/minecraft/tags/item/villager_picks_up.json");
        if (!tagFile.isFile()) {
            return false;
        }
        try {
            if (!Files.readString(tagFile.toPath(), StandardCharsets.UTF_8).equals(tagContent(materials))) {
                return false;
            }
            // Compared against the string writeDataPack would produce now, so a pack left behind by
            // a different server version is rewritten instead of being trusted.
            File meta = new File(root, "pack.mcmeta");
            return meta.isFile()
                    && Files.readString(meta.toPath(), StandardCharsets.UTF_8).equals(packMetadata());
        } catch (IOException e) {
            return false;
        }
    }

    // Serialized with a sorted value list so the same desired set always produces the same bytes,
    // letting dataPackMatches skip the write (and the expensive reloadData) when nothing changed
    // regardless of the set's iteration order.
    private static String tagContent(Set<Material> materials) {
        List<String> keys = new ArrayList<>();
        for (Material material : materials) {
            keys.add('"' + material.getKey().toString() + '"');
        }
        Collections.sort(keys);
        return "{\"replace\":false,\"values\":[" + String.join(",", keys) + "]}\n";
    }

    private static void verifyInjectedTags(VillagersDelightPlugin plugin, Set<Material> materials) {
        boolean allActive = true;
        for (Material material : materials) {
            if (!isVillagerPickable(material)) {
                allActive = false;
                plugin.getLogger().warning("Pickup: " + material + " still not in villager_picks_up tag after reloadData; restart the server (or run /reload) to apply the data pack");
            }
        }
        if (allActive) {
            plugin.getLogger().info("Pickup: tag active, villagers can now pick up " + materials);
        }
    }

    // The data pack format is version specific: a single pack_format up to 1.21.8, a min_format and
    // max_format pair from 1.21.9 onward. A pack whose declared format falls outside the range the
    // server accepts is skipped without an error, and the tag never reaches the registry -- the only
    // symptom would be the warning in verifyInjectedTags, which blames timing for a format problem.
    // VillagersDelight carries no FarmersDelight dependency on purpose, so this repeats the probe in
    // com.huidu.farmersdelight.api.util.DatapackSupport rather than calling it.
    private static String packMetadata() {
        PackFormat format = serverDataPackFormat();
        if (format == null) {
            format = fallbackPackFormat();
        }
        String line = format.range()
                ? "\"min_format\":" + format.major() + ",\"max_format\":" + MAX_RANGE_FORMAT
                : "\"pack_format\":" + format.major();
        return "{\"pack\":{\"description\":\"VillagersDelight villager seed pickup\"," + line + "}}\n";
    }

    private record PackFormat(int major, boolean range) {
    }

    private static final int MAX_RANGE_FORMAT = 150;

    // 1.21.4 exposes getPackVersion(PackType) returning an int; 26.x renamed it to packVersion(PackType)
    // and returns a PackFormat record. Both are reached reflectively so one jar covers the whole range.
    private static PackFormat serverDataPackFormat() {
        try {
            Class<?> shared = Class.forName("net.minecraft.SharedConstants");
            Object worldVersion = null;
            for (String name : new String[]{"getCurrentVersion", "currentVersion"}) {
                try {
                    worldVersion = shared.getMethod(name).invoke(null);
                    break;
                } catch (NoSuchMethodException ignored) {
                    // try the other spelling
                }
            }
            if (worldVersion == null) {
                return null;
            }
            Class<?> packTypeClass = Class.forName("net.minecraft.server.packs.PackType");
            Object serverData = null;
            for (Object constant : packTypeClass.getEnumConstants()) {
                if ("SERVER_DATA".equals(((Enum<?>) constant).name())) {
                    serverData = constant;
                    break;
                }
            }
            if (serverData == null) {
                return null;
            }
            for (String name : new String[]{"packVersion", "getPackVersion"}) {
                try {
                    Object result = worldVersion.getClass()
                            .getMethod(name, packTypeClass).invoke(worldVersion, serverData);
                    if (result instanceof Integer value) {
                        return new PackFormat(value, false);
                    }
                    Object major = result.getClass().getMethod("major").invoke(result);
                    return new PackFormat(((Number) major).intValue(), true);
                } catch (NoSuchMethodException ignored) {
                    // try the other spelling
                }
            }
        } catch (Throwable ignored) {
            // Any linkage or access failure falls back to the version table below.
        }
        return null;
    }

    // Data pack format history: 1.21.4=61, 1.21.5=71, 1.21.6/7/8=80; 1.21.9 and every 26.x release use
    // the min_format/max_format range form (88 is the 1.21.9 data pack major format).
    private static PackFormat fallbackPackFormat() {
        String version = Bukkit.getBukkitVersion();
        if (version.contains("1.21.4")) {
            return new PackFormat(61, false);
        }
        if (version.contains("1.21.5")) {
            return new PackFormat(71, false);
        }
        if (version.contains("1.21.6") || version.contains("1.21.7") || version.contains("1.21.8")) {
            return new PackFormat(80, false);
        }
        return new PackFormat(88, true);
    }

    // Resolves the world folder that holds level.dat. Same heuristic as FarmersDelight's
    // LootDatapackInstaller so the data pack lands in the folder the pack repository scans.
    private static File worldRoot(World world) {
        File folder = world.getWorldFolder();
        while (folder != null && !new File(folder, "level.dat").exists()) {
            folder = folder.getParentFile();
        }
        return folder != null ? folder : world.getWorldFolder();
    }

    // Resolves the vanilla material backing a seed item: CE definitions expose their base item,
    // plain vanilla items resolve by id.
    public static Material seedMaterial(Key seedId) {
        BukkitItemDefinition definition = CraftEngineItems.byId(seedId);
        if (definition != null) {
            return definition.buildBukkitItem().getType();
        }
        // Vanilla seed item: its material is the vanilla item itself.
        return Material.matchMaterial(seedId.toString());
    }

    private static boolean isVillagerPickable(Material material) {
        Tag<Material> picksUp = tag("villager_picks_up");
        Tag<Material> plantable = tag("villager_plantable_seeds");
        return (picksUp != null && picksUp.isTagged(material))
                || (plantable != null && plantable.isTagged(material));
    }

    private static Tag<Material> tag(String key) {
        return Bukkit.getTag(Tag.REGISTRY_ITEMS, NamespacedKey.minecraft(key), Material.class);
    }

    private static boolean writeDataPack(File root, Set<Material> materials) {
        try {
            Files.createDirectories(root.toPath());
            Files.writeString(new File(root, "pack.mcmeta").toPath(), packMetadata(), StandardCharsets.UTF_8);
            File tagsDir = new File(root, "data/minecraft/tags/item");
            Files.createDirectories(tagsDir.toPath());
            Files.writeString(
                    new File(tagsDir, "villager_picks_up.json").toPath(),
                    tagContent(materials),
                    StandardCharsets.UTF_8
            );
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isRegionizedServer() {
        String className = "io.papermc.paper.threadedregions.RegionizedServer";
        ClassLoader[] loaders = {
                PickupEnabler.class.getClassLoader(),
                Thread.currentThread().getContextClassLoader(),
                Bukkit.getServer() == null ? null : Bukkit.getServer().getClass().getClassLoader(),
                ClassLoader.getSystemClassLoader()
        };
        for (ClassLoader loader : loaders) {
            try {
                Class.forName(className, false, loader);
                return true;
            } catch (ClassNotFoundException | LinkageError ignored) {
                // Try the next loader; plugin and server classes may use different class loaders.
            }
        }
        return false;
    }
}
