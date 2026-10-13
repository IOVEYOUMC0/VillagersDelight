package com.huidu.villagersdelight.core;

import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.item.BukkitItemDefinition;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Bukkit;
import org.bukkit.Material;
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
// the missing materials to the villager_picks_up tag for the next server start. CE seed items can
// therefore keep a vanilla base material that players cannot obtain or place (e.g. knowledge book).
public final class PickupEnabler {

    // True once this JVM has written the data pack. The plugin takes the pack over only when it
    // successfully wrote it; a pack file already present at startup belongs to the operator and is
    // left to them, so it is not reported here.
    private static volatile boolean dataPackWritten;

    private PickupEnabler() {
    }

    /** True when the villager_picks_up data pack was written during this server run. */
    public static boolean dataPackWritten() {
        return dataPackWritten;
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
        dataPackWritten = true;
        plugin.getLogger().warning("Pickup: data pack written; restart the server to apply the pickup tag");
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
    // letting dataPackMatches skip the write when nothing changed
    // regardless of the set's iteration order.
    private static String tagContent(Set<Material> materials) {
        List<String> keys = new ArrayList<>();
        for (Material material : materials) {
            keys.add('"' + material.getKey().toString() + '"');
        }
        Collections.sort(keys);
        return "{\"replace\":false,\"values\":[" + String.join(",", keys) + "]}\n";
    }

    private static String packMetadata() {
        return packMetadata(Bukkit.getBukkitVersion());
    }

    static String packMetadata(String version) {
        String format = version.startsWith("1.21.5-") || version.equals("1.21.5")
                ? "\"pack_format\":71"
                : version.startsWith("1.21.6") ? "\"pack_format\":80"
                : version.startsWith("1.21.7") || version.startsWith("1.21.8") ? "\"pack_format\":81"
                : "\"min_format\":88,\"max_format\":150";
        return "{\"pack\":{\"description\":\"VillagersDelight villager seed pickup\"," + format + "}}\n";
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

}
