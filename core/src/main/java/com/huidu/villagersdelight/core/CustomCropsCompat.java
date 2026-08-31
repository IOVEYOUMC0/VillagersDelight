package com.huidu.villagersdelight.core;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.Optional;

/** Optional CustomCrops bridge. It deliberately has no compile-time CustomCrops dependency. */
public final class CustomCropsCompat {

    private static final String API_CLASS = "net.momirealms.customcrops.api.BukkitCustomCropsAPI";
    private static final String PLUGIN_CLASS = "net.momirealms.customcrops.api.BukkitCustomCropsPlugin";
    private static final String REGISTRIES_CLASS = "net.momirealms.customcrops.api.core.Registries";
    private static final String CROP_BLOCK_CLASS = "net.momirealms.customcrops.api.core.block.CropBlock";
    private static final String BREAK_REASON_CLASS = "net.momirealms.customcrops.api.core.block.BreakReason";

    private static volatile Access access;
    private static volatile boolean unavailable;

    private CustomCropsCompat() {
    }

    public record State(int point, int maxPoints) {
        public boolean mature() {
            return this.point >= this.maxPoints;
        }
    }

    @Nullable
    public static State stateAt(World world, int x, int y, int z) {
        Access a = getAccess();
        if (a == null) {
            return null;
        }
        try {
            Object api = a.api();
            if (api == null) {
                return null;
            }
            Object customWorld = a.apiGetWorld.invoke(api, world);
            if (customWorld == null) {
                return null;
            }
            Location location = new Location(world, x, y, z);
            Object pos = a.apiAdapt.invoke(api, location);
            Optional<?> optional = (Optional<?>) a.loadedState.invoke(customWorld, pos);
            if (optional.isEmpty()) {
                return null;
            }
            Object state = optional.get();
            Object type = a.stateType.invoke(state);
            if (type == null || !a.cropBlock.isInstance(type)) {
                return null;
            }
            Object config = a.cropConfig.invoke(type, state);
            if (config == null || !hasOnlyBlockStages(a, config)) {
                return null;
            }
            Object stage = a.stageForPoint.invoke(config, a.cropPoint.invoke(type, state));
            if (!isBlockStage(a.stageForm.invoke(stage))) {
                return null;
            }
            int point = ((Number) a.cropPoint.invoke(type, state)).intValue();
            int max = ((Number) a.maxPoints.invoke(config)).intValue();
            return new State(point, max);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    public static String cropId(ItemStack item) {
        Access a = getAccess();
        if (a == null || item == null || item.getType().isAir()) {
            return null;
        }
        try {
            Object itemManager = a.itemManager();
            if (itemManager == null) {
                return null;
            }
            String itemId = String.valueOf(a.itemId.invoke(itemManager, item));
            Object config = lookup(a.seedRegistry, itemId);
            if (config == null && itemId.contains(":")) {
                config = lookup(a.seedRegistry, itemId.substring(itemId.indexOf(':') + 1));
            }
            if (config == null) {
                return null;
            }
            return String.valueOf(a.configId.invoke(config));
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Returns whether the empty block is above a registered CustomCrops pot. */
    public static boolean isPotentialPlantingSpot(Location location) {
        Access a = getAccess();
        if (a == null || location == null || location.getWorld() == null || !location.getBlock().getType().isAir()) {
            return false;
        }
        try {
            Object itemManager = a.itemManager();
            String blockId = String.valueOf(a.blockId.invoke(itemManager, location.clone().subtract(0, 1, 0).getBlock()));
            return lookup(a.potRegistry, blockId) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean canPlantAt(Location location, String cropId) {
        Access a = getAccess();
        if (a == null || location == null || cropId == null || !isPotentialPlantingSpot(location)) {
            return false;
        }
        try {
            Object config = lookup(a.cropRegistry, cropId);
            if (config == null || !hasOnlyBlockStages(a, config)) {
                return false;
            }
            Object itemManager = a.itemManager();
            String blockId = String.valueOf(a.blockId.invoke(itemManager, location.clone().subtract(0, 1, 0).getBlock()));
            Object pot = lookup(a.potRegistry, blockId);
            if (pot == null) {
                return false;
            }
            Object potId = a.potConfigId.invoke(pot);
            Object whitelist = a.potWhitelist.invoke(config);
            return whitelist instanceof java.util.Set<?> set && set.contains(potId);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean place(Location location, String cropId) {
        Access a = getAccess();
        if (a == null) {
            return false;
        }
        try {
            Object api = a.api();
            return api != null && Boolean.TRUE.equals(a.placeCrop.invoke(api, location, cropId, 0));
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean harvest(Location location) {
        Access a = getAccess();
        if (a == null || location == null) {
            return false;
        }
        try {
            Object api = a.api();
            if (api == null) {
                return false;
            }
            Object reason = a.breakReason.getField("BREAK").get(null);
            a.breakCrop.invoke(api, null, null, location, reason);
            return stateAt(location.getWorld(), location.getBlockX(), location.getBlockY(), location.getBlockZ()) == null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isBlockStage(Object form) {
        return form != null && "BLOCK".equalsIgnoreCase(String.valueOf(form));
    }

    private static boolean hasOnlyBlockStages(Access access, Object config) throws ReflectiveOperationException {
        int maxPoints = ((Number) access.maxPoints.invoke(config)).intValue();
        for (int point = 0; point <= maxPoints; point++) {
            Object stage = access.stageForPoint.invoke(config, point);
            if (stage == null || !isBlockStage(access.stageForm.invoke(stage))) {
                return false;
            }
        }
        return true;
    }

    private static Object lookup(Object registry, String key) throws ReflectiveOperationException {
        return registry == null || key == null ? null : registry.getClass().getMethod("get", Object.class).invoke(registry, key);
    }

    @Nullable
    private static Access getAccess() {
        if (!VillagersDelightPlugin.customCropsEnabled()) {
            return null;
        }
        Access current = access;
        if (current != null) {
            return current;
        }
        if (unavailable) {
            return null;
        }
        Plugin plugin = Bukkit.getPluginManager().getPlugin("CustomCrops");
        if (plugin == null || !plugin.isEnabled()) {
            return null;
        }
        try {
            Access created = new Access(plugin.getClass().getClassLoader());
            access = created;
            return created;
        } catch (Throwable ignored) {
            unavailable = true;
            return null;
        }
    }

    private static final class Access {
        private final ClassLoader loader;
        private final Class<?> cropBlock;
        private final Class<?> breakReason;
        private final Method apiGet;
        private final Method apiAdapt;
        private final Method apiGetWorld;
        private final Method loadedState;
        private final Method stateType;
        private final Method cropConfig;
        private final Method cropPoint;
        private final Method maxPoints;
        private final Method stageForPoint;
        private final Method stageForm;
        private final Method configId;
        private final Method potConfigId;
        private final Method potWhitelist;
        private final Method placeCrop;
        private final Method breakCrop;
        private final Method itemManagerGet;
        private final Method itemId;
        private final Method blockId;
        private final Object seedRegistry;
        private final Object cropRegistry;
        private final Object potRegistry;

        private Access(ClassLoader loader) throws ReflectiveOperationException {
            this.loader = loader;
            Class<?> apiClass = load("net.momirealms.customcrops.api.CustomCropsAPI");
            Class<?> apiImpl = load(API_CLASS);
            Class<?> pos = load("net.momirealms.customcrops.api.core.world.Pos3");
            Class<?> blockState = load("net.momirealms.customcrops.api.core.world.CustomCropsBlockState");
            Class<?> cropConfig = load("net.momirealms.customcrops.api.core.mechanic.crop.CropConfig");
            Class<?> stageConfig = load("net.momirealms.customcrops.api.core.mechanic.crop.CropStageConfig");
            this.cropBlock = load(CROP_BLOCK_CLASS);
            this.breakReason = load(BREAK_REASON_CLASS);
            this.apiGet = apiImpl.getMethod("get");
            this.apiAdapt = apiClass.getMethod("adapt", Location.class);
            this.apiGetWorld = apiClass.getMethod("getCustomCropsWorld", World.class);
            Class<?> worldClass = load("net.momirealms.customcrops.api.core.world.CustomCropsWorld");
            this.loadedState = worldClass.getMethod("getLoadedBlockState", pos);
            this.stateType = blockState.getMethod("type");
            this.cropConfig = cropBlock.getMethod("config", blockState);
            this.cropPoint = cropBlock.getMethod("point", blockState);
            this.maxPoints = cropConfig.getMethod("maxPoints");
            this.stageForPoint = cropConfig.getMethod("stageWithModelByPoint", int.class);
            this.stageForm = stageConfig.getMethod("existenceForm");
            this.configId = cropConfig.getMethod("id");
            this.potConfigId = load("net.momirealms.customcrops.api.core.mechanic.pot.PotConfig").getMethod("id");
            this.potWhitelist = cropConfig.getMethod("potWhitelist");
            this.placeCrop = apiClass.getMethod("placeCrop", Location.class, String.class, int.class);
            this.breakCrop = apiClass.getMethod("simulatePlayerBreakCrop", load("org.bukkit.entity.Player"), load("org.bukkit.inventory.EquipmentSlot"), Location.class, breakReason);
            Class<?> pluginClass = load(PLUGIN_CLASS);
            this.itemManagerGet = pluginClass.getMethod("getInstance").getReturnType().getMethod("getItemManager");
            Class<?> itemManager = itemManagerGet.getReturnType();
            this.itemId = itemManager.getMethod("id", ItemStack.class);
            this.blockId = itemManager.getMethod("blockID", Block.class);
            Class<?> registries = load(REGISTRIES_CLASS);
            this.seedRegistry = registries.getField("SEED_TO_CROP").get(null);
            this.cropRegistry = registries.getField("CROP").get(null);
            this.potRegistry = registries.getField("ITEM_TO_POT").get(null);
        }

        private Class<?> load(String name) throws ClassNotFoundException {
            return Class.forName(name, true, this.loader);
        }

        private Object api() throws ReflectiveOperationException {
            return apiGet.invoke(null);
        }

        private Object itemManager() throws ReflectiveOperationException {
            Object plugin = load(PLUGIN_CLASS).getMethod("getInstance").invoke(null);
            return itemManagerGet.invoke(plugin);
        }
    }
}
