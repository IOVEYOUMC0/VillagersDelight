package com.huidu.villagersdelight.api;

import com.huidu.villagersdelight.core.CeBlockAccess;
import com.huidu.villagersdelight.core.CeItemAccess;
import com.huidu.villagersdelight.core.CropRegistry;
import com.huidu.villagersdelight.core.FDCrop;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Read-only API for other plugins that need VillagersDelight's crop configuration.
 *
 * <p>All ids are plain {@code namespace:path} strings, so a consumer does not need a CraftEngine
 * dependency for ordinary crop queries. The API reflects the current registry snapshot; it is
 * rebuilt on VillagersDelight reload and cleared on plugin shutdown.</p>
 *
 * <p>Call through {@link #get()}; the methods return empty/false when the plugin is absent or its
 * crop registry is not ready yet. Configuration lookups resolve the current registry on every call,
 * and {@link #handle(String)} returns a reload-stable reference; immutable {@link CropInfo} values
 * remain readable after a reload but do not update themselves.</p>
 *
 * <p>Live block methods check the caller owns the block's region; on the wrong thread they fail
 * closed (null/false/-1/failure) instead of touching a block owned by another Folia region.</p>
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

    /** The crop behind a live block, or null when it is not a configured crop. */
    @Nullable
    public CropInfo cropAt(Block block) {
        return owns(block) ? info(CeBlockAccess.cropAt(block)) : null;
    }

    /** A reload-stable handle for a crop id; it resolves the current definition on each call. */
    public CropHandle handle(String blockId) {
        return new CropHandle(Objects.requireNonNull(blockId, "blockId"));
    }

    /** Whether the live block is a configured crop that has reached its harvest stage. */
    public boolean isMature(Block block) {
        if (!owns(block)) {
            return false;
        }
        FDCrop crop = CeBlockAccess.cropAt(block);
        if (crop == null) {
            return false;
        }
        var state = CeBlockAccess.customStateAt(block);
        return state != null && crop.isMature(state);
    }

    /** Current growth value of a crop block, or {@code -1} when it is not a configured crop. */
    public int age(Block block) {
        if (!owns(block)) {
            return -1;
        }
        FDCrop crop = CeBlockAccess.cropAt(block);
        if (crop == null) {
            return -1;
        }
        var state = CeBlockAccess.customStateAt(block);
        return state == null ? -1 : crop.ageOf(state);
    }

    /** Whether this block is the upper half of a configured TALL crop. */
    public boolean isUpperHalf(Block block) {
        if (!owns(block)) {
            return false;
        }
        FDCrop crop = CeBlockAccess.cropAt(block);
        if (crop == null) {
            return false;
        }
        var state = CeBlockAccess.customStateAt(block);
        return state != null && crop.isUpperHalf(state);
    }

    /** Whether this block is the lower half of a configured TALL crop. */
    public boolean isLowerHalf(Block block) {
        if (!owns(block)) {
            return false;
        }
        FDCrop crop = CeBlockAccess.cropAt(block);
        if (crop == null || crop.harvestMode() != FDCrop.HarvestMode.TALL) {
            return false;
        }
        var state = CeBlockAccess.customStateAt(block);
        return state != null && !crop.isUpperHalf(state);
    }

    public boolean isTallCrop(Block block) {
        if (!owns(block)) {
            return false;
        }
        FDCrop crop = CeBlockAccess.cropAt(block);
        return crop != null && crop.harvestMode() == FDCrop.HarvestMode.TALL;
    }

    /**
     * Harvests a configured mature crop and spawns its drops at the harvested block, while also
     * returning caller-owned copies.
     *
     * <p>For a TALL crop, passing either half targets the upper half: the upper block is removed and
     * the lower stalk is reset. PICK crops are replanted at age 0. The block must be owned by the
     * current region thread on Folia.</p>
     */
    public HarvestResult harvest(Block block) {
        return harvest(block, true);
    }

    /** Harvests without spawning world items; the caller owns the returned drops. */
    public HarvestResult harvestPreview(Block block) {
        return harvest(block, false);
    }

    /**
     * Harvests a configured mature crop. When {@code spawnDrops} is true the returned drops are also
     * spawned naturally at the harvested block; otherwise only copies are returned.
     */
    public HarvestResult harvest(Block block, boolean spawnDrops) {
        if (!owns(block)) {
            return HarvestResult.failure();
        }
        FDCrop crop = CeBlockAccess.cropAt(block);
        var state = CeBlockAccess.customStateAt(block);
        if (crop == null || state == null) {
            return HarvestResult.failure();
        }
        Block target = block;
        if (crop.harvestMode() == FDCrop.HarvestMode.TALL && !crop.isUpperHalf(state)) {
            target = block.getRelative(BlockFace.UP);
            crop = CeBlockAccess.cropAt(target);
            state = CeBlockAccess.customStateAt(target);
            if (crop == null || state == null || !crop.isUpperHalf(state)) {
                return HarvestResult.failure();
            }
        }
        if (!crop.isMature(state)) {
            return HarvestResult.failure();
        }

        List<ItemStack> drops = CeBlockAccess.harvestDrops(target);
        if (!CeBlockAccess.removeBlock(target)) {
            return HarvestResult.failure();
        }
        if (crop.harvestMode() == FDCrop.HarvestMode.TALL) {
            CeBlockAccess.resetTallLower(target.getRelative(BlockFace.DOWN), crop);
        } else if (crop.harvestMode() == FDCrop.HarvestMode.PICK) {
            CeBlockAccess.placeCrop(target, crop.plantBlock().toString(), 0, false);
        }
        if (spawnDrops) {
            var world = target.getWorld();
            var location = target.getLocation().add(0.5, 0.5, 0.5);
            for (ItemStack drop : drops) {
                world.dropItemNaturally(location, drop.clone());
            }
        }
        return HarvestResult.success(drops);
    }

    /** Harvests and also spawns the returned drops at the harvested block. */
    public HarvestResult harvestAndSpawn(Block block) {
        return harvest(block, true);
    }

    /**
     * Whether the target currently satisfies this seed crop's configured soil/fluid requirement.
     * Does not place a block or consume the seed.
     */
    public boolean canPlant(Block block, ItemStack seed) {
        if (!owns(block) || seed == null || seed.isEmpty()) {
            return false;
        }
        return canPlant(block, CeItemAccess.itemId(seed));
    }

    /** Validate a planting target by seed item id without placing or consuming anything. */
    public boolean canPlant(Block block, String seedItemId) {
        if (!owns(block) || seedItemId == null) {
            return false;
        }
        FDCrop crop = CropRegistry.cropBySeed(seedItemId);
        return crop != null && CeBlockAccess.canPlantAt(block, crop);
    }

    /**
     * Plants a configured crop from a seed item. On success one item is removed from the passed
     * stack (the caller owns it); soil/water is validated against the current crop definition.
     */
    public boolean plant(Block block, ItemStack seed) {
        if (!owns(block) || seed == null || seed.isEmpty()) {
            return false;
        }
        String seedItemId = CeItemAccess.itemId(seed);
        if (seedItemId == null) {
            return false;
        }
        FDCrop crop = CropRegistry.cropBySeed(seedItemId);
        if (crop == null || !CeBlockAccess.canPlantAt(block, crop)) {
            return false;
        }
        if (!CeBlockAccess.placeCrop(block, crop.plantBlock().toString(), 0, true)) {
            return false;
        }
        seed.setAmount(seed.getAmount() - 1);
        return true;
    }

    /**
     * Plants a configured crop from a plain seed item id. The target must satisfy the crop's
     * configured soil or fluid requirement; this overload consumes nothing.
     */
    public boolean plant(Block block, String seedItemId) {
        if (!owns(block) || seedItemId == null) {
            return false;
        }
        FDCrop crop = CropRegistry.cropBySeed(seedItemId);
        if (crop == null || !CeBlockAccess.canPlantAt(block, crop)) {
            return false;
        }
        return CeBlockAccess.placeCrop(block, crop.plantBlock().toString(), 0, true);
    }

    private static boolean owns(Block block) {
        if (block == null) {
            return false;
        }
        try {
            return Bukkit.isOwnedByCurrentRegion(block);
        } catch (RuntimeException | LinkageError unsupported) {
            // Paper's Folia API is not implemented on every server flavor; the legacy main-thread
            // check is the safe fallback there.
            return Bukkit.isPrimaryThread();
        }
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
