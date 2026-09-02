package com.huidu.villagersdelight.impl26;

import com.huidu.villagersdelight.core.VillagerAiInjector;
import com.huidu.villagersdelight.core.VillagersDelightPlugin;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.behavior.GateBehavior;
import net.minecraft.world.entity.ai.behavior.HarvestFarmland;
import net.minecraft.world.entity.ai.behavior.TradeWithVillager;
import net.minecraft.world.entity.ai.behavior.ShufflingList;
import net.minecraft.world.entity.ai.sensing.SensorType;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.WorkAtComposter;
import net.minecraft.world.entity.ai.behavior.UseBonemeal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.VillagerCareerChangeEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Replaces the vanilla HarvestFarmland behavior in farmer-villager brains with
// VillagerFarmBehavior. Replacement happens on spawn (covers cured zombie villagers too)
// and when a chunk loads. All handlers run on the entity's owning region.
// 26.x stores behaviors per priority: Map<Integer, Map<Activity, Set<BehaviorControl<?>>>>
// in Brain.availableBehaviorsByPriority. The farm behavior lives inside the WORK
// RunOne (a GateBehavior), so the replacement descends into every gate's
// ShufflingList and swaps the entry while keeping its weight.
public final class NmsVillagerAi implements VillagerAiInjector, Listener {

    private static final Field AVAILABLE_BEHAVIORS_BY_PRIORITY = findField(Brain.class, "availableBehaviorsByPriority");

    static volatile Set<String> ALLOWED_SEEDS = Set.of();
    static volatile boolean SHARE_ENABLED = false;
    static volatile Set<String> SHARE_IDS = Set.of();
    static volatile Set<Item> FOOD_ITEMS = Set.of();
    /** CE item ids villagers may compost (VillagerWorkAtComposter); the vanilla table is untouched. */
    static volatile Set<String> COMPOST_IDS = Set.of();
    /** Compost success probability for CE items without a vanilla table entry. */
    static volatile float COMPOST_CHANCE = 0.3F;
    private static final Field GATE_BEHAVIORS = findField(GateBehavior.class, "behaviors");
    private static final Field SHUFFLING_ENTRIES = findField(ShufflingList.class, "entries");
    private static final Constructor<?> WEIGHTED_ENTRY_CTOR = findWeightedEntryConstructor();
    private static final Field BRAIN_SENSORS = findField(Brain.class, "sensors");
    private static final Field FOOD_LEVEL = findField(Villager.class, "foodLevel");

    private VillagersDelightPlugin plugin;
    private boolean installed;

    private static Field findField(Class<?> clazz, String name) {
        try {
            Field field = clazz.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(clazz.getName() + "." + name + " not found", e);
        }
    }

    private static Constructor<?> findWeightedEntryConstructor() {
        try {
            Constructor<?> ctor = ShufflingList.WeightedEntry.class.getDeclaredConstructor(Object.class, int.class);
            ctor.setAccessible(true);
            return ctor;
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("ShufflingList.WeightedEntry ctor not found", e);
        }
    }

    @Override
    public void install() {
        if (this.installed) {
            return;
        }
        this.plugin = JavaPlugin.getPlugin(VillagersDelightPlugin.class);
        this.plugin.getServer().getPluginManager().registerEvents(this, this.plugin);
        this.installed = true;
    }

    @Override
    public void shutdown() {
        if (!this.installed) {
            return;
        }
        this.installed = false;
    }

    @Override
    public void configureFoodItems(java.util.List<String> itemIds) {
        if (this.plugin == null) {
            return;
        }
        try {
            Set<Item> foods = new HashSet<>();
            if (itemIds != null) {
                for (String id : itemIds) {
                    Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
                    if (item != null && !Villager.FOOD_POINTS.containsKey(item)) {
                        foods.add(item);
                    }
                }
            }
            FOOD_ITEMS = Set.copyOf(foods);
            VillagersDelightPlugin.debug("pickup: configured villager food items=" + itemIds);
        } catch (Throwable t) {
            this.plugin.getLogger().warning("Failed to configure villager food items: " + t);
        }
    }

    @Override
    public void configureCeCompost(java.util.Set<String> ceItemIds) {
        COMPOST_IDS = ceItemIds == null ? Set.of() : Set.copyOf(ceItemIds);
        VillagersDelightPlugin.debug("compost: villager CE compost items set to " + COMPOST_IDS);
    }

    @Override
    public void configurePickupFilter(java.util.Set<String> seedKeys) {
        ALLOWED_SEEDS = seedKeys == null ? Set.of() : Set.copyOf(seedKeys);
        VillagersDelightPlugin.debug("pickup: wanted-item filter seeds=" + ALLOWED_SEEDS);
    }

    @Override
    public void configureShareItems(boolean enabled, java.util.Set<String> itemIds) {
        SHARE_ENABLED = enabled;
        SHARE_IDS = itemIds == null ? Set.of() : Set.copyOf(itemIds);
        VillagersDelightPlugin.debug("share: enabled=" + SHARE_ENABLED + " ids=" + SHARE_IDS);
    }

