package com.huidu.villagersdelight.impl1211;

import com.huidu.villagersdelight.impl1211.common.VillagerAiSettings;
import com.huidu.villagersdelight.impl1211.common.VillagerBackpack;
import net.kyori.adventure.text.Component;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import java.util.function.BooleanSupplier;
import com.huidu.villagersdelight.impl1211.common.VillagerCollectWantedItem;
import com.huidu.villagersdelight.impl1211.common.VillagerItems;
import com.huidu.villagersdelight.impl1211.common.VillagerUseBonemeal;
import com.huidu.villagersdelight.impl1211.common.VillagerWantedItemSensor;
import com.huidu.villagersdelight.impl1211.common.VillagerWorkAtComposter;
import com.huidu.villagersdelight.core.VillagerAiInjector;
import com.huidu.villagersdelight.core.VillagerFoodRules;
import com.huidu.villagersdelight.core.VillagersDelightConfig;
import com.huidu.villagersdelight.core.VillagersDelightPlugin;
import com.huidu.villagersdelight.core.CeItemAccess;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
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
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.WorkAtComposter;
import net.minecraft.world.entity.ai.behavior.UseBonemeal;
import net.minecraft.world.item.ItemStack;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftVillager;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.VillagerCareerChangeEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.craftbukkit.inventory.CraftItemStack;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// Replaces the vanilla HarvestFarmland behavior in farmer-villager brains with
// VillagerFarmBehavior. Replacement happens on spawn (covers cured zombie villagers too)
// and when a chunk loads. All handlers run on the entity's owning region.
// 26.x stores behaviors per priority: Map<Integer, Map<Activity, Set<BehaviorControl<?>>>>
// in Brain.availableBehaviorsByPriority. The farm behavior lives inside the WORK
// RunOne (a GateBehavior), so the replacement descends into every gate's
// ShufflingList and swaps the entry while keeping its weight.
public final class NmsVillagerAi implements VillagerAiInjector, Listener {

    private static final Field AVAILABLE_BEHAVIORS_BY_PRIORITY = findField(Brain.class, "availableBehaviorsByPriority");

    // True in custom-id pickup mode: CE items are collected by VillagerCollectWantedItem instead of
    // the vanilla tag path. False keeps legacy datapack-tag pickup and must not install the collect
    // behaviour, otherwise a CE item already admitted by the tag would be collected twice.
    static volatile boolean CUSTOM_ID_PICKUP = false;
    private static final Field GATE_BEHAVIORS = findField(GateBehavior.class, "behaviors");
    private static final Field SHUFFLING_ENTRIES = findField(ShufflingList.class, "entries");
    private static final Constructor<?> WEIGHTED_ENTRY_CTOR = findWeightedEntryConstructor();
    private static final Field BRAIN_SENSORS = findField(Brain.class, "sensors");
    private static final Field FOOD_LEVEL = findField(Villager.class, "foodLevel");

    private VillagersDelightPlugin plugin;
    private boolean installed;
    private final Map<UUID, ScheduledTask> brainChecks =
            new ConcurrentHashMap<>();
    // Villagers whose brain this injector has actually modified. Withdrawing a behaviour only has to visit
    // these, which keeps the work on each villager's own region thread instead of reading every world here.
    private final Map<UUID, org.bukkit.entity.Villager> injectedVillagers =
            new ConcurrentHashMap<>();
    // Every behaviour class this injector actually put into a brain, recorded at the moment of injection.
    // Shutdown must not resolve these by name: the shared behaviour classes are relocated into the layer
    // package's common subpackage while this layer's own classes sit in the layer package itself, and one of
    // them is nested here, so a name built from the layer package misses most of them and those behaviours
    // would survive the disable.
    private final Set<Class<?>> installedBehaviorTypes = ConcurrentHashMap.newKeySet();
    // Restore sensors only into their original brain; both maps share the same monitor.
    private final Map<UUID, Map<Object, Object>> replacedSensors = new HashMap<>();
    private final Map<UUID, Brain<?>> sensorBrains = new HashMap<>();
    // Failure warnings are cooled down per key, so a brain that keeps failing cannot flood the log while a
    // failure still stays visible instead of dropping to a debug line nobody reads.
    private final Map<String, Long> warnedFailures = new ConcurrentHashMap<>();
    private static final long FAILURE_WARNING_COOLDOWN_MS = 60_000L;

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
    public Inventory openBackpack(Player player,
            org.bukkit.entity.Villager villager, Component title,
            org.bukkit.inventory.ItemStack padding, BooleanSupplier accessible) {
        if (!accessible.getAsBoolean()) return null;
        return new VillagerBackpack(((CraftVillager) villager).getHandle().getInventory(),
                CraftItemStack.asNMSCopy(padding), accessible).open(((CraftPlayer) player).getHandle(), title);
    }

