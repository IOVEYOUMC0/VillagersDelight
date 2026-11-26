package com.huidu.villagersdelight.api;

import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * Immutable read-only snapshot of one configured VillagersDelight crop.
 *
 * <p>It contains only strings and an immutable set, never a registry or CraftEngine object, so an
 * acquired snapshot stays readable across a VillagersDelight reload. It is intentionally a snapshot,
 * not a live view; use {@link VillagersDelightApi#handle(String)} when a long-lived reference must
 * follow the current reloaded definition.</p>
 *
 * @param blockId     the CraftEngine crop block id
 * @param ageMax      highest growth property value
 * @param seedItem    seed item id, or null when the crop has no configured seed
 * @param soils       accepted soils (global extra soils included)
 * @param harvestMode how villagers harvest it
 * @param water       required planting fluid id, or null for dry soil
 * @param plantBlock  block placed at age 0 (defaults to {@code blockId})
 */
public record CropInfo(
        String blockId,
        int ageMax,
        @Nullable String seedItem,
        Set<String> soils,
        HarvestMode harvestMode,
        @Nullable String water,
        String plantBlock) {

    public CropInfo {
        soils = Set.copyOf(soils);
    }
}
