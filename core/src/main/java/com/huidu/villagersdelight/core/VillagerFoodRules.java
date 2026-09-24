package com.huidu.villagersdelight.core;

import java.util.Map;
import java.util.Set;

/** Food identity and planting reserves shared by the versioned villager behaviors. */
public record VillagerFoodRules(Map<String, Integer> points, Set<String> seeds, int minimumKeptSeeds) {

    public static final VillagerFoodRules EMPTY = new VillagerFoodRules(Map.of(), Set.of(), 0);

    public VillagerFoodRules {
        points = Map.copyOf(points);
        seeds = Set.copyOf(seeds);
        if (minimumKeptSeeds < 0 || points.values().stream().anyMatch(value -> value < 0 || value > 12)) {
            throw new IllegalArgumentException("Food points must be 0..12 and seed reserves non-negative");
        }
    }

    public int value(String id) {
        return points.getOrDefault(id, 0);
    }

    public int reserve(String id, boolean farmer) {
        return farmer && seeds.contains(id) ? minimumKeptSeeds : 0;
    }

    public int consumable(String id, int held, boolean farmer, int neededPoints) {
        int value = value(id);
        if (value == 0 || neededPoints <= 0) return 0;
        return Math.min(Math.max(0, held - reserve(id, farmer)), (neededPoints + value - 1) / value);
    }
}
