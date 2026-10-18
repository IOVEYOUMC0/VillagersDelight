package com.huidu.villagersdelight.core;

import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;

import java.util.Locale;
import java.util.Set;

/**
 * Refuses a live plugin-manager unload/reload of this plugin before it runs.
 *
 *
 * VillagersDelight injects behaviours and sensors straight into live villager brains; a hot unload cannot
 * withdraw them, and a re-enable installs a second copy while the first keeps running against a dead
 * classloader. The JVM-property guard in {@link VillagersDelightPlugin#onEnable()} only catches the re-enable
 * (after the damage); this cancels the destructive command itself. Same list of plugin managers as the rest of
 * the family, kept local so VillagersDelight stays independent of FarmersDelight's API.
 */
public final class PluginManagerGuard implements Listener {

    private static final Set<String> PLUGIN_MANAGER_NAMES = Set.of(
            "plugman", "plm", "pluginmanager", "plugmanx", "plmx", "pluginsmanager", "pl");

    private static final Set<String> DESTRUCTIVE_ACTIONS = Set.of(
            "unload", "reload", "disable", "enable", "load", "restart", "stop", "start");

    private final String pluginNameLower;

    public PluginManagerGuard(String pluginName) {
        this.pluginNameLower = pluginName.toLowerCase(Locale.ROOT);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        if (matches(event.getMessage())) {
            event.setCancelled(true);
            sendRefusal(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent event) {
        if (matches("/" + event.getCommand())) {
            event.setCancelled(true);
            sendRefusal(event.getSender());
        }
    }

    private boolean matches(String message) {
        if (message == null || message.isEmpty()) return false;
        String trimmed = message.startsWith("/") ? message.substring(1) : message;
        String[] parts = trimmed.trim().toLowerCase(Locale.ROOT).split("\\s+");
        if (parts.length < 3) return false;
        // Strip an optional `namespace:` prefix (e.g. `plugman:plm`) so the match is what is after the colon.
        String cmd = parts[0];
        int colon = cmd.indexOf(':');
        if (colon >= 0) cmd = cmd.substring(colon + 1);
        if (!PLUGIN_MANAGER_NAMES.contains(cmd)) return false;
        if (!DESTRUCTIVE_ACTIONS.contains(parts[1])) return false;
        for (int i = 2; i < parts.length; i++) {
            if (parts[i].equals(pluginNameLower)) return true;
        }
        return false;
    }

    private void sendRefusal(CommandSender sender) {
        sender.sendMessage("§cVillagersDelight refuses a live plugin-manager reload: its behaviour/sensor "
                + "injection cannot be withdrawn, and a re-enable would install a second copy. Use "
                + "/vd reload for config changes, or /stop and start the server.");
    }
}
