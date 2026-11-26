package com.huidu.villagersdelight.api;

import com.huidu.villagersdelight.core.CropRegistry;
import com.huidu.villagersdelight.core.FDCrop;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Read-only API for other plugins that need VillagersDelight's crop configuration.
 *
 * <p>All ids are plain {@code namespace:path} strings, so a consumer does not need a CraftEngine
 * dependency for ordinary crop queries. The API reflects the current registry snapshot; it is
 * rebuilt on VillagersDelight reload and cleared on plugin shutdown.</p>
 *
 * <p>Call through {@link #get()}; the methods return empty/false when the plugin is absent or its
 * crop registry is not ready yet.</p>
 */
public final class VillagersDelightApi {

    public static final int API_VERSION = 1;

    private static final VillagersDelightApi INSTANCE = new VillagersDelightApi();

    private VillagersDelightApi() {
    }

    public static VillagersDelightApi get() {
        return INSTANCE;
    }

    public int apiVersion() {
        return API_VERSION;
    }

    /** Whether the VillagersDelight plugin is enabled on this server. */
    public boolean isAvailable() {
        try {
            return Bukkit.getPluginManager().isPluginEnabled("VillagersDelight");
        } catch (RuntimeException | LinkageError unavailable) {
            return false;
        }
    }

    /** Every configured crop that resolved to a loaded CraftEngine block. */
    public List<CropInfo> crops() {
        CropRegistry registry = CropRegistry.instance();
        if (registry == null) {
            return List.of();
        }
        List<CropInfo> crops = new ArrayList<>(registry.cropCount());
        for (FDCrop crop : registry.allCrops()) {
            crops.add(info(crop));
        }
        return List.copyOf(crops);
    }

    /** A crop by its block id, or null when it is not configured/loaded. */
    @Nullable
    public CropInfo crop(String blockId) {
        return info(CropRegistry.cropById(blockId));
    }

    /** The crop planted by a seed item id, or null. */
    @Nullable
    public CropInfo cropBySeed(String seedItemId) {
        return info(CropRegistry.cropBySeed(seedItemId));
    }

    public boolean isCrop(String blockId) {
        return crop(blockId) != null;
    }

    /** Extra soils configured globally; every configured crop accepts these in addition to its own. */
    public Set<String> extraSoils() {
        return strings(CropRegistry.extraSoilsSnapshot());
    }

    /** Every soil accepted by at least one configured crop (global extra soils included). */
    public Set<String> plantableSoils() {
        return strings(CropRegistry.plantableSoilsSnapshot());
    }

    public boolean isPlantableSoil(String blockId) {
        return blockId != null && plantableSoils().contains(blockId);
    }

    @Nullable
    private static CropInfo info(@Nullable FDCrop crop) {
        if (crop == null) {
            return null;
        }
        return new CropInfo(
                crop.blockId().toString(),
                crop.ageMax(),
                crop.seedItem() == null ? null : crop.seedItem().toString(),
                strings(crop.soils()),
                HarvestMode.valueOf(crop.harvestMode().name()),
                crop.water() == null ? null : crop.water().toString(),
                crop.plantBlock().toString());
    }

    private static Set<String> strings(Set<?> ids) {
        List<String> values = new ArrayList<>(ids.size());
        for (Object id : ids) {
            values.add(id.toString());
        }
        return Set.copyOf(values);
    }
}
