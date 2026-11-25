package com.huidu.villagersdelight.api;

import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;
import java.util.Optional;

/**
 * A reload-stable reference to one configured crop.
 *
 * Unlike CropInfo, which is an immutable snapshot, a handle stores only the crop block
 * id. Its methods resolve the current registry every time, so a handle obtained before a
 * VillagersDelight reload keeps working and reports the reloaded definition instead of pointing at
 * an invalidated old registry object.
 */
public final class CropHandle {

    private final String blockId;

    CropHandle(String blockId) {
        this.blockId = Objects.requireNonNull(blockId, "blockId");
    }

    public String blockId() {
        return blockId;
    }

    /** Current definition, or empty if the crop is no longer configured/loaded. */
    public Optional<CropInfo> current() {
        return Optional.ofNullable(VillagersDelightApi.get().crop(blockId));
    }

    public boolean isLoaded() {
        return current().isPresent();
    }

    public boolean isMature(Block block) {
        return VillagersDelightApi.get().isMature(block);
    }

    public int age(Block block) {
        return VillagersDelightApi.get().age(block);
    }

    public HarvestResult harvest(Block block) {
        return VillagersDelightApi.get().harvest(block);
    }

    public HarvestResult harvestPreview(Block block) {
        return VillagersDelightApi.get().harvestPreview(block);
    }

    public HarvestResult harvestAndSpawn(Block block) {
        return VillagersDelightApi.get().harvestAndSpawn(block);
    }

    public boolean canPlant(Block block, ItemStack seed) {
        return VillagersDelightApi.get().canPlant(block, seed);
    }

    public boolean canPlant(Block block, String seedItemId) {
        return VillagersDelightApi.get().canPlant(block, seedItemId);
    }

    public boolean plant(Block block, ItemStack seed) {
        return VillagersDelightApi.get().plant(block, seed);
    }

    public boolean plant(Block block, String seedItemId) {
        return VillagersDelightApi.get().plant(block, seedItemId);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CropHandle handle && blockId.equals(handle.blockId);
    }

    @Override
    public int hashCode() {
        return blockId.hashCode();
    }

    @Override
    public String toString() {
        return "CropHandle[" + blockId + "]";
    }
}
