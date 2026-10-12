package com.huidu.villagersdelight.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The villager AI layer withdraws exactly what it injected.
 *
 *
 * The behaviour classes the layer installs do not share one package: the layer's own classes sit in the layer
 * package, the shared behaviour sources are relocated into that package's common subpackage, and one behaviour
 * is nested. A withdrawal that rebuilds a class name from the layer package therefore misses most of them and
 * leaves those behaviours running on a disabled plugin. The injector has to record the classes it installs and
 * the sensors it replaces while it installs them, and withdrawal has to walk those records.
 *
 * Every install site is checked on its own, so dropping a single registration fails here rather than only
 * shrinking the withdrawal by one behaviour. The three NMS layers keep separate copies of the injector, so all
 * three sources are checked; they are read as text instead of being loaded, because one source is compiled into
 * three different packages and no single classpath carries them.
 */
public final class VillagerBehaviorWithdrawCheck {

    private static final String[] LAYERS = {"nms-1.21.5", "nms-1211", "nms-26"};
    private static final String SOURCE = "src/main/java/com/huidu/villagersdelight/nms/NmsVillagerAi.java";

    private static final String REPLACE = "private void replace(org.bukkit.entity.Villager bukkitVillager)";
    private static final String REPLACE_IN_GATE = "private int replaceInsideGate(GateBehavior<?> gate, boolean farmer)";
    private static final String ADD_COLLECT =
            "private void addCollectIfMissing(org.bukkit.entity.Villager villager, Class<?> behaviorType)";
    private static final String REMOVE_INJECTED =
            "private void removeInjectedFrom(org.bukkit.entity.Villager villager)";
    private static final String REMOVE_BEHAVIOR = "private int removeBehavior(Brain<?> brain, Class<?> behaviorType)";
    private static final String REMOVE_NESTED = "private int removeNested(GateBehavior<?> gate, Class<?> behaviorType)";
    private static final String REMOVE_EMPTY_GATES = "private void removeEmptyGates(Brain<?> brain)";
    private static final String RESTORE_SENSORS = "private void restoreReplacedSensors(UUID id, Brain<?> brain)";

    private static final List<String> problems = new ArrayList<>();

    public static void main(String[] args) throws IOException {
        List<Path> targets = new ArrayList<>();
        if (args.length > 0) {
            for (String argument : args) {
                targets.add(Path.of(argument));
            }
        } else {
            for (String layer : LAYERS) {
                targets.add(locate(layer));
            }
        }
        for (Path target : targets) {
            check(target);
        }
        if (!problems.isEmpty()) {
            System.err.println("villager behavior withdrawal check failed:");
            for (String problem : problems) {
                System.err.println("  " + problem);
            }
            System.exit(1);
        }
        System.out.println("villager behavior withdrawal check passed for " + targets.size() + " layer source(s)");
    }

