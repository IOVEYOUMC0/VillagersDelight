package com.huidu.villagersdelight.core;

import org.bukkit.configuration.file.YamlConfiguration;

import java.util.List;
import java.util.Map;
import java.util.Set;

public final class VillagerFoodRulesCheck {
    public static void main(String[] args) throws Exception {
        var rules = new VillagerFoodRules(Map.of("test:onion", 1, "test:rice", 2, "test:panicle", 0),
                Set.of("test:onion", "test:rice"), 32);
        assert rules.consumable("test:rice", 6, false, 12) == 6;
        assert rules.consumable("test:rice", 32, true, 12) == 0;
        assert rules.consumable("test:rice", 38, true, 12) == 6;
        assert rules.consumable("test:rice", 34, true, 12) == 2;
        assert rules.consumable("test:rice", 38, true, 1) == 1;
        assert rules.consumable("test:onion", 12, false, 12) == 12;
        assert rules.consumable("test:panicle", 64, false, 12) == 0;
        assert rules.consumable("test:unknown", 64, false, 12) == 0;
        assert rules.consumable("test:rice", 64, true, 0) == 0;
        // Combined reserve across multiple inventory slots; consuming the first slot must not
        // allow the second slot to consume the same reserved stock again.
        int held = 36;
        int first = Math.min(3, rules.consumable("test:rice", held, true, 12));
        held -= first;
        int second = rules.consumable("test:rice", held, true, 12 - first * 2);
        assert held - second == 32;

        var yaml = new YamlConfiguration();
        yaml.loadFromString("""
                pickup:
                  foods:
                    - test:rice
                    - test:onion
                    - test:panicle
                villager-ai:
                  food:
                    points:
                      test:rice: 2
                      test:panicle: 0
                """);
        var loaded = VillagersDelightConfig.load(yaml).foodRules(Set.of("test:rice"));
        assert loaded.value("test:rice") == 2;
        assert loaded.value("test:onion") == 1;
        assert loaded.value("test:panicle") == 0;
        assert loaded.reserve("test:rice", true) == 32;
        yaml.set("villager-ai.food.protect-custom-seeds", false);
        assert VillagersDelightConfig.load(yaml).foodRules(Set.of("test:rice")).reserve("test:rice", true) == 0;
        for (Object invalid : new Object[]{-1, 13, 1.5, "2", true}) {
            yaml.set("villager-ai.food.points.test:rice", invalid);
            try {
                VillagersDelightConfig.load(yaml);
                throw new AssertionError("Accepted invalid food points: " + invalid);
            } catch (IllegalArgumentException expected) {
                assert expected.getMessage().contains("villager-ai.food.points");
            }
        }
        var defaults = new YamlConfiguration();
        defaults.load("src/main/resources/config.yml");
        defaults.set("harvest-drops", null);
        var modRules = VillagersDelightConfig.load(defaults).foodRules(Set.of("farmersdelight:rice"));
        assert modRules.value("farmersdelight:cabbage") == 1;
        assert modRules.value("farmersdelight:tomato") == 1;
        assert modRules.value("farmersdelight:onion") == 1;
        assert modRules.value("farmersdelight:rice") == 2;
        assert modRules.value("farmersdelight:rice_panicle") == 0;
        // Old configurations inherit the new scalar defaults without overwriting their food list.
        var oldConfig = new YamlConfiguration();
        oldConfig.setDefaults(defaults);
        oldConfig.set("pickup.foods", List.of("farmersdelight:rice", "farmersdelight:rice_panicle"));
        var inherited = VillagersDelightConfig.load(oldConfig).foodRules(Set.of());
        assert inherited.value("farmersdelight:rice") == 2;
        assert inherited.value("farmersdelight:rice_panicle") == 0;
        assert inherited.value("farmersdelight:onion") == 0;
        var legacy = new YamlConfiguration();
        legacy.set("pickup.foods", List.of("farmersdelight:rice", "farmersdelight:rice_panicle"));
        var legacyRules = VillagersDelightConfig.load(legacy).foodRules(Set.of());
        assert legacyRules.value("farmersdelight:rice") == 2;
        assert legacyRules.value("farmersdelight:rice_panicle") == 0;
        // Seed-slot cache signature: an unchanged inventory keeps its signature, while everything the decode
        // reads (emptiness, item identity, data components) changes it, and slot order matters.
        long emptySlot = SeedSlotSignature.fold(SeedSlotSignature.start(), true, 0, 0);
        assert SeedSlotSignature.fold(SeedSlotSignature.start(), true, 0, 0) == emptySlot;
        long seed = SeedSlotSignature.fold(SeedSlotSignature.start(), false, 1234, 0);
        assert seed != emptySlot;
        assert SeedSlotSignature.fold(SeedSlotSignature.start(), false, 1234, 7) != seed;
        assert SeedSlotSignature.fold(SeedSlotSignature.start(), false, 1235, 0) != seed;
        assert SeedSlotSignature.fold(SeedSlotSignature.start(), false, 0, 0) != emptySlot;
        assert SeedSlotSignature.fold(seed, false, 99, 0)
                != SeedSlotSignature.fold(SeedSlotSignature.start(), false, 99, 0);

        System.out.println("Food points, shared seed reserves, seed-slot signature and config validation passed.");
    }
}
