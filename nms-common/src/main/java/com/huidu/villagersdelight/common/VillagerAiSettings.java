package com.huidu.villagersdelight.common;

import com.huidu.villagersdelight.core.VillagerFoodRules;
import com.huidu.villagersdelight.core.VillagersDelightConfig;

import java.util.Set;

/**
 * Tunables shared by every version adapter: the plugin writes them from config.yml once per load or reload and
 * the behaviours installed into villager brains read them per tick. They live here rather than in each adapter
 * because all three adapters compile the same behaviour sources from nms-common.
 */
public final class VillagerAiSettings {

    public static volatile VillagerFoodRules FOOD_RULES = VillagerFoodRules.EMPTY;
    /** CE item ids villagers may compost (VillagerWorkAtComposter); the vanilla table is untouched. */
    public static volatile Set<String> COMPOST_IDS = Set.of();
    /** Compost success probability for CE items without a vanilla table entry. */
    public static volatile float COMPOST_CHANCE = 0.3F;
    public static volatile boolean FOOD_ENABLED = true;
    public static volatile double FOOD_CHECK_CHANCE = 0.05;
    public static volatile int COMPOST_MAX_ITEMS = 20;
    public static volatile int COMPOST_MINIMUM_KEPT = 32;
    public static volatile int BONEMEAL_RETRY_DELAY = 40;
    public static volatile int BONEMEAL_WORK_DURATION = 80;
    public static volatile int FARM_RETARGET_DELAY = 20;
    public static volatile int FARM_STOP_COOLDOWN = 40;
    public static volatile int FARM_WORK_DURATION = 200;

    private VillagerAiSettings() {
    }

    /** Applies the villager-ai block of config.yml; every field is written so a reload takes effect. */
    public static void apply(VillagersDelightConfig.BehaviorSettings settings) {
        FOOD_ENABLED = settings.foodEnabled();
        FOOD_CHECK_CHANCE = settings.foodCheckChance();
        COMPOST_MAX_ITEMS = settings.compostMaxItemsPerWork();
        COMPOST_MINIMUM_KEPT = settings.compostMinimumKeptPerItem();
        COMPOST_CHANCE = (float) settings.compostDefaultChance();
        BONEMEAL_RETRY_DELAY = settings.bonemealRetryDelayTicks();
        BONEMEAL_WORK_DURATION = settings.bonemealWorkDurationTicks();
        FARM_RETARGET_DELAY = settings.farmRetargetDelayTicks();
        FARM_STOP_COOLDOWN = settings.farmStopCooldownTicks();
        FARM_WORK_DURATION = settings.farmWorkDurationTicks();
    }

    /**
     * Restores every tunable to its built-in default. Called while the plugin shuts down so behaviour
     * instances still referenced by villager brains read neutral values instead of the disabled
     * plugin's last configuration.
     */
    public static void reset() {
        FOOD_RULES = VillagerFoodRules.EMPTY;
        COMPOST_IDS = Set.of();
        COMPOST_CHANCE = 0.3F;
        FOOD_ENABLED = true;
        FOOD_CHECK_CHANCE = 0.05;
        COMPOST_MAX_ITEMS = 20;
        COMPOST_MINIMUM_KEPT = 32;
        BONEMEAL_RETRY_DELAY = 40;
        BONEMEAL_WORK_DURATION = 80;
        FARM_RETARGET_DELAY = 20;
        FARM_STOP_COOLDOWN = 40;
        FARM_WORK_DURATION = 200;
    }
}
