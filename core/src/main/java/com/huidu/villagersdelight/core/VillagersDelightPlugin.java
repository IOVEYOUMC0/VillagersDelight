package com.huidu.villagersdelight.core;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.api.event.CraftEngineReloadEvent;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.Listener;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class VillagersDelightPlugin extends JavaPlugin {

    private static VillagersDelightPlugin instance;

    @Nullable
    private VillagerAiInjector aiInjector;
    private VillagersDelightConfig config;
    private final VillagersDelightLanguage language = new VillagersDelightLanguage(this);
    // Native menus edit live inventories; sessions only guard ownership and concurrent viewers.
    private final Map<Inventory, BackpackSession> openBackpackViews = new ConcurrentHashMap<>();
    private final Set<UUID> openBackpackVillagers = ConcurrentHashMap.newKeySet();

    private record BackpackSession(Player player, Villager villager, UUID villagerId) {
    }
    // Configured seed/food identities (CE custom ids and vanilla material keys) villagers may pick up,
    // and the vanilla base materials VillagersDelight injected into the villager_picks_up tag for CE
    // items. The pickup filter listener uses both to cancel leaked vanilla-counterpart pickups.
    private volatile Set<String> pickupIds = Set.of();
    private volatile Set<String> injectedBaseMaterials = Set.of();
    private volatile boolean configNotReady;
    private int configRetryAttempts;
    private ScheduledTask configRetryTask;

    // Vanilla items villagers naturally pick up. A CE seed/food whose base material is one of these is
    // never added to injectedBaseMaterials, so the pickup filter can't cancel a real vanilla pickup.
    private static final Set<Material> VANILLA_VILLAGER_WANTED = EnumSet.of(
            Material.BREAD, Material.WHEAT, Material.WHEAT_SEEDS,
            Material.BEETROOT, Material.BEETROOT_SEEDS,
            Material.CARROT, Material.POTATO);

    public VillagersDelightConfig config() {
        return this.config;
    }

    public static boolean isPickupAllowed(ItemStack stack) {
        VillagersDelightPlugin plugin = instance;
        if (plugin == null) return true;
        // New (custom-id pickup) mode: the datapack tag is left untouched, so vanilla items keep their
        // native pickup rules while a CE item is collectable only when its id is configured.
        boolean datapackTag = plugin.config == null || plugin.config.pickupUseDatapackTag();
        Key customId = CeItemAccess.customItemId(stack);
        String id = customId != null ? customId.toString() : stack.getType().getKey().toString();
        if (customId != null) {
            return plugin.pickupIds.contains(id);
        }
        if (datapackTag) {
            // Old mode: base materials were spread into the tag, so cancel leaked vanilla counterparts
            // whose id is not configured (keeps the previous build's behavior).
            return plugin.pickupIds.contains(id)
                    || !plugin.injectedBaseMaterials.contains(stack.getType().getKey().toString());
        }
        return true;
    }

    /** True when itemId (CE custom or vanilla key) is a configured villager pickup target. */
    public static boolean isPickupConfigured(String itemId) {
        VillagersDelightPlugin plugin = instance;
        return plugin != null && itemId != null && plugin.pickupIds.contains(itemId);
    }

    /** Snapshot of the configured pickup target ids (CE custom ids and vanilla keys). */
    public static Set<String> pickupTargetIds() {
        VillagersDelightPlugin plugin = instance;
        return plugin == null ? Set.of() : plugin.pickupIds;
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

    /**
     * One NMS layer plus the server classes that identify the versions it was compiled against.
     * "Does the layer class load?" is not a version probe: every layer's entry class only names symbols
     * that exist across the whole 1.21.5-26.x range, so the first candidate always loaded and the
     * version-specific classes it references only blew up later, per villager, with no fallback left.
     */
    private record NmsCandidate(String className, List<String> serverVersionPrefixes,
                                List<String> requiredServerClasses) {

        boolean matchesServer(String minecraftVersion) {
            return minecraftVersion != null && serverVersionPrefixes.stream()
                    .anyMatch(version -> version.endsWith(".")
                            ? minecraftVersion.startsWith(version) : minecraftVersion.equals(version));
        }

        String firstMissingServerClass(ClassLoader loader) {
            for (String required : requiredServerClasses) {
                try {
                    Class.forName(required, false, loader);
                } catch (ClassNotFoundException | LinkageError e) {
                    return required;
                }
            }
            return null;
        }
    }

    // Ordered newest-first. FarmlandBlock is the 26.x rename of FarmBlock; the villager class moved from
    // npc.Villager to npc.villager.Villager in 1.21.9.
    private static final List<NmsCandidate> NMS_CANDIDATES = List.of(
            new NmsCandidate("com.huidu.villagersdelight.impl26.NmsVillagerAi",
                    List.of("26."),
                    List.of("net.minecraft.world.level.block.FarmlandBlock",
                            "net.minecraft.world.entity.npc.villager.Villager")),
            new NmsCandidate("com.huidu.villagersdelight.impl1211.NmsVillagerAi",
                    List.of("1.21.11"),
                    List.of("net.minecraft.world.level.block.FarmBlock",
                            "net.minecraft.world.entity.npc.villager.Villager")),
            new NmsCandidate("com.huidu.villagersdelight.impl215.NmsVillagerAi",
                    List.of("1.21.5", "1.21.6", "1.21.7", "1.21.8"),
                    List.of("net.minecraft.world.level.block.FarmBlock",
                            "net.minecraft.world.entity.npc.Villager")));

    /** JVM-lifetime guard against /reload + hot disable (property persists across classloader recreation). */
    private static final String RELOAD_GUARD_PROPERTY = "villagersdelight.enabled.in.this.jvm";

    @Override
    public void onEnable() {
        if (System.getProperty(RELOAD_GUARD_PROPERTY) != null) {
            getLogger().severe("==================================================================");
            getLogger().severe(" PLEASE DO NOT /reload OR HOT-DISABLE VillagersDelight.");
            getLogger().severe(" Its behaviors and sensors are injected straight into live villager");
            getLogger().severe(" brains and cannot be withdrawn, so a re-enable installs a second");
            getLogger().severe(" copy while the first keeps running against a dead classloader.");
            getLogger().severe(" To apply config changes: /vd reload, or /stop and start again.");
            getLogger().severe("==================================================================");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        System.setProperty(RELOAD_GUARD_PROPERTY, "1");
        instance = this;
        saveDefaultConfig();
        language.init();
        getServer().getPluginManager().registerEvents(new BackpackCloseListener(), this);
        getServer().getPluginManager().registerEvents(new PickupFilterListener(), this);
        // Refuse a live plugin-manager unload/reload before it strands the brain injection; the JVM-property
        // guard below only catches the re-enable, which is already too late for the injected behaviours.
        getServer().getPluginManager().registerEvents(new PluginManagerGuard(getName()), this);
        registerCommands();
        getServer().getPluginManager().registerEvents(new ReloadListener(), this);
        if (!reloadFromConfig() && !configNotReady) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        String minecraftVersion = Bukkit.getMinecraftVersion();
        for (NmsCandidate candidate : NMS_CANDIDATES) {
            if (!candidate.matchesServer(minecraftVersion)) {
                getLogger().info("NMS layer " + candidate.className()
                        + " does not match Minecraft " + minecraftVersion + ".");
                continue;
            }
            String missing = candidate.firstMissingServerClass(getClass().getClassLoader());
            if (missing != null) {
                getLogger().info("NMS layer " + candidate.className()
                        + " does not match this server (" + missing + " not present).");
                continue;
            }
            try {
                Class<?> nmsClass = Class.forName(candidate.className());
                this.aiInjector = (VillagerAiInjector) nmsClass.getConstructor().newInstance();
                this.aiInjector.install();
                getLogger().info("VillagersDelight NMS layer installed: " + candidate.className());
                // Apply NMS-backed configuration after the injector is installed.
                if (!reloadFromConfig() && !configNotReady) {
                    getServer().getPluginManager().disablePlugin(this);
                }
                scheduleConfigRetryIfNeeded();
                return;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError t) {
                // Log the whole stack: a failure inside a static initializer surfaces as a bare
                // ExceptionInInitializerError whose toString says nothing about the real cause.
                getLogger().log(Level.WARNING,
                        "NMS layer " + candidate.className() + " failed to install", t);
                this.aiInjector = null;
            }
        }
        getLogger().warning("No compatible NMS villager AI layer found; villagers keep vanilla behavior.");
        scheduleConfigRetryIfNeeded();
    }

    @Override
    public void onDisable() {
        if (this.aiInjector != null) {
            this.aiInjector.shutdown();
            this.aiInjector = null;
        }
        for (BackpackSession session : List.copyOf(this.openBackpackViews.values())) {
            Player player = session.player();
            if (Bukkit.isOwnedByCurrentRegion(player)) player.closeInventory();
        }
        // Foreign-region menus close through their native validity check without a write-back.
        this.openBackpackViews.clear();
        this.openBackpackVillagers.clear();
        this.pickupIds = Set.of();
        this.injectedBaseMaterials = Set.of();
        // The behaviours injected into live villager brains keep reading this shared state after the
        // injector withdrew them, so it has to fall back to the built-in defaults rather than to the
        // configuration of a plugin that is no longer running.
        CropRegistry.reset();
        instance = null;
    }

    private static final String PERM_RELOAD = "villagersdelight.reload";
    private static final double LOOK_RANGE = 8.0;
    private static final double LOOK_DOT = 0.92;

    private static final String PERM_INVENTORY = "villagersdelight.inventory";
    // Keep the backpack target as a plain UUID argument. Entity selectors and player-name completion
    // are intentionally excluded; the command accepts an entity id copied from debug output.
    private void registerCommands() {
        getLifecycleManager().registerEventHandler(
                LifecycleEvents.COMMANDS, event -> {
                    LiteralCommandNode<CommandSourceStack> root =
                            Commands.literal("villagersdelight")
                                    .executes(ctx -> {
                                        ctx.getSource().getSender().sendMessage(language.component("messages.usage"));
                                        return Command.SINGLE_SUCCESS;
                                    })
                                    .then(Commands.literal("reload")
                                            .requires(source -> source.getSender().hasPermission(PERM_RELOAD))
                                            .executes(ctx -> {
                                                if (reloadFromConfig()) {
                                                    ctx.getSource().getSender().sendMessage(language.component("messages.config_reloaded"));
                                                }
                                                return Command.SINGLE_SUCCESS;
                                            }))
                                    .then(Commands.literal("inv")
                                            // Without a uuid, take the villager the player is looking at.
                                            // Brigadier treats a literal with no executes as an incomplete
                                            // command, so omitting this makes bare "/vd inv" an error
                                            // rather than the convenient form it reads as.
                                            .executes(ctx -> {
                                                openLookedAtVillager(ctx.getSource().getSender());
                                                return Command.SINGLE_SUCCESS;
                                            })
                                            .then(Commands
                                                    .argument("target", ArgumentTypes.uuid())
                                                    .suggests((ctx, builder) -> {
                                                        if (ctx.getSource().getSender() instanceof Player player) {
                                                            player.getNearbyEntities(32, 32, 32).stream()
                                                                    .filter(entity -> entity instanceof Villager)
                                                                    .map(Entity::getUniqueId)
                                                                    .map(UUID::toString)
                                                                    .forEach(builder::suggest);
                                                        }
                                                        return builder.buildFuture();
                                                    })
                                                    .executes(ctx -> {
                                                        openResolvedVillager(ctx);
                                                        return Command.SINGLE_SUCCESS;
                                                    })))
                                    .build();
                    event.registrar().register(root, "VillagersDelight management command",
                            List.of("vd"));
                });
    }

    // Picks the villager the player is aiming at: ray-traces the look direction and falls back to the
    // nearest villager inside a small cone, so a slightly-off crosshair still works. Runs on the sender's
    // own region, which is the only one allowed to read their location and the entities around them.
    private void openLookedAtVillager(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(language.component("messages.player_only"));
            return;
        }
        RayTraceResult hit = player.getWorld().rayTraceEntities(
                player.getEyeLocation(), player.getEyeLocation().getDirection(), LOOK_RANGE,
                entity -> entity instanceof Villager && !entity.equals(player));
        Villager villager = hit != null && hit.getHitEntity() instanceof Villager v ? v : null;
        if (villager == null) {
            villager = nearestVillagerInView(player);
        }
        if (villager == null) {
            sender.sendMessage(language.component("messages.villager_not_found"));
            return;
        }
        openVillagerInventory(sender, villager);
    }

    // Nearest villager within LOOK_RANGE whose direction is within LOOK_DOT of where the player faces.
    private Villager nearestVillagerInView(Player player) {
        Vector look = player.getEyeLocation().getDirection().normalize();
        Villager best = null;
        double bestDistanceSquared = Double.MAX_VALUE;
        for (Entity entity
                : player.getNearbyEntities(LOOK_RANGE, LOOK_RANGE, LOOK_RANGE)) {
            if (!(entity instanceof Villager villager)) {
                continue;
            }
            Vector toEntity = villager.getEyeLocation().toVector()
                    .subtract(player.getEyeLocation().toVector());
            double distanceSquared = toEntity.lengthSquared();
            if (distanceSquared <= 1.0E-6 || distanceSquared >= bestDistanceSquared) {
                continue;
            }
            if (toEntity.normalize().dot(look) < LOOK_DOT) {
                continue;
            }
            best = villager;
            bestDistanceSquared = distanceSquared;
        }
        return best;
    }

    // The entity argument resolves to whatever the selector matched, which is not necessarily a
    // villager and may be empty when a uuid names an entity that is no longer loaded.
    private void openResolvedVillager(
            CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        CommandSender sender = ctx.getSource().getSender();
        UUID targetId = ctx.getArgument("target", UUID.class);
        Entity entity = Bukkit.getEntity(targetId);
        Villager villager = entity instanceof Villager v ? v : null;
        if (villager == null) {
            sender.sendMessage(language.component("messages.target_not_villager"));
            return;
        }
        openVillagerInventory(sender, villager);
    }

    // Opens a villager's inventory in a standard container GUI so its contents can be inspected and edited.
    private boolean openVillagerInventory(CommandSender sender, Villager explicitTarget) {
        if (!sender.hasPermission(PERM_INVENTORY)) {
            sender.sendMessage(language.component("messages.no_permission"));
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(language.component("messages.player_only"));
            return true;
        }
        Villager villager = explicitTarget;
        if (villager == null) {
            sender.sendMessage(language.component("messages.villager_not_found"));
            return true;
        }
        final Villager targetVillager = villager;
        UUID villagerId = targetVillager.getUniqueId();
        if (!this.openBackpackVillagers.add(villagerId)) {
            sender.sendMessage(language.component("messages.inventory_open"));
            return true;
        }
        openBackpackView(player, targetVillager, villagerId);
        return true;
    }

    private boolean canAccessBackpack(Player player, Villager villager) {
        return isEnabled() && Bukkit.isOwnedByCurrentRegion(player)
                && Bukkit.isOwnedByCurrentRegion(villager) && villager.isValid();
    }

    private void openBackpackView(Player player, Villager villager, UUID villagerId) {
        if (!canAccessBackpack(player, villager)) {
            this.openBackpackVillagers.remove(villagerId);
            player.sendMessage(language.component("messages.region_unavailable"));
            return;
        }
        try {
            ItemStack placeholder = new ItemStack(Material.BARRIER);
            ItemMeta meta = placeholder.getItemMeta();
            meta.displayName(language.component("messages.locked_slot"));
            placeholder.setItemMeta(meta);
            Inventory view = this.aiInjector == null ? null : this.aiInjector.openBackpack(player, villager,
                    language.component("messages.inventory_title"), placeholder,
                    () -> canAccessBackpack(player, villager));
            if (view == null) {
                this.openBackpackVillagers.remove(villagerId);
                player.sendMessage(language.component("messages.inventory_failed"));
                return;
            }
            this.openBackpackViews.put(view, new BackpackSession(player, villager, villagerId));
            player.sendMessage(language.component("messages.inventory_opened"));
        } catch (RuntimeException e) {
            this.openBackpackVillagers.remove(villagerId);
            player.sendMessage(language.component("messages.inventory_failed"));
            getLogger().warning("Failed to open villager backpack for " + villagerId + ": " + e.getMessage());
        }
    }

    boolean reloadFromConfig() {
        reloadConfig();
        VillagersDelightConfig previous = this.config;
        this.configNotReady = false;
        try {
            this.config = VillagersDelightConfig.load(getConfig());
        } catch (VillagersDelightConfig.CraftEngineNotReadyException e) {
            this.configNotReady = true;
            if (previous != null) {
                this.config = previous;
            }
            return false;
        } catch (RuntimeException e) {
            if (previous != null) {
                this.config = previous;
            }
            getLogger().warning(language.get("messages.config_invalid", "error", e.getMessage()));
            return false;
        }
        language.reload();
        CropRegistry.reload(this);
        CropRegistry registry = CropRegistry.instance();
        // The data pack only widens what villagers MAY pick up; the wanted-item sensor that narrows it
        // back down to the configured seeds lives in the NMS layer. Writing the pack without that layer
        // makes every villager on the server start hoarding the raw base materials of CE seed items.
        // In custom-id pickup mode the NMS layer handles CE items itself, so the data pack is skipped.
        if (this.aiInjector != null) {
            boolean customIdPickup = !this.config.pickupUseDatapackTag()
                    && this.aiInjector.supportsCustomIdPickup();
            this.aiInjector.setCustomIdPickup(customIdPickup);
            if (customIdPickup) {
                getLogger().info("Pickup: custom-id mode active; skipping the villager_picks_up data pack.");
                // The pack written while datapack-tag mode was active keeps widening the vanilla pickup
                // tag until the file is gone; the injector only stops installing the collect behaviour,
                // so the operator has to be told to remove it.
                if (PickupEnabler.dataPackWritten()) {
                    getLogger().warning("Pickup: the villager_picks_up data pack written earlier is still"
                            + " installed and keeps widening the pickup tag; delete"
                            + " <world>/datapacks/villagersdelight/ and restart the server so only the"
                            + " custom-id mode applies.");
                }
            } else {
                PickupEnabler.apply(this, this.config);
            }
        } else {
            getLogger().info("NMS layer not installed; skipping the villager pickup data pack.");
        }
        augmentFarmerPickup(registry, this.config);
        configureCompost(this.config);
        if (this.aiInjector != null) {
            this.aiInjector.configureBehavior(this.config.behaviorSettings());
        }
        getLogger().info(language.get("messages.config_loaded",
                "crops", registry == null ? 0 : registry.cropCount(),
                "harvest_drops", this.config.harvestDrops().size(),
                "pickup_foods", this.config.pickupFoods().size(),
                "compost_items", this.config.compostItems().size()));
        return true;
    }

    private void scheduleConfigRetryIfNeeded() {
        if (!this.configNotReady || !isEnabled() || this.configRetryAttempts >= 20) {
            if (this.configNotReady) {
                getLogger().warning("CraftEngine custom items are still unavailable after startup retries; "
                        + "VillagersDelight kept the previous configuration.");
            }
            return;
        }
        if (this.configRetryTask != null && !this.configRetryTask.isCancelled()) {
            return;
        }
        this.configRetryAttempts++;
        this.configRetryTask = Bukkit.getGlobalRegionScheduler().runDelayed(this, task -> {
            this.configRetryTask = null;
            if (reloadFromConfig()) {
                this.configRetryAttempts = 0;
                getLogger().info("VillagersDelight configuration loaded after CraftEngine became ready.");
            } else {
                scheduleConfigRetryIfNeeded();
            }
        }, 10L);
    }

    // Pickup coordination: the villager_picks_up data pack (PickupEnabler) makes wantsToPickUp accept
    // CE seed base materials. Rebuilding the farmer profession registry entry is unsafe because replacing
    // the VillagerProfession holder value strips farmer outfits after a restart. The wanted-item sensor and
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
            this.aiInjector.configureFoodItems(VillagerFoodRules.EMPTY);
            return;
        }
        List<Key> enabledCrops = cfg.pickupCrops();
        Set<Key> seeds = new HashSet<>();
        for (FDCrop crop : registry.allCrops()) {
            if (!enabledCrops.isEmpty() && !enabledCrops.contains(crop.seedItem())
                    && !enabledCrops.contains(crop.blockId())) {
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
        this.pickupIds = Set.copyOf(seedIds);
        Set<String> injected = new HashSet<>();
        for (Key seed : seeds) {
            if ("minecraft".equals(seed.namespace())) {
                // Vanilla id: its base material is itself and already villager-wanted; nothing to inject.
                continue;
            }
            Material material = PickupEnabler.seedMaterial(seed);
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
        Set<String> protectedSeeds = new HashSet<>();
        for (FDCrop crop : registry.allCrops()) {
            Key seed = crop.seedItem();
            if (seed != null && !"minecraft".equals(seed.namespace())) protectedSeeds.add(seed.toString());
        }
        this.aiInjector.configureFoodItems(cfg.foodRules(protectedSeeds));
    }

    // Match configured CE ids without widening vanilla compost rules; the NMS loop keeps seed reserves.
    private void configureCompost(VillagersDelightConfig cfg) {
        if (this.aiInjector == null) {
            return;
        }
        Set<String> ids = new HashSet<>();
        for (Key item : cfg.compostItems()) {
            ids.add(item.toString());
        }
        this.aiInjector.configureCeCompost(ids);
    }

    private final class BackpackCloseListener implements Listener {

        @EventHandler
        public void onClose(InventoryCloseEvent event) {
            BackpackSession session = openBackpackViews.remove(event.getInventory());
            if (session != null) openBackpackVillagers.remove(session.villagerId());
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onClick(InventoryClickEvent event) {
            Inventory view = event.getView().getTopInventory();
            BackpackSession session = openBackpackViews.get(view);
            if (session == null) return;
            if (!canAccessBackpack(session.player(), session.villager())) {
                event.setCancelled(true);
                closeUnavailableBackpack(session, view);
            } else if (event.getRawSlot() == 8) {
                event.setCancelled(true);
            }
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onDrag(InventoryDragEvent event) {
            Inventory view = event.getView().getTopInventory();
            BackpackSession session = openBackpackViews.get(view);
            if (session == null) return;
            if (!canAccessBackpack(session.player(), session.villager())) {
                event.setCancelled(true);
                closeUnavailableBackpack(session, view);
            } else if (event.getRawSlots().contains(8)) {
                event.setCancelled(true);
            }
        }

        private void closeUnavailableBackpack(BackpackSession session, Inventory view) {
            session.player().getScheduler().run(VillagersDelightPlugin.this, task -> {
                if (session.player().getOpenInventory().getTopInventory() == view) {
                    session.player().closeInventory();
                }
            }, null);
        }
    }

    // Restrict injected base materials to configured identities; preserve native pickups.
    private final class PickupFilterListener implements Listener {

        @EventHandler(ignoreCancelled = true)
        public void onPickup(EntityPickupItemEvent event) {
            if (!(event.getEntity() instanceof Villager)) {
                return;
            }
            if (!isPickupAllowed(event.getItem().getItemStack())) {
                event.setCancelled(true);
            }
        }
    }

    private final class ReloadListener implements Listener {

        @EventHandler
        public void onCraftEngineReload(CraftEngineReloadEvent event) {
            reloadFromConfig();
            scheduleConfigRetryIfNeeded();
        }
    }
}
