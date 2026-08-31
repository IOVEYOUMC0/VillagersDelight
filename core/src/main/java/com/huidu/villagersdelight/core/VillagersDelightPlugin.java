package com.huidu.villagersdelight.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.api.event.CraftEngineReloadEvent;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class VillagersDelightPlugin extends JavaPlugin {

    private static VillagersDelightPlugin instance;

    @Nullable
    private VillagerAiInjector aiInjector;
    private VillagersDelightConfig config;
    // The open backpack view plus the villager's inventory as it was at open time, so on close only the
    // slots the player actually changed are written back (AI-mutated slots are left alone). Keyed on the
    // view inventory; concurrent because open/click/close run on different players' region threads.
    private final java.util.Map<org.bukkit.inventory.Inventory, BackpackSession> openBackpackViews =
            new java.util.concurrent.ConcurrentHashMap<>();

    private record BackpackSession(org.bukkit.entity.Villager villager, org.bukkit.inventory.ItemStack[] snapshot) {
    }
    // Configured seed/food identities (CE custom ids and vanilla material keys) villagers may pick up,
    // and the vanilla base materials VillagersDelight injected into the villager_picks_up tag for CE
    // items. The pickup filter listener uses both to cancel leaked vanilla-counterpart pickups.
    private volatile Set<String> pickupIds = Set.of();
    private volatile Set<String> injectedBaseMaterials = Set.of();

    // Vanilla items villagers naturally pick up. A CE seed/food whose base material is one of these is
    // never added to injectedBaseMaterials, so the pickup filter can't cancel a real vanilla pickup.
    private static final Set<org.bukkit.Material> VANILLA_VILLAGER_WANTED = java.util.EnumSet.of(
            org.bukkit.Material.BREAD, org.bukkit.Material.WHEAT, org.bukkit.Material.WHEAT_SEEDS,
            org.bukkit.Material.BEETROOT, org.bukkit.Material.BEETROOT_SEEDS,
            org.bukkit.Material.CARROT, org.bukkit.Material.POTATO);

    public VillagersDelightConfig config() {
        return this.config;
    }

    public static boolean customCropsEnabled() {
        VillagersDelightPlugin plugin = instance;
        return plugin != null && plugin.config != null && plugin.config.customCropsEnabled();
    }

    // Debug log helper; prints only when config.yml debug: true.
    public static void debug(String message) {
        VillagersDelightPlugin plugin = instance;
        if (plugin != null && plugin.config != null && plugin.config.debug()) {
            plugin.getLogger().info("[VD-debug] " + message);
        }
    }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(new BackpackCloseListener(), this);
        getServer().getPluginManager().registerEvents(new PickupFilterListener(), this);
        registerCommands();
        reloadFromConfig();
        getServer().getPluginManager().registerEvents(new ReloadListener(), this);

        String[] implementations = {
                "com.huidu.villagersdelight.impl26.NmsVillagerAi",
                "com.huidu.villagersdelight.impl1211.NmsVillagerAi",
                "com.huidu.villagersdelight.impl214.NmsVillagerAi"
        };
        for (String implementation : implementations) {
            try {
                Class<?> nmsClass = Class.forName(implementation);
                this.aiInjector = (VillagerAiInjector) nmsClass.getConstructor().newInstance();
                this.aiInjector.install();
                getLogger().info("VillagersDelight NMS layer installed: " + implementation);
                // The onEnable reloadFromConfig above ran before the NMS layer existed, so pickup, share
                // and compost all no-op'd. Re-apply the whole config now that the injector is present.
                reloadFromConfig();
                return;
            } catch (Throwable t) {
                getLogger().warning("NMS layer " + implementation + " unavailable: " + t);
            }
        }
        getLogger().warning("No compatible NMS villager AI layer found; villagers keep vanilla behavior.");
    }

    @Override
    public void onDisable() {
        if (this.aiInjector != null) {
            this.aiInjector.shutdown();
            this.aiInjector = null;
        }
    }

    private void registerCommands() {
        org.bukkit.command.PluginCommand cmd = getCommand("villagersdelight");
        if (cmd == null) {
            return;
        }
        cmd.setExecutor((sender, command, label, args) -> {
            if (args.length == 0) {
                sender.sendMessage("用法: /villagersdelight reload | /villagersdelight inv");
                return true;
            }
            switch (args[0].toLowerCase()) {
                case "reload":
                    reloadFromConfig();
                    sender.sendMessage("[VillagersDelight] 配置已重载");
                    return true;
                case "inv":
                case "inventory":
                    return openVillagerInventory(sender);
                default:
                    sender.sendMessage("用法: /villagersdelight reload | /villagersdelight inv");
                    return true;
            }
        });
    }

    // Opens the inventory of the villager the player is looking at (ray-traced within 10 blocks)
    // in a standard container GUI, so its contents can be inspected and edited directly. Edits
    // apply to the villager's real inventory immediately.
    private boolean openVillagerInventory(org.bukkit.command.CommandSender sender) {
        if (!(sender instanceof org.bukkit.entity.Player player)) {
            sender.sendMessage("[VillagersDelight] 只有玩家可以打开村民背包");
            return true;
        }
        org.bukkit.util.RayTraceResult result = player.rayTraceEntities(10, false);
        org.bukkit.entity.Villager villager = result != null && result.getHitEntity() instanceof org.bukkit.entity.Villager v
                ? v : null;
        if (villager == null) {
            // Fall back to the nearest villager around the player.
            villager = player.getWorld().getNearbyEntities(player.getLocation(), 6, 6, 6).stream()
                    .filter(entity -> entity instanceof org.bukkit.entity.Villager)
                    .map(entity -> (org.bukkit.entity.Villager) entity)
                    .min(java.util.Comparator.comparingDouble(entity -> entity.getLocation().distanceSquared(player.getLocation())))
                    .orElse(null);
        }
        if (villager == null) {
            sender.sendMessage("[VillagersDelight] 准星没有指向村民，附近也没有村民");
            return true;
        }
        for (BackpackSession existing : this.openBackpackViews.values()) {
            if (existing.villager().getUniqueId().equals(villager.getUniqueId())) {
                sender.sendMessage("[VillagersDelight] 该村民的背包已被打开");
                return true;
            }
        }
        // The villager inventory may have a size CraftContainer rejects, so copy it into a
        // standard inventory view and write edits back when the view closes.
        int villagerSize = villager.getInventory().getSize();
        int viewSize = Math.max(9, ((villagerSize + 8) / 9) * 9);
        org.bukkit.inventory.Inventory view = getServer().createInventory(null, viewSize, Component.text("村民背包"));
        org.bukkit.inventory.ItemStack[] snapshot = new org.bukkit.inventory.ItemStack[villagerSize];
        for (int i = 0; i < villagerSize; i++) {
            org.bukkit.inventory.ItemStack item = villager.getInventory().getItem(i);
            snapshot[i] = item == null ? null : item.clone();
            view.setItem(i, item);
        }
        // Lock the trailing slot added to fit a supported container size so players cannot use it.
        if (viewSize > villager.getInventory().getSize()) {
            org.bukkit.inventory.ItemStack placeholder = new org.bukkit.inventory.ItemStack(org.bukkit.Material.BARRIER);
            org.bukkit.inventory.meta.ItemMeta meta = placeholder.getItemMeta();
            if (meta != null) {
                meta.displayName(Component.text("不可操作", NamedTextColor.DARK_GRAY));
                placeholder.setItemMeta(meta);
            }
            view.setItem(viewSize - 1, placeholder);
        }
        this.openBackpackViews.put(view, new BackpackSession(villager, snapshot));
        player.openInventory(view);
        sender.sendMessage("[VillagersDelight] 已打开村民背包，关闭界面时保存改动");
        return true;
    }

    void reloadFromConfig() {
        reloadConfig();
        this.config = VillagersDelightConfig.load(getConfig());
        CropRegistry.reload(this);
        CropRegistry registry = CropRegistry.instance();
        PickupEnabler.apply(this, this.config);
        augmentFarmerPickup(registry, this.config);
        configureShareItems(registry, this.config);
        configureCompost(registry, this.config);
        getLogger().info("Crop index rebuilt: " + (registry == null ? 0 : registry.cropCount()) + " crops.");
    }

    // Pickup coordination: the villager_picks_up data pack (PickupEnabler) is the only mechanism
    // that makes wantsToPickUp accept the CE seeds' base materials. We deliberately do NOT rebuild
    // the farmer profession registry entry here - replacing the VillagerProfession holder value made
    // every farmer villager lose its profession outfit after a restart. The wanted-item sensor and
    // pickup listener stay config-driven so villagers only walk to the configured seeds and fruit.
    private void augmentFarmerPickup(CropRegistry registry, VillagersDelightConfig cfg) {
        if (this.aiInjector == null) {
            this.pickupIds = Set.of();
            this.injectedBaseMaterials = Set.of();
            return;
        }
        if (!cfg.pickupEnabled() || registry == null) {
            this.pickupIds = Set.of();
            this.injectedBaseMaterials = Set.of();
            this.aiInjector.configurePickupFilter(Set.of());
            this.aiInjector.configureFoodItems(List.of());
            return;
        }
        List<Key> enabledCrops = cfg.pickupCrops();
        Set<Key> seeds = new HashSet<>();
        for (FDCrop crop : registry.allCrops()) {
            if (!enabledCrops.isEmpty() && !enabledCrops.contains(crop.blockId())) {
                continue;
            }
            Key seed = crop.seedItem();
            if (seed == null) {
                continue;
            }
            seeds.add(seed);
        }
        // Mature crop drops (harvest-drops) are pickup targets too so villagers can collect what
        // they harvest.
        for (List<ItemStack> drops : cfg.harvestDrops().values()) {
            for (ItemStack drop : drops) {
                if (drop == null || drop.getType().isAir()) {
                    continue;
                }
                Key customId = CraftEngineItems.getCustomItemId(drop);
                seeds.add(customId != null ? customId : Key.of(drop.getType().getKey().toString()));
            }
        }
        // Configured foods are walk-and-pick targets like the seeds.
        for (Key food : cfg.pickupFoods()) {
            seeds.add(food);
        }
        Set<String> seedIds = new HashSet<>();
        for (Key seed : seeds) {
            seedIds.add(seed.toString());
        }
        // Villagers actually pick items up passively in Mob.aiStep (via the villager_picks_up tag),
        // not through this sensor, so the leaked vanilla-counterpart filtering happens in the pickup
        // event listener; it is keyed off these configured ids and the injected base materials below.
        this.aiInjector.configurePickupFilter(seedIds);
        this.pickupIds = Set.copyOf(seedIds);
        Set<String> injected = new HashSet<>();
        for (Key seed : seeds) {
            if ("minecraft".equals(seed.namespace())) {
                // Vanilla id: its base material is itself and already villager-wanted; nothing to inject.
                continue;
            }
            org.bukkit.Material material = PickupEnabler.seedMaterial(seed);
            if (material == null) {
                continue;
            }
            // A CE item backed by a vanilla item villagers naturally want can't be filtered without
            // breaking that vanilla pickup, so leave it out (rare config choice).
            if (VANILLA_VILLAGER_WANTED.contains(material)) {
                getLogger().warning("Pickup: CE item " + seed + " uses vanilla-wanted base " + material
                        + "; not filtering it so real vanilla pickups keep working");
                continue;
            }
            injected.add(material.getKey().toString());
        }
        this.injectedBaseMaterials = Set.copyOf(injected);
        // Configured food base materials count toward breeding food points and are consumed by the
        // NMS behavior instead of modifying Minecraft's static villager food table.
        List<String> foodBaseIds = new ArrayList<>();
        for (Key food : cfg.pickupFoods()) {
            org.bukkit.Material material = PickupEnabler.seedMaterial(food);
            if (material != null) {
                foodBaseIds.add(material.getKey().toString());
            }
        }
        this.aiInjector.configureFoodItems(foodBaseIds);
    }

    // Passes the compost-items configuration to the NMS layer: villagers can compost the configured
    // CE items (matched by their CE custom id, never by base material) into bone meal. The vanilla
    // COMPOSTABLES table stays untouched, so base materials of CE items are not made compostable.
    // Re-invoking replaces the previous set, so /vd reload applies edits immediately.
    private void configureCompost(CropRegistry registry, VillagersDelightConfig cfg) {
        if (this.aiInjector == null) {
            return;
        }
        Set<String> ids = new HashSet<>();
        for (Key item : cfg.compostItems()) {
            ids.add(item.toString());
        }
        this.aiInjector.configureCeCompost(ids);
    }

    // Passes the share-items configuration to the NMS layer: villagers throw surplus configured
    // items (matched by CE custom id first, vanilla material key as fallback) at nearby villagers.
    // Re-invoking replaces the previous set, so /vd reload applies edits immediately.
    private void configureShareItems(CropRegistry registry, VillagersDelightConfig cfg) {
        if (this.aiInjector == null) {
            return;
        }
        Set<String> ids = new HashSet<>();
        for (Key item : cfg.shareItems()) {
            ids.add(item.toString());
        }
        this.aiInjector.configureShareItems(cfg.shareEnabled(), ids);
    }

    // Writes the opened backpack view back to the villager's real inventory on close. The view is
    // a plain container, so edits are buffered until the player closes it.
    private final class BackpackCloseListener implements Listener {

        @EventHandler
        public void onClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
            BackpackSession session = VillagersDelightPlugin.this.openBackpackViews.remove(event.getInventory());
            if (session == null) {
                return;
            }
            org.bukkit.entity.Villager villager = session.villager();
            org.bukkit.inventory.ItemStack[] snapshot = session.snapshot();
            org.bukkit.inventory.Inventory view = event.getInventory();
            int size = Math.min(snapshot.length, view.getSize());
            // Capture only the slots the player edited (view differs from the open-time snapshot); slots the
            // villager's AI changed while the view was open are untouched, so nothing is lost or duplicated.
            org.bukkit.inventory.ItemStack[] edited = new org.bukkit.inventory.ItemStack[size];
            boolean[] changed = new boolean[size];
            for (int i = 0; i < size; i++) {
                org.bukkit.inventory.ItemStack now = view.getItem(i);
                if (!java.util.Objects.equals(now, snapshot[i])) {
                    changed[i] = true;
                    edited[i] = now == null ? null : now.clone();
                }
            }
            villager.getScheduler().run(VillagersDelightPlugin.this, task -> {
                org.bukkit.inventory.Inventory inv = villager.getInventory();
                for (int i = 0; i < size && i < inv.getSize(); i++) {
                    if (changed[i]) {
                        inv.setItem(i, edited[i]);
                    }
                }
            }, null);
        }

        @EventHandler(ignoreCancelled = true)
        public void onClick(org.bukkit.event.inventory.InventoryClickEvent event) {
            if (!VillagersDelightPlugin.this.openBackpackViews.containsKey(event.getView().getTopInventory())) {
                return;
            }
            org.bukkit.inventory.Inventory view = event.getView().getTopInventory();
            if (event.getRawSlot() >= 0 && event.getRawSlot() < view.getSize()
                    && event.getRawSlot() == view.getSize() - 1) {
                event.setCancelled(true);
            }
        }

        @EventHandler(ignoreCancelled = true)
        public void onDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
            if (!VillagersDelightPlugin.this.openBackpackViews.containsKey(event.getView().getTopInventory())) {
                return;
            }
            if (event.getRawSlots().contains(event.getView().getTopInventory().getSize() - 1)) {
                event.setCancelled(true);
            }
        }
    }

    // Villagers pick items up passively in Mob.aiStep via wantsToPickUp (the villager_picks_up tag),
    // never through the wanted-item sensor (villagers have no walk-to-wanted-item behavior). We appended
    // CE seed/food base materials (e.g. nether brick, steak) to that tag so villagers would collect the
    // CE items, but that also lets a plain vanilla item of the same base material leak into a villager's
    // inventory. Cancel exactly those: an item whose base material we injected and whose identity is not
    // a configured seed/food. Bread, real seeds and every other naturally-wanted vanilla item keep a base
    // material we never injected, so they are untouched and vanilla pickup/breeding is preserved.
    private final class PickupFilterListener implements Listener {

        @EventHandler(ignoreCancelled = true)
        public void onPickup(org.bukkit.event.entity.EntityPickupItemEvent event) {
            if (!(event.getEntity() instanceof org.bukkit.entity.Villager)) {
                return;
            }
            Set<String> injected = VillagersDelightPlugin.this.injectedBaseMaterials;
            if (injected.isEmpty()) {
                return;
            }
            ItemStack stack = event.getItem().getItemStack();
            Key customId = CraftEngineItems.getCustomItemId(stack);
            String identity = customId != null ? customId.toString() : stack.getType().getKey().toString();
            if (VillagersDelightPlugin.this.pickupIds.contains(identity)) {
                return;
            }
            if (injected.contains(stack.getType().getKey().toString())) {
                event.setCancelled(true);
            }
        }
    }

    private final class ReloadListener implements Listener {

        @EventHandler
        public void onCraftEngineReload(CraftEngineReloadEvent event) {
            reloadFromConfig();
        }
    }
}