    @Override
    public void shutdown() {
        if (!this.installed) {
            return;
        }
        this.installed = false;
        brainChecks.values().forEach(ScheduledTask::cancel);
        brainChecks.clear();
        // Withdraw before dropping the villager references: the behaviours read the static settings below
        // on every tick, so leaving either in place would keep this layer's classes harvesting, planting
        // and composting on behalf of a plugin that is already disabled, with a soon-dead classloader.
        withdrawAllBehaviors();
        injectedVillagers.clear();
        // Best-effort static state reset so a disabled plugin cannot keep feeding the behaviours rules
        // that its own config no longer backs.
        try {
            VillagerAiSettings.reset();
        } catch (RuntimeException e) {
            VillagersDelightPlugin.debug("shutdown: villager AI settings reset failed: " + e.getMessage());
        }
    }

    // Removes every behaviour this injector installed from every villager it touched. One failing
    // villager must not abort the rest.
    private void withdrawAllBehaviors() {
        for (org.bukkit.entity.Villager villager : List.copyOf(injectedVillagers.values())) {
            try {
                if (!villager.isValid()) {
                    continue;
                }
                if (Bukkit.isOwnedByCurrentRegion(villager)) {
                    removeInjectedFrom(villager);
                } else {
                    // Entities are only readable on their owning region thread, so a villager owned by
                    // another region is handled by its own scheduler instead of from here.
                    villager.getScheduler().run(this.plugin, task -> removeInjectedFrom(villager), null);
                }
            } catch (RuntimeException e) {
                warnFailureOnce("withdraw:" + villager.getUniqueId(), "shutdown: behavior withdrawal for "
                        + villager.getUniqueId() + " failed: " + e.getMessage());
            }
        }
    }

    private void removeInjectedFrom(org.bukkit.entity.Villager villager) {
        Villager handle = ((CraftVillager) villager).getHandle();
        Brain<?> brain = handle.getBrain();
        if (handle.level() instanceof ServerLevel level) handle.getBrain().stopAll(level, handle);
        // removeBehavior and removeEmptyGates already contain their own failure handling, so a
        // villager whose brain cannot be read leaves the remaining ones to be withdrawn.
        for (Class<?> type : installedBehaviorTypes) {
            removeBehavior(brain, type);
        }
        removeEmptyGates(brain);
        restoreReplacedSensors(villager.getUniqueId(), brain);
    }

    // Puts the sensors this injector replaced back, including removing a type that had none before.
    @SuppressWarnings("unchecked")
    private void restoreReplacedSensors(UUID id, Brain<?> brain) {
        Map<Object, Object> replaced;
        synchronized (replacedSensors) {
            replaced = replacedSensors.remove(id);
            if (sensorBrains.remove(id) != brain) return;
        }
        if (replaced == null || replaced.isEmpty()) {
            return;
        }
        try {
            Map<Object, Object> sensors = (Map<Object, Object>) BRAIN_SENSORS.get(brain);
            for (Map.Entry<Object, Object> entry : replaced.entrySet()) {
                if (entry.getValue() == null) {
                    sensors.remove(entry.getKey());
                } else {
                    sensors.put(entry.getKey(), entry.getValue());
                }
            }
        } catch (IllegalAccessException e) {
            warnFailureOnce("uninstall:sensors", "Failed to restore villager sensors: " + e.getMessage());
        }
    }

    // Records a behaviour class the moment it is installed, so withdrawal never has to build its name.
    private void rememberInjectedBehavior(Class<?> type) {
        if (type != null) {
            installedBehaviorTypes.add(type);
        }
    }