    private static void check(Path path) throws IOException {
        String name = path.toString();
        if (!Files.isRegularFile(path)) {
            problems.add(name + ": source not found; run the check from the module directory");
            return;
        }
        String source = Files.readString(path).replace("\r\n", "\n");

        // Withdrawal must walk the classes recorded at injection, never rebuild a name to look one up.
        if (source.contains("loadClass(")) {
            problems.add(name + ": withdrawal still resolves behaviour classes by name (loadClass); no layer"
                    + " package matches the relocated shared sources or the nested behaviour");
        }
        if (source.contains("ClassNotFoundException")) {
            problems.add(name + ": the name-resolution failure path is back, which silently skipped behaviours");
        }
        if (source.contains("\"VillagerConfiguredFood\"")) {
            problems.add(name + ": the withdrawal name list is back; behaviour classes have to be recorded when"
                    + " they are installed");
        }
        String withdrawal = body(name, source, REMOVE_INJECTED);
        if (withdrawal != null) {
            require(withdrawal, "for (Class<?> type : installedBehaviorTypes)", name, REMOVE_INJECTED,
                    "does not iterate the behaviour classes recorded at injection");
            require(withdrawal, "restoreReplacedSensors(", name, REMOVE_INJECTED,
                    "does not restore the sensors this layer replaced");
        }

        // Every install site records what it installs; one missing registration shrinks the withdrawal.
        String replace = body(name, source, REPLACE);
        if (replace != null) {
            require(replace, "rememberInjectedBehavior(VillagerConfiguredFood.class)", name, REPLACE,
                    "installs the configured-food behaviour without recording it");
            require(replace, "rememberInjectedBehavior(VillagerCollectWantedItem.class)", name, REPLACE,
                    "installs the collect behaviour without recording it");
            require(replace, "rememberInjectedBehavior(replacement.getClass())", name, REPLACE,
                    "installs a replaced behaviour without recording it");
            require(replace, "rememberReplacedSensor(bukkitVillager.getUniqueId(), SensorType.SECONDARY_POIS,",
                    name, REPLACE, "replaces the secondary-POI sensor without recording the original");
            require(replace, "rememberReplacedSensor(bukkitVillager.getUniqueId(), SensorType.NEAREST_ITEMS,",
                    name, REPLACE, "replaces the wanted-item sensor without recording the original");
        }
        String inGate = body(name, source, REPLACE_IN_GATE);
        if (inGate != null) {
            require(inGate, "rememberInjectedBehavior(replacement.getClass())", name, REPLACE_IN_GATE,
                    "installs a behaviour inside a gate without recording it");
        }
        String collect = body(name, source, ADD_COLLECT);
        if (collect != null) {
            require(collect, "rememberInjectedBehavior(behaviorType)", name, ADD_COLLECT,
                    "installs the collect behaviour without recording it");
            require(collect, "warnFailureOnce(", name, ADD_COLLECT, "reports failures on the debug channel only");
            if (collect.contains("debug(\"install: \" + behaviorType")) {
                problems.add(name + ": " + ADD_COLLECT + " still reports an install failure on the debug channel"
                        + " only");
            }
        }
        String restore = body(name, source, RESTORE_SENSORS);
        if (restore != null) {
            require(restore, "sensors.put(", name, RESTORE_SENSORS, "does not put a replaced sensor back");
            require(restore, "sensors.remove(", name, RESTORE_SENSORS,
                    "does not drop a sensor type that had none before");
            require(restore, "warnFailureOnce(", name, RESTORE_SENSORS, "fails silently");
        }

        // A failed withdrawal has to stay visible; these paths used to log on the debug channel only.
        requireVisibleFailure(name, source, REMOVE_BEHAVIOR);
        requireVisibleFailure(name, source, REMOVE_NESTED);
        requireVisibleFailure(name, source, REMOVE_EMPTY_GATES);
    }

    private static void require(String body, String needle, String name, String marker, String problem) {
        if (!body.contains(needle)) {
            problems.add(name + ": " + marker + " " + problem);
        }
    }

    private static void requireVisibleFailure(String name, String source, String marker) {
        String body = body(name, source, marker);
        if (body == null) {
            return;
        }
        if (!body.contains("warnFailureOnce(")) {
            problems.add(name + ": " + marker + " reports failures on the debug channel only");
        }
        if (body.contains("VillagersDelightPlugin.debug(")) {
            problems.add(name + ": " + marker + " still reports a failure on the debug channel only");
        }
    }

    private static String body(String name, String source, String marker) {
        int start = source.indexOf(marker);
        if (start < 0) {
            problems.add(name + ": expected declaration not found: " + marker);
            return null;
        }
        int end = source.indexOf("\n    }", start);
        if (end < start) {
            problems.add(name + ": no closing brace found after " + marker);
            return null;
        }
        return source.substring(start, end);
    }

    private static Path locate(String layer) {
        Path cursor = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 5 && cursor != null; depth++) {
            Path direct = cursor.resolve(layer).resolve(SOURCE);
            if (Files.isRegularFile(direct)) {
                return direct;
            }
            Path nested = cursor.resolve("VillagersDelight").resolve(layer).resolve(SOURCE);
            if (Files.isRegularFile(nested)) {
                return nested;
            }
            cursor = cursor.getParent();
        }
        return Path.of(layer).resolve(SOURCE);
    }
}
