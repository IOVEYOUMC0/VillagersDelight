package com.huidu.villagersdelight.core;

import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.item.BukkitItemDefinition;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;

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
        // complete set every time (rather than only the not-yet-tagged subset) keeps the data pack
        // idempotent: {"replace":false} unions with vanilla, so re-listing a material vanilla already
        // has is harmless, and no previously-injected material can be dropped.
        Set<Material> desired = new LinkedHashSet<>();
        for (FDCrop crop : registry.allCrops()) {
            if (!enabledCrops.isEmpty() && !enabledCrops.contains(crop.blockId())) {
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
        plugin.getLogger().info("Pickup: wrote villager_picks_up tag " + desired + " (data pack, reloading)");
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
            // A leftover pack.mcmeta from the pre-fix build (pack_format 81+) makes reloadData log
            // the "missing min_format and max_format" error, so only treat the pack as up to date
            // when the fixed format 48 metadata is present too.
            File meta = new File(root, "pack.mcmeta");
            return meta.isFile()
                    && Files.readString(meta.toPath(), StandardCharsets.UTF_8).contains("\"pack_format\":48");
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
            // Fixed pack format 48 (1.21): formats above 81 require min_format/max_format in the
            // metadata, which a plain pack.mcmeta does not provide. Older formats are accepted on
            // newer servers, so 48 loads cleanly on 26.x.
            Files.writeString(
                    new File(root, "pack.mcmeta").toPath(),
                    "{\"pack\":{\"description\":\"VillagersDelight villager seed pickup\",\"pack_format\":48}}\n",
                    StandardCharsets.UTF_8
            );
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
}