    static boolean isShareId(String id) {
        return SHARE_IDS.contains(id);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntitySpawn(EntitySpawnEvent event) {
        if (event.getEntity() instanceof org.bukkit.entity.Villager villager) {
            this.replace(villager);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCareerChange(VillagerCareerChangeEvent event) {
        if (event.getProfession() == org.bukkit.entity.Villager.Profession.FARMER) {
            scheduleReplaceAfterCareerChange(event.getEntity());
        }
    }

    private void scheduleReplaceAfterCareerChange(org.bukkit.entity.Villager villager) {
        // The event fires before vanilla applies the new profession and refreshes the brain.
        villager.getScheduler().runDelayed(this.plugin, task -> this.replace(villager), null, 1L);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (org.bukkit.entity.Entity entity : event.getEntities()) {
            if (entity instanceof org.bukkit.entity.Villager villager) {
                this.replace(villager);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void replace(org.bukkit.entity.Villager bukkitVillager) {
        if (!Bukkit.isOwnedByCurrentRegion(bukkitVillager)) {
            bukkitVillager.getScheduler().run(this.plugin, task -> replace(bukkitVillager), null);
            return;
        }
        try {
            Villager handle = ((org.bukkit.craftbukkit.entity.CraftVillager) bukkitVillager).getHandle();
            Brain<Villager> brain = handle.getBrain();
            Map<Integer, Map<Activity, Set<BehaviorControl<?>>>> byPriority =
                    (Map<Integer, Map<Activity, Set<BehaviorControl<?>>>>) AVAILABLE_BEHAVIORS_BY_PRIORITY.get(brain);
            if (byPriority == null) {
                VillagersDelightPlugin.debug("replace: brain has no availableBehaviorsByPriority");
                return;
            }

            boolean farmer = bukkitVillager.getProfession() == org.bukkit.entity.Villager.Profession.FARMER;
            boolean foodBehaviorInstalled = byPriority.values().stream()
                    .map(activities -> activities.get(Activity.CORE))
                    .filter(java.util.Objects::nonNull)
                    .flatMap(Set::stream)
                    .anyMatch(VillagerConfiguredFood.class::isInstance);

            if (farmer) {
                // Install the rich-soil aware secondary-POI sensor so a pure CE farm populates SECONDARY_JOB_SITE.
                try {
                    Map<Object, Object> sensors = (Map<Object, Object>) BRAIN_SENSORS.get(brain);
                    // Keep the sensor replacement idempotent for repeated entity lifecycle events.
                    if (!(sensors.get(SensorType.SECONDARY_POIS) instanceof VillagerSecondaryPoiSensor)) {
                        sensors.put(SensorType.SECONDARY_POIS, new VillagerSecondaryPoiSensor());
                    }
                    // Restrict wanted-item detection to configured seeds so villagers ignore same-base items.
                    if (!(sensors.get(SensorType.NEAREST_ITEMS) instanceof VillagerWantedItemSensor)) {
                        sensors.put(SensorType.NEAREST_ITEMS, new VillagerWantedItemSensor());
                    }
                } catch (IllegalAccessException e) {
                    this.plugin.getLogger().warning("Failed to install villager secondary-POI sensor: " + e.getMessage());
                }
            }
            int replaced = 0;
            for (Map.Entry<Integer, Map<Activity, Set<BehaviorControl<?>>>> priorityEntry : byPriority.entrySet()) {
                Set<BehaviorControl<?>> core = priorityEntry.getValue().get(Activity.CORE);
                if (core != null) {
                    if (!foodBehaviorInstalled) {
                        core.add(new VillagerConfiguredFood());
                        foodBehaviorInstalled = true;
                    }
                    if (farmer && core.stream().noneMatch(c -> c instanceof VillagerShareItems)) {
                        core.add(new VillagerShareItems());
                    }
                }
                if (!farmer) {
                    continue;
                }
                for (Set<BehaviorControl<?>> controls : priorityEntry.getValue().values()) {
                    if (controls == null) {
                        continue;
                    }
                    for (BehaviorControl<?> control : List.copyOf(controls)) {
                        if (control instanceof HarvestFarmland hf && !(hf instanceof VillagerFarmBehavior)) {
                            controls.remove(control);
                            controls.add(new VillagerFarmBehavior());
                            replaced++;
                        } else if (control.getClass() == UseBonemeal.class) {
                            controls.remove(control);
                            controls.add(new VillagerUseBonemeal());
                            replaced++;
                        } else if (control instanceof GateBehavior<?> gate) {
                            replaced += this.replaceInsideGate(gate);
                        }
                    }
                }
            }
            if (replaced > 0) {
                VillagersDelightPlugin.debug("farm: installed at " + bukkitVillager.getLocation() + " replaced=" + replaced);
            }
        } catch (IllegalAccessException e) {
            this.plugin.getLogger().warning("Failed to replace villager farm behavior: " + e.getMessage());
        }
    }

    private static final class VillagerConfiguredFood extends Behavior<Villager> {

        private VillagerConfiguredFood() {
            super(Map.of());
        }

        @Override
        protected boolean checkExtraStartConditions(ServerLevel level, Villager villager) {
            return !FOOD_ITEMS.isEmpty()
                    && level.getRandom().nextInt(20) == 0
                    && villager.getAge() == 0
                    && !villager.isSleeping()
                    && availableFoodPoints(villager) < Villager.BREEDING_FOOD_THRESHOLD;
        }

        @Override
        protected void start(ServerLevel level, Villager villager, long gameTime) {
            int points = availableFoodPoints(villager);
            int needed = Villager.BREEDING_FOOD_THRESHOLD - points;
            if (needed <= 0) {
                return;
            }
            SimpleContainer inventory = villager.getInventory();
            int consumed = 0;
            for (int slot = 0; slot < inventory.getContainerSize() && consumed < needed; slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (FOOD_ITEMS.contains(stack.getItem())) {
                    int amount = Math.min(stack.getCount(), needed - consumed);
                    inventory.removeItem(slot, amount);
                    consumed += amount;
                }
            }
            if (consumed > 0) {
                setFoodLevel(villager, foodLevel(villager) + consumed);
            }
        }

        private static int availableFoodPoints(Villager villager) {
            SimpleContainer inventory = villager.getInventory();
            int points = foodLevel(villager);
            for (Map.Entry<Item, Integer> food : Villager.FOOD_POINTS.entrySet()) {
                points += inventory.countItem(food.getKey()) * food.getValue();
            }
            return points;
        }

        private static int foodLevel(Villager villager) {
            try {
                return FOOD_LEVEL.getInt(villager);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Cannot read villager food level", e);
            }
        }

        private static void setFoodLevel(Villager villager, int value) {
            try {
                FOOD_LEVEL.setInt(villager, value);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Cannot write villager food level", e);
            }
        }
    }

    private int replaceInsideGate(GateBehavior<?> gate) {
        int replaced = 0;
        try {
            ShufflingList<?> list = (ShufflingList<?>) GATE_BEHAVIORS.get(gate);
            @SuppressWarnings("unchecked")
            List<ShufflingList.WeightedEntry<?>> entries = (List<ShufflingList.WeightedEntry<?>>) SHUFFLING_ENTRIES.get(list);
            for (int i = 0; i < entries.size(); i++) {
                ShufflingList.WeightedEntry<?> entry = (ShufflingList.WeightedEntry<?>) entries.get(i);
                Object data = entry.getData();
                if (data instanceof HarvestFarmland hf && !(hf instanceof VillagerFarmBehavior)) {
                    entries.set(i, (ShufflingList.WeightedEntry<?>) WEIGHTED_ENTRY_CTOR.newInstance(new VillagerFarmBehavior(), entry.getWeight()));
                    replaced++;
                } else if (data.getClass() == UseBonemeal.class) {
                    entries.set(i, (ShufflingList.WeightedEntry<?>) WEIGHTED_ENTRY_CTOR.newInstance(new VillagerUseBonemeal(), entry.getWeight()));
                    replaced++;
                } else if (data instanceof WorkAtComposter wac && !(wac instanceof VillagerWorkAtComposter)) {
                    // CE-aware composter: accepts configured CE items without touching the vanilla table.
                    entries.set(i, (ShufflingList.WeightedEntry<?>) WEIGHTED_ENTRY_CTOR.newInstance(new VillagerWorkAtComposter(), entry.getWeight()));
                    replaced++;
                } else if (data instanceof TradeWithVillager tw && !(tw instanceof VillagerTradeWithVillager)) {
                    entries.set(i, (ShufflingList.WeightedEntry<?>) WEIGHTED_ENTRY_CTOR.newInstance(new VillagerTradeWithVillager(), entry.getWeight()));
                    replaced++;
                } else if (data instanceof GateBehavior<?> innerGate) {
                    replaced += this.replaceInsideGate(innerGate);
                }
            }
        } catch (ReflectiveOperationException e) {
            this.plugin.getLogger().warning("Failed to replace farm behavior inside gate: " + e.getMessage());
        }
        return replaced;
    }

    private String describe(BehaviorControl<?> control) {
        if (control instanceof GateBehavior<?> gate) {
            try {
                ShufflingList<?> list = (ShufflingList<?>) GATE_BEHAVIORS.get(gate);
                @SuppressWarnings("unchecked")
            List<ShufflingList.WeightedEntry<?>> entries = (List<ShufflingList.WeightedEntry<?>>) SHUFFLING_ENTRIES.get(list);
                StringBuilder sb = new StringBuilder(gate.getClass().getSimpleName()).append('(');
                for (int i = 0; i < entries.size(); i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    Object data = ((ShufflingList.WeightedEntry<?>) entries.get(i)).getData();
                    sb.append(data.getClass().getSimpleName());
                }
                return sb.append(')').toString();
            } catch (ReflectiveOperationException e) {
                return gate.getClass().getSimpleName();
            }
        }
        return control.getClass().getSimpleName();
    }
}