    // Records a replaced sensor together with the value that was there before, keeping the first value so a
    // repeated lifecycle event cannot overwrite the original with this injector's own sensor.
    private void rememberReplacedSensor(UUID id, Object type, Object previous) {
        synchronized (replacedSensors) {
            Map<Object, Object> forVillager = replacedSensors.computeIfAbsent(id, key -> new HashMap<>());
            if (!forVillager.containsKey(type)) {
                forVillager.put(type, previous);
            }
        }
    }

    // One cooled warning per key: a class that keeps failing warns once a minute instead of once per villager.
    private void warnFailureOnce(String key, String message) {
        long now = System.currentTimeMillis();
        Long last = warnedFailures.get(key);
        if (last != null && now - last < FAILURE_WARNING_COOLDOWN_MS) {
            VillagersDelightPlugin.debug(message);
            return;
        }
        warnedFailures.put(key, now);
        if (this.plugin == null) {
            VillagersDelightPlugin.debug(message);
        } else {
            this.plugin.getLogger().warning(message);
        }
    }

    @Override
    public void configureFoodItems(VillagerFoodRules rules) {
        VillagerAiSettings.FOOD_RULES = rules;
    }

    @Override
    public void configureCeCompost(Set<String> ceItemIds) {
        VillagerAiSettings.COMPOST_IDS = ceItemIds == null ? Set.of() : Set.copyOf(ceItemIds);
        VillagersDelightPlugin.debug("compost: villager CE compost items set to " + VillagerAiSettings.COMPOST_IDS);
    }

    @Override
    public void configureBehavior(VillagersDelightConfig.BehaviorSettings settings) {
        VillagerAiSettings.apply(settings);
    }

    @Override
    public boolean supportsCustomIdPickup() {
        return true;
    }

    @Override
    public void setCustomIdPickup(boolean enabled) {
        if (CUSTOM_ID_PICKUP == enabled) {
            return;
        }
        if (!enabled) {
            // Switch back to legacy datapack-tag pickup: withdraw the injected collect behaviour so a
            // CE item the widened tag already admits is not collected twice. The flag flips only once the
            // withdrawal has been issued, so a failure here cannot leave the config and the brains out of step.
            uninstallBehaviors(Set.of(VillagerCollectWantedItem.class));
        }
        CUSTOM_ID_PICKUP = enabled;
        if (enabled) {
            // The spawn, entity-load, career-change and brain-rebuild paths are what normally install the
            // collect behaviour, and none of them re-runs for a villager that already exists. Without this
            // pass, switching use-datapack-tag off only stops new installs and the mode stays inert until
            // the villagers reload. The flag is already set, so a villager replaced concurrently installs it.
            installCollectBehavior(VillagerCollectWantedItem.class);
        }
    }

    @Override
    public void uninstallBehaviors(Set<Class<?>> behaviorTypes) {
        if (behaviorTypes == null || behaviorTypes.isEmpty()) {
            return;
        }
        for (org.bukkit.entity.Villager villager : List.copyOf(injectedVillagers.values())) {
            if (!villager.isValid()) {
                injectedVillagers.remove(villager.getUniqueId());
                continue;
            }
            if (Bukkit.isOwnedByCurrentRegion(villager)) {
                removeFrom(villager, behaviorTypes);
            } else {
                // Reading another region's entity list from here is not region-safe, so the removal is handed
                // to the villager's own scheduler instead of scanning every world on the calling thread.
                villager.getScheduler().run(this.plugin, task -> removeFrom(villager, behaviorTypes), null);
            }
        }
    }

    private void removeFrom(org.bukkit.entity.Villager villager, Set<Class<?>> behaviorTypes) {
        Villager handle = ((CraftVillager) villager).getHandle();
        for (Class<?> type : behaviorTypes) {
            removeBehavior(handle.getBrain(), type);
        }
        removeEmptyGates(handle.getBrain());
    }

