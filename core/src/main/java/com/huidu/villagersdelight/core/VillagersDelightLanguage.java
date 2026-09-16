package com.huidu.villagersdelight.core;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.io.File;
import java.util.Locale;
import java.util.Map;

/** Small editable message bundle; files live in plugins/VillagersDelight/lang/. */
final class VillagersDelightLanguage {
    private final JavaPlugin plugin;
    private volatile Map<String, YamlConfiguration> files = Map.of();
    private volatile String selected = "en_us";

    VillagersDelightLanguage(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    void init() {
        File dir = new File(plugin.getDataFolder(), "lang");
        if (!dir.exists() && !dir.mkdirs()) {
            plugin.getLogger().warning("Unable to create language directory: " + dir);
        }
        for (String locale : new String[]{"zh_cn", "en_us"}) {
            File file = new File(dir, locale + ".yml");
            if (!file.exists()) {
                plugin.saveResource("lang/" + locale + ".yml", false);
            }
        }
        reload();
    }

    void reload() {
        File dir = new File(plugin.getDataFolder(), "lang");
        Map<String, YamlConfiguration> loaded = new java.util.HashMap<>();
        File[] files = dir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files != null) {
            for (File file : files) {
                try {
                    YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
                    loaded.put(file.getName().substring(0, file.getName().length() - 4).toLowerCase(Locale.ROOT), yaml);
                } catch (RuntimeException e) {
                    plugin.getLogger().warning("Failed to load language file " + file.getName() + ": " + e.getMessage());
                }
            }
        }
        String configured = plugin.getConfig().getString("language", "").trim().toLowerCase(Locale.ROOT);
        String locale = configured.isEmpty() ? Locale.getDefault().toString().toLowerCase(Locale.ROOT) : configured;
        if (!loaded.containsKey(locale)) {
            locale = locale.replace('-', '_');
        }
        if (!loaded.containsKey(locale)) {
            String prefix = locale.length() >= 2 ? locale.substring(0, 2) : locale;
            locale = loaded.keySet().stream().filter(k -> k.startsWith(prefix + "_")).findFirst().orElse("en_us");
        }
        selected = loaded.containsKey(locale) ? locale : loaded.keySet().stream().findFirst().orElse("en_us");
        this.files = Map.copyOf(loaded);
    }

    String get(String key, Object... args) {
        String value = null;
        YamlConfiguration selectedFile = files.get(selected);
        if (selectedFile != null) value = selectedFile.getString(key);
        if (value == null) {
            YamlConfiguration english = files.get("en_us");
            if (english != null) value = english.getString(key);
        }
        if (value == null) return key;
        for (int i = 0; i + 1 < args.length; i += 2) {
            value = value.replace("{" + args[i] + "}", String.valueOf(args[i + 1]));
        }
        return value;
    }

    Component component(String key, Object... args) {
        return MiniMessage.miniMessage().deserialize(get(key, args));
    }
}
