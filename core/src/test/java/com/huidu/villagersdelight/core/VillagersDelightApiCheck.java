package com.huidu.villagersdelight.core;

import com.huidu.villagersdelight.api.CropHandle;
import com.huidu.villagersdelight.api.CropInfo;
import com.huidu.villagersdelight.api.HarvestMode;
import com.huidu.villagersdelight.api.HarvestResult;
import com.huidu.villagersdelight.api.VillagersDelightApi;

import java.util.List;
import java.util.Set;

/** Offline contract check for the public VillagersDelight api artifact. */
public final class VillagersDelightApiCheck {

    private VillagersDelightApiCheck() {
    }

    public static void main(String[] args) {
        VillagersDelightApi api = VillagersDelightApi.get();
        check(api.apiVersion() == VillagersDelightApi.API_VERSION, "api version mismatch");
        check(api.crops().isEmpty(), "no registry snapshot should exist offline");
        check(api.crop("testaddon:crop") == null, "unknown crop must be null");
        check(api.cropBySeed("testaddon:seed") == null, "unknown seed must be null");
        check(api.crop("not a valid id") == null, "malformed crop id must be null");
        check(api.cropBySeed("not a valid id") == null, "malformed seed id must be null");
        check(api.extraSoils().isEmpty(), "extra soils should be empty offline");
        check(api.cropAt(null) == null, "null block has no crop");
        check(!api.isMature(null), "null block is not mature");
        check(api.age(null) == -1, "null block has no age");
        check(!api.isUpperHalf(null) && !api.isLowerHalf(null) && !api.isTallCrop(null), "null half checks");
        check(!api.harvest(null).harvested(), "null harvest fails closed");
        check(!api.plant(null, "testaddon:seed"), "null plant target fails");
        check(!api.canPlant(null, "testaddon:seed"), "null canPlant target fails");
        check(!api.canPlant(null, (org.bukkit.inventory.ItemStack) null), "null canPlant seed fails");
        check(!api.harvest(null, true).harvested(), "null spawn harvest fails");
        check(!api.harvestAndSpawn(null).harvested(), "null append harvest fails");
        check(!api.plant(null, (org.bukkit.inventory.ItemStack) null), "null plant seed fails");
        CropHandle handle = api.handle("testaddon:crop");
        check(handle.isLoaded() == false && handle.current().isEmpty(), "offline handle has no snapshot");
        check(handle.equals(api.handle("testaddon:crop")), "handle equality by id");
        check(!handle.equals(api.handle("testaddon:other")), "different handles differ");
        HarvestResult noHarvest = HarvestResult.failure();
        check(!noHarvest.harvested() && noHarvest.drops().isEmpty(), "failure result is empty");
        check(!api.isAvailable() || api.isAvailable(), "availability probe must not throw");

        CropInfo info = new CropInfo("testaddon:crop", 3, "testaddon:seed",
                Set.of("minecraft:farmland"), HarvestMode.TALL, "minecraft:water", "testaddon:young_crop");
        check(info.blockId().equals("testaddon:crop"), "block id");
        check(info.ageMax() == 3, "age max");
        check(info.seedItem() != null && info.seedItem().equals("testaddon:seed"), "seed id");
        check(info.soils().equals(Set.of("minecraft:farmland")), "soil set");
        check(info.harvestMode() == HarvestMode.TALL, "harvest mode");
        check(info.water() != null && info.water().equals("minecraft:water"), "water id");
        check(info.plantBlock().equals("testaddon:young_crop"), "plant block");
        try {
            info.soils().add("minecraft:dirt");
            throw new AssertionError("CropInfo soils must be immutable");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
        check(List.of(info).getFirst().equals(info), "record equality");
        System.out.println("VillagersDelight api checks passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