    // Install side of the collect behaviour for villagers that already exist. uninstallBehaviors is the
    // symmetric withdrawal; this exists because every other install path is tied to a villager lifecycle
    // event (spawn, entity load, career change, brain rebuild) that a running villager never fires again.
    private void installCollectBehavior(Class<?> behaviorType) {
        for (org.bukkit.entity.Villager villager : List.copyOf(injectedVillagers.values())) {
            if (!villager.isValid()) {
                injectedVillagers.remove(villager.getUniqueId());
                continue;
            }
            try {
                if (Bukkit.isOwnedByCurrentRegion(villager)) {
                    addCollectIfMissing(villager, behaviorType);
                } else {
                    // The brain is only touched on the villager's own region thread, same as the
                    // withdrawal path: reading another region's entity state from here is not
                    // region-safe. The behaviour is still mode-gated, so a task that runs late after
                    // another switch is a no-op.
                    villager.getScheduler().run(this.plugin, task -> addCollectIfMissing(villager, behaviorType), null);
                }
            } catch (RuntimeException e) {
                // One unusable villager must not abort the pass; the rest still need the behaviour.
                VillagersDelightPlugin.debug("install: " + behaviorType.getSimpleName()
                        + " for " + villager.getUniqueId() + " failed: " + e.getMessage());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void addCollectIfMissing(org.bukkit.entity.Villager villager, Class<?> behaviorType) {
        try {
            if (!CUSTOM_ID_PICKUP) {
                return;
            }
            Villager handle = ((CraftVillager) villager).getHandle();
            Map<Integer, Map<Activity, Set<BehaviorControl<?>>>> byPriority =
                    (Map<Integer, Map<Activity, Set<BehaviorControl<?>>>>) AVAILABLE_BEHAVIORS_BY_PRIORITY.get(handle.getBrain());
            if (byPriority == null) {
                return;
            }
            boolean installed = byPriority.values().stream()
                    .map(activities -> activities.get(Activity.CORE))
                    .filter(Objects::nonNull)
                    .flatMap(Set::stream)
                    .anyMatch(behaviorType::isInstance);
            if (installed) {
                rememberInjectedBehavior(behaviorType);
                return;
            }
            for (Map<Activity, Set<BehaviorControl<?>>> activities : byPriority.values()) {
                Set<BehaviorControl<?>> core = activities.get(Activity.CORE);
                if (core != null) {
                    core.add(new VillagerCollectWantedItem());
                    rememberInjectedBehavior(behaviorType);
                    return;
                }
            }
            // No CORE activity set to attach to: the villager's next replace() still installs it once
            // the mode flag is set.
            VillagersDelightPlugin.debug("install: no CORE activity for " + villager.getUniqueId());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError t) {
            warnFailureOnce("install:" + behaviorType.getSimpleName(), "install: " + behaviorType.getSimpleName()
                    + " for " + villager.getUniqueId() + " failed: " + t);
        }
    }

    // Removes any behavior whose runtime class equals behaviorType from every activity set, descending
    // into GateBehavior shuffling lists. Exact class match keeps it idempotent (a replaced behavior's
    // class differs from the original, so it is never removed twice). Returns the number removed.
    private int removeBehavior(Brain<?> brain, Class<?> behaviorType) {
        try {
            Map<Integer, Map<Activity, Set<BehaviorControl<?>>>> byPriority =
                    (Map<Integer, Map<Activity, Set<BehaviorControl<?>>>>) AVAILABLE_BEHAVIORS_BY_PRIORITY.get(brain);
            if (byPriority == null) {
                return 0;
            }
            int removed = 0;
            for (Map<Activity, Set<BehaviorControl<?>>> activities : byPriority.values()) {
                for (Set<BehaviorControl<?>> controls : activities.values()) {
                    if (controls == null) {
                        continue;
                    }
                    for (BehaviorControl<?> control : List.copyOf(controls)) {
                        if (control.getClass() == behaviorType) {
                            controls.remove(control);
                            BehaviorControl<?> original = vanillaBehavior(behaviorType);
                            if (original != null) controls.add(original);
                            removed++;
                        } else if (control instanceof GateBehavior<?> gate) {
                            removed += removeNested(gate, behaviorType);
                        }
                    }
                }
            }
            return removed;
        } catch (IllegalAccessException e) {
            warnFailureOnce("uninstall:" + behaviorType.getSimpleName(),
                    "uninstall: " + behaviorType.getSimpleName() + " failed: " + e.getMessage());
            return 0;
        }
    }

    // Recurses through a GateBehavior's weighted entries, removing exact-class matches. An entry that
    // is itself a GateBehavior is descended into.
    @SuppressWarnings("unchecked")
    private int removeNested(GateBehavior<?> gate, Class<?> behaviorType) {
        int removed = 0;
        try {
            ShufflingList<?> list = (ShufflingList<?>) GATE_BEHAVIORS.get(gate);
            List<ShufflingList.WeightedEntry<?>> entries = (List<ShufflingList.WeightedEntry<?>>) SHUFFLING_ENTRIES.get(list);
            for (int i = entries.size() - 1; i >= 0; i--) {
                ShufflingList.WeightedEntry<?> entry = entries.get(i);
                Object data = entry.getData();
                if (data instanceof BehaviorControl<?> control && control.getClass() == behaviorType) {
                    BehaviorControl<?> original = vanillaBehavior(behaviorType);
                    if (original == null) {
                        entries.remove(i);
                    } else {
                        entries.set(i, (ShufflingList.WeightedEntry<?>)
                                WEIGHTED_ENTRY_CTOR.newInstance(original, entry.getWeight()));
                    }
                    removed++;
                } else if (data instanceof GateBehavior<?> innerGate) {
                    removed += removeNested(innerGate, behaviorType);
                }
            }
        } catch (ReflectiveOperationException e) {
            warnFailureOnce("uninstall:nested:" + behaviorType.getSimpleName(),
                    "uninstall: nested " + behaviorType.getSimpleName() + " failed: " + e.getMessage());
        }
        return removed;
    }

    // Removes GateBehaviors that became empty after unloading their injected entries. Top-level empty
    // sets are left alone: vanilla repopulates those per activity; only vacated gates are harmful.
    private void removeEmptyGates(Brain<?> brain) {
        try {
            Map<Integer, Map<Activity, Set<BehaviorControl<?>>>> byPriority =
                    (Map<Integer, Map<Activity, Set<BehaviorControl<?>>>>) AVAILABLE_BEHAVIORS_BY_PRIORITY.get(brain);
            if (byPriority == null) {
                return;
            }
            for (Map<Activity, Set<BehaviorControl<?>>> activities : byPriority.values()) {
                for (Set<BehaviorControl<?>> controls : activities.values()) {
                    if (controls == null) {
                        continue;
                    }
                    for (BehaviorControl<?> control : List.copyOf(controls)) {
                        if (control instanceof GateBehavior<?> gate && isEmptyGate(gate)) {
                            controls.remove(control);
                        }
                    }
                }
            }
        } catch (IllegalAccessException e) {
            warnFailureOnce("uninstall:gates", "uninstall: empty-gate scan failed: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean isEmptyGate(GateBehavior<?> gate) {
        try {
            ShufflingList<?> list = (ShufflingList<?>) GATE_BEHAVIORS.get(gate);
            List<ShufflingList.WeightedEntry<?>> entries = (List<ShufflingList.WeightedEntry<?>>) SHUFFLING_ENTRIES.get(list);
            return entries == null || entries.isEmpty();
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntitySpawn(EntitySpawnEvent event) {
        if (event.getEntity() instanceof org.bukkit.entity.Villager villager) {
            this.replace(villager);
        }
    }

    // A villager only becomes a farmer after claiming a workstation, which happens after spawn when
    // the profession is still NONE. Inject right away on the profession change instead of waiting
    // when the profession changes.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCareerChange(VillagerCareerChangeEvent event) {
        scheduleReplaceAfterCareerChange(event.getEntity());
    }

    private void scheduleReplaceAfterCareerChange(org.bukkit.entity.Villager villager) {
        // The event fires before vanilla applies the new profession and refreshes the brain.
        villager.getScheduler().runDelayed(this.plugin, task -> this.replace(villager), null, 1L);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
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
            Villager handle = ((CraftVillager) bukkitVillager).getHandle();
            Brain<Villager> brain = handle.getBrain();
            synchronized (replacedSensors) {
                if (sensorBrains.put(bukkitVillager.getUniqueId(), brain) != brain) {
                    replacedSensors.remove(bukkitVillager.getUniqueId());
                }
            }
            Map<Integer, Map<Activity, Set<BehaviorControl<?>>>> byPriority =
                    (Map<Integer, Map<Activity, Set<BehaviorControl<?>>>>) AVAILABLE_BEHAVIORS_BY_PRIORITY.get(brain);
            if (byPriority == null) {
                VillagersDelightPlugin.debug("replace: brain has no availableBehaviorsByPriority");
                return;
            }

            boolean farmer = bukkitVillager.getProfession() == org.bukkit.entity.Villager.Profession.FARMER;
            boolean foodBehaviorInstalled = byPriority.values().stream()
                    .map(activities -> activities.get(Activity.CORE))
                    .filter(Objects::nonNull)
                    .flatMap(Set::stream)
                    .anyMatch(VillagerConfiguredFood.class::isInstance);
            boolean collectBehaviorInstalled = byPriority.values().stream()
                    .map(activities -> activities.get(Activity.CORE))
                    .filter(Objects::nonNull)
                    .flatMap(Set::stream)
                    .anyMatch(VillagerCollectWantedItem.class::isInstance);

            // Every profession can collect shared food; only farmers need extra soil detection.
            {
                try {
                    Map<Object, Object> sensors = (Map<Object, Object>) BRAIN_SENSORS.get(brain);
                    // Keep the sensor replacement idempotent for repeated entity lifecycle events.
                    if (farmer && !(sensors.get(SensorType.SECONDARY_POIS) instanceof VillagerSecondaryPoiSensor)) {
                        rememberReplacedSensor(bukkitVillager.getUniqueId(), SensorType.SECONDARY_POIS,
                                sensors.get(SensorType.SECONDARY_POIS));
                        sensors.put(SensorType.SECONDARY_POIS, new VillagerSecondaryPoiSensor());
                    }
                    // CE identities must not admit plain items with the same base material.
                    if (!(sensors.get(SensorType.NEAREST_ITEMS) instanceof VillagerWantedItemSensor)) {
                        rememberReplacedSensor(bukkitVillager.getUniqueId(), SensorType.NEAREST_ITEMS,
                                sensors.get(SensorType.NEAREST_ITEMS));
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
                        rememberInjectedBehavior(VillagerConfiguredFood.class);
                        foodBehaviorInstalled = true;
                    }
                    // Legacy datapack pickup is handled by vanilla.
                    if (CUSTOM_ID_PICKUP && !collectBehaviorInstalled) {
                        core.add(new VillagerCollectWantedItem());
                        rememberInjectedBehavior(VillagerCollectWantedItem.class);
                        collectBehaviorInstalled = true;
                    }
                }
                for (Set<BehaviorControl<?>> controls : priorityEntry.getValue().values()) {
                    if (controls == null) continue;
                    for (BehaviorControl<?> control : List.copyOf(controls)) {
                        BehaviorControl<?> replacement = replacementFor(control, farmer);
                        if (replacement != control) {
                            rememberInjectedBehavior(replacement.getClass());
                            controls.remove(control);
                            controls.add(replacement);
                            replaced++;
                        } else if (control instanceof GateBehavior<?> gate) {
                            replaced += replaceInsideGate(gate, farmer);
                        }
                    }
                }
            }
            watchBrain(bukkitVillager, handle, brain);
            injectedVillagers.put(bukkitVillager.getUniqueId(), bukkitVillager);
            if (replaced > 0) {
                VillagersDelightPlugin.debug("farm: installed at " + bukkitVillager.getLocation() + " replaced=" + replaced);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError t) {
            // Catches Error too: a version mismatch surfaces as NoClassDefFoundError the first time an
            // injected behavior is constructed, and letting that escape aborts the entity-load handler
            // for every chunk that contains a villager.
            this.plugin.getLogger().warning("Failed to replace villager farm behavior: " + t);
        }
    }

    private void watchBrain(org.bukkit.entity.Villager entity, Villager handle, Brain<Villager> brain) {
        brainChecks.computeIfAbsent(entity.getUniqueId(), id -> {
            Brain<?>[] previous = {brain};
            // Age boundaries rebuild the brain without a spawn or profession event. Compare only
            // the brain reference every five seconds; traverse behaviors only after a replacement.
            return entity.getScheduler().runAtFixedRate(plugin, task -> {
                if (handle.getBrain() != previous[0]) {
                    replace(entity);
                    previous[0] = handle.getBrain();
                }
            }, () -> {
                brainChecks.remove(id);
                injectedVillagers.remove(id);
                synchronized (replacedSensors) {
                    replacedSensors.remove(id);
                    sensorBrains.remove(id);
                }
            }, 1L + Math.floorMod(id.hashCode(), 100), 100L);
        });
    }

    private static final class VillagerConfiguredFood extends Behavior<Villager> {
        private VillagerConfiguredFood() {
            super(Map.of());
        }

        @Override
        protected boolean checkExtraStartConditions(ServerLevel level, Villager villager) {
            if (VillagerAiSettings.FOOD_RULES.points().isEmpty() || !VillagerAiSettings.FOOD_ENABLED
                    || level.getRandom().nextDouble() >= VillagerAiSettings.FOOD_CHECK_CHANCE
                    || villager.getAge() != 0 || villager.isSleeping()
                    || foodLevel(villager) >= Villager.BREEDING_FOOD_THRESHOLD) return false;
            SimpleContainer inventory = villager.getInventory();
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                String id = configuredFoodId(inventory.getItem(slot));
                if (id != null && VillagerAiSettings.FOOD_RULES.consumable(id, VillagerItems.countHeld(villager, id), VillagerItems.isFarmer(villager), 12) > 0) return true;
            }
            return false;
        }

        @Override
        protected void start(ServerLevel level, Villager villager, long gameTime) {
            int food = foodLevel(villager);
            SimpleContainer inventory = villager.getInventory();
            VillagerFoodRules rules = VillagerAiSettings.FOOD_RULES;
            for (int slot = 0; slot < inventory.getContainerSize() && food < Villager.BREEDING_FOOD_THRESHOLD; slot++) {
                ItemStack stack = inventory.getItem(slot);
                String id = configuredFoodId(stack);
                if (id == null) continue;
                int amount = Math.min(stack.getCount(), rules.consumable(id, VillagerItems.countHeld(villager, id),
                        VillagerItems.isFarmer(villager), Villager.BREEDING_FOOD_THRESHOLD - food));
                if (amount <= 0) continue;
                // Write points before removing food so a reflective access failure cannot eat inventory.
                food += amount * rules.value(id);
                setFoodLevel(villager, food);
                inventory.removeItem(slot, amount);
            }
        }

        private static String configuredFoodId(ItemStack stack) {
            if (stack.isEmpty()) return null;
            var bukkit = CraftItemStack.asCraftMirror(stack);
            var custom = CeItemAccess.customItemId(bukkit);
            if (custom == null && Villager.FOOD_POINTS.containsKey(stack.getItem())) return null;
            String id = custom != null ? custom.toString() : bukkit.getType().getKey().toString();
            return VillagerAiSettings.FOOD_RULES.value(id) > 0 ? id : null;
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

    private static BehaviorControl<?> vanillaBehavior(Class<?> type) {
        if (type == VillagerFarmBehavior.class) return new HarvestFarmland();
        if (type == VillagerUseBonemeal.class) return new UseBonemeal();
        if (type == VillagerWorkAtComposter.class) return new WorkAtComposter();
        if (type == VillagerTradeWithVillager.class) return new TradeWithVillager();
        return null;
    }

    private BehaviorControl<?> replacementFor(BehaviorControl<?> control, boolean farmer) {
        if (control.getClass() == TradeWithVillager.class) return new VillagerTradeWithVillager();
        if (farmer) {
            if (control.getClass() == HarvestFarmland.class) return new VillagerFarmBehavior();
            if (control.getClass() == UseBonemeal.class) return new VillagerUseBonemeal();
            if (control.getClass() == WorkAtComposter.class) return new VillagerWorkAtComposter();
        }
        return control;
    }

    private int replaceInsideGate(GateBehavior<?> gate, boolean farmer) {
        int replaced = 0;
        try {
            ShufflingList<?> list = (ShufflingList<?>) GATE_BEHAVIORS.get(gate);
            @SuppressWarnings("unchecked")
            List<ShufflingList.WeightedEntry<?>> entries = (List<ShufflingList.WeightedEntry<?>>) SHUFFLING_ENTRIES.get(list);
            for (int i = 0; i < entries.size(); i++) {
                ShufflingList.WeightedEntry<?> entry = (ShufflingList.WeightedEntry<?>) entries.get(i);
                Object data = entry.getData();
                if (data instanceof BehaviorControl<?> control) {
                    BehaviorControl<?> replacement = replacementFor(control, farmer);
                    if (replacement != control) {
                        rememberInjectedBehavior(replacement.getClass());
                        entries.set(i, (ShufflingList.WeightedEntry<?>) WEIGHTED_ENTRY_CTOR.newInstance(replacement, entry.getWeight()));
                        replaced++;
                    } else if (data instanceof GateBehavior<?> innerGate) {
                        replaced += replaceInsideGate(innerGate, farmer);
                    }
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
