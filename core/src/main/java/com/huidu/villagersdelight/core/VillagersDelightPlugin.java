package com.huidu.villagersdelight.core;

import net.kyori.adventure.text.Component;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.api.event.CraftEngineReloadEvent;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Bukkit;
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
    private final VillagersDelightLanguage language = new VillagersDelightLanguage(this);
    // The open backpack view plus the villager's inventory as it was at open time, so on close only the
    // slots the player actually changed are written back (AI-mutated slots are left alone). Keyed on the
    // view inventory; concurrent because open/click/close run on different players' region threads.
    private final java.util.Map<org.bukkit.inventory.Inventory, BackpackSession> openBackpackViews =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<java.util.UUID> openBackpackVillagers =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private record BackpackSession(org.bukkit.entity.Villager villager, java.util.UUID villagerId,
                                   org.bukkit.inventory.ItemStack[] snapshot) {
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

    /**
     * One NMS layer plus the server classes that identify the versions it was compiled against.
     * "Does the layer class load?" is not a version probe: every layer's entry class only names symbols
     * that exist across the whole 1.21.4-26.x range, so the first candidate always loaded and the
     * version-specific classes it references only blew up later, per villager, with no fallback left.
     */
    private record NmsCandidate(String className, List<String> serverVersionPrefixes,
                                List<String> requiredServerClasses) {

        boolean matchesServer(String minecraftVersion) {
            return minecraftVersion != null && serverVersionPrefixes.stream()
                    .anyMatch(minecraftVersion::startsWith);
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
            new NmsCandidate("com.huidu.villagersdelight.impl214.NmsVillagerAi",
                    List.of("1.21.4", "1.21.5", "1.21.6", "1.21.7", "1.21.8", "1.21.9", "1.21.10"),
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
        registerCommands();
        if (!reloadFromConfig()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getPluginManager().registerEvents(new ReloadListener(), this);

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
                reloadFromConfig();
                return;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError t) {
                // Log the whole stack: a failure inside a static initializer surfaces as a bare
                // ExceptionInInitializerError whose toString says nothing about the real cause.
                getLogger().log(java.util.logging.Level.WARNING,
                        "NMS layer " + candidate.className() + " failed to install", t);
                this.aiInjector = null;
            }
        }
        getLogger().warning("No compatible NMS villager AI layer found; villagers keep vanilla behavior.");
    }

    // VillagersDelight has no FarmersDelight dependency, so it carries its own deadline rather than
    // the shared ShutdownBudget from the FD api.
    private static final long BACKPACK_FLUSH_BUDGET_MILLIS = 5000L;

    @Override
    public void onDisable() {
        if (this.aiInjector != null) {
            this.aiInjector.shutdown();
            this.aiInjector = null;
        }
        // The server disables plugins BEFORE kicking players, so the quit-driven InventoryCloseEvent
        // never reaches BackpackCloseListener: dropping the sessions here would keep whatever the player
        // pulled out of the villager while the villager kept its old contents. Flush inline first --
        // scheduled tasks no longer run at this point.
        // Budgeted: a villager whose region refuses the write must not stall the whole shutdown, and
        // every remaining session shares one deadline rather than waiting in turn.
        long deadline = System.nanoTime()
                + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(BACKPACK_FLUSH_BUDGET_MILLIS);
        int skipped = 0;
        for (java.util.Map.Entry<org.bukkit.inventory.Inventory, BackpackSession> entry
                : new java.util.ArrayList<>(this.openBackpackViews.entrySet())) {
            if (System.nanoTime() >= deadline) {
                skipped++;
                continue;
            }
            applyBackpackEdits(entry.getValue(), entry.getKey(), false);
            for (org.bukkit.entity.HumanEntity viewer : List.copyOf(entry.getKey().getViewers())) {
                try {
                    viewer.closeInventory();
                } catch (RuntimeException ignored) {
                    // A viewer in another region may refuse the close; the write-back already happened.
                }
            }
        }
        if (skipped > 0) {
            getLogger().warning("Shutdown budget exhausted; " + skipped
                    + " villager backpack view(s) were not written back.");
        }
        this.openBackpackViews.clear();
        this.openBackpackVillagers.clear();
        this.pickupIds = Set.of();
        this.injectedBaseMaterials = Set.of();
        instance = null;
    }

    private static final String PERM_RELOAD = "villagersdelight.reload";
    private static final String PERM_INVENTORY = "villagersdelight.inventory";
    // Keep the backpack target as a plain UUID argument. Entity selectors and player-name completion
    // are intentionally excluded; the command accepts an entity id copied from debug output.
    private void registerCommands() {
        getLifecycleManager().registerEventHandler(
                io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents.COMMANDS, event -> {
                    com.mojang.brigadier.tree.LiteralCommandNode<io.papermc.paper.command.brigadier.CommandSourceStack> root =
                            io.papermc.paper.command.brigadier.Commands.literal("villagersdelight")
                                    .executes(ctx -> {
                                        ctx.getSource().getSender().sendMessage(language.component("messages.usage"));
                                        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                                    })
                                    .then(io.papermc.paper.command.brigadier.Commands.literal("reload")
                                            .requires(source -> source.getSender().hasPermission(PERM_RELOAD))
                                            .executes(ctx -> {
                                                if (reloadFromConfig()) {
                                                    ctx.getSource().getSender().sendMessage(language.component("messages.config_reloaded"));
                                                }
                                                return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                                            }))
                                    .then(io.papermc.paper.command.brigadier.Commands.literal("inv")
                                            .then(io.papermc.paper.command.brigadier.Commands
                                                    .argument("target", io.papermc.paper.command.brigadier.argument.ArgumentTypes.uuid())
                                                    .suggests((ctx, builder) -> {
                                                        if (ctx.getSource().getSender() instanceof org.bukkit.entity.Player player) {
                                                            player.getNearbyEntities(32, 32, 32).stream()
                                                                    .filter(entity -> entity instanceof org.bukkit.entity.Villager)
                                                                    .map(org.bukkit.entity.Entity::getUniqueId)
                                                                    .map(java.util.UUID::toString)
                                                                    .forEach(builder::suggest);
                                                        }
                                                        return builder.buildFuture();
                                                    })
                                                    .executes(ctx -> {
                                                        openResolvedVillager(ctx);
                                                        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                                                    })))
                                    .build();
                    event.registrar().register(root, "VillagersDelight management command",
                            java.util.List.of("vd"));
                });
    }

    // The entity argument resolves to whatever the selector matched, which is not necessarily a
    // villager and may be empty when a uuid names an entity that is no longer loaded.
    private void openResolvedVillager(
            com.mojang.brigadier.context.CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        org.bukkit.command.CommandSender sender = ctx.getSource().getSender();
        java.util.UUID targetId = ctx.getArgument("target", java.util.UUID.class);
        org.bukkit.entity.Entity entity = Bukkit.getEntity(targetId);
        org.bukkit.entity.Villager villager = entity instanceof org.bukkit.entity.Villager v ? v : null;
        if (villager == null) {
            sender.sendMessage(language.component("messages.target_not_villager"));
            return;
        }
        openVillagerInventory(sender, villager);
    }

    // Opens a villager's inventory in a standard container GUI so its contents can be inspected and edited.
    private boolean openVillagerInventory(org.bukkit.command.CommandSender sender,
                                          org.bukkit.entity.Villager explicitTarget) {
        if (!sender.hasPermission(PERM_INVENTORY)) {
            sender.sendMessage(language.component("messages.no_permission"));
            return true;
        }
        if (!(sender instanceof org.bukkit.entity.Player player)) {
            sender.sendMessage(language.component("messages.player_only"));
            return true;
        }
        org.bukkit.entity.Villager villager = explicitTarget;
        if (villager == null) {
            sender.sendMessage(language.component("messages.villager_not_found"));
            return true;
        }
        final org.bukkit.entity.Villager targetVillager = villager;
        java.util.UUID villagerId = targetVillager.getUniqueId();
        if (!this.openBackpackVillagers.add(villagerId)) {
            sender.sendMessage(language.component("messages.inventory_open"));
            return true;
        }
        // Inventory state belongs to the villager's region. Build the view only after a snapshot has
        // been captured there, then switch to the player's region for the inventory API calls.
        // EntityScheduler.schedule returns false for an already retired entity WITHOUT running the
        // retired callback, so the null return has to be handled here or the guard entry never clears.
        io.papermc.paper.threadedregions.scheduler.ScheduledTask opening;
        try {
            opening = targetVillager.getScheduler().run(this, task -> {
                try {
                    int villagerSize = targetVillager.getInventory().getSize();
                    org.bukkit.inventory.ItemStack[] snapshot = new org.bukkit.inventory.ItemStack[villagerSize];
                    for (int i = 0; i < villagerSize; i++) {
                        org.bukkit.inventory.ItemStack item = targetVillager.getInventory().getItem(i);
                        snapshot[i] = item == null ? null : item.clone();
                    }
                    io.papermc.paper.threadedregions.scheduler.ScheduledTask playerOpen = player.getScheduler().run(this,
                            playerTask -> openBackpackView(player, targetVillager, villagerId, snapshot),
                            () -> openBackpackVillagers.remove(villagerId));
                    if (playerOpen == null) {
                        openBackpackVillagers.remove(villagerId);
                        getLogger().warning("Player became unavailable before villager backpack could open: "
                                + player.getUniqueId());
                    }
                } catch (RuntimeException e) {
                    openBackpackVillagers.remove(villagerId);
                    player.getScheduler().run(this,
                            playerTask -> player.sendMessage(language.component("messages.inventory_failed")), null);
                    getLogger().warning("Failed to read villager backpack for " + villagerId + ": " + e.getMessage());
                }
            }, () -> {
                openBackpackVillagers.remove(villagerId);
                player.getScheduler().run(this,
                        playerTask -> player.sendMessage(language.component("messages.villager_unavailable")), null);
            });
        } catch (RuntimeException e) {
            opening = null;
            getLogger().warning("Failed to schedule villager backpack for " + villagerId + ": " + e.getMessage());
        }
        if (opening == null) {
            openBackpackVillagers.remove(villagerId);
            sender.sendMessage(language.component("messages.inventory_failed"));
        }
        return true;
    }

    private void openBackpackView(org.bukkit.entity.Player player, org.bukkit.entity.Villager villager,
                                  java.util.UUID villagerId, org.bukkit.inventory.ItemStack[] snapshot) {
        try {
            int villagerSize = snapshot.length;
            int viewSize = Math.max(9, ((villagerSize + 8) / 9) * 9);
            org.bukkit.inventory.Inventory view = getServer().createInventory(null, viewSize,
                    language.component("messages.inventory_title"));
            for (int i = 0; i < villagerSize; i++) {
                view.setItem(i, snapshot[i]);
            }
            if (viewSize > villagerSize) {
                org.bukkit.inventory.ItemStack placeholder = new org.bukkit.inventory.ItemStack(org.bukkit.Material.BARRIER);
                org.bukkit.inventory.meta.ItemMeta meta = placeholder.getItemMeta();
                if (meta != null) {
                    meta.displayName(language.component("messages.locked_slot"));
                    placeholder.setItemMeta(meta);
                }
                view.setItem(viewSize - 1, placeholder);
            }
            this.openBackpackViews.put(view, new BackpackSession(villager, villagerId, snapshot));
            player.openInventory(view);
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
        try {
            this.config = VillagersDelightConfig.load(getConfig());
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
        if (this.aiInjector != null) {
            PickupEnabler.apply(this, this.config);
        } else {
            getLogger().info("NMS layer not installed; skipping the villager pickup data pack.");
        }
        augmentFarmerPickup(registry, this.config);
        configureShareItems(registry, this.config);
        configureCompost(registry, this.config);
        if (this.aiInjector != null) {
            this.aiInjector.configureBehavior(this.config.behaviorSettings());
        }
        getLogger().info("Crop index rebuilt: " + (registry == null ? 0 : registry.cropCount()) + " crops.");
        return true;
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
        List<String> foodIds = new ArrayList<>();
        for (Key food : cfg.pickupFoods()) {
            // Vanilla carrots/potatoes are both seeds and food; their consumption is handled by
            // Villager.eatUntilFull exactly as in vanilla. Protect only CE seeds, whose food value
            // is not part of the vanilla table and whose loss can leave a custom crop unplantable.
            boolean customCropSeed = registry != null && registry.allCrops().stream()
                    .map(FDCrop::seedItem)
                    .filter(java.util.Objects::nonNull)
                    .filter(seed -> !"minecraft".equals(seed.namespace()))
                    .anyMatch(food::equals);
            if (!customCropSeed || !cfg.behaviorSettings().protectCustomSeeds()) {
                foodIds.add(food.toString());
            }
        }
        if (!cfg.behaviorSettings().foodEnabled()) {
            foodIds.clear();
        }
        this.aiInjector.configureFoodItems(foodIds);
    }

    // Passes the compost-items configuration to the NMS layer: villagers can compost the configured
    // CE items (matched by their CE custom id, never by base material) into bone meal. The vanilla
    // COMPOSTABLES table stays untouched, so base materials of CE items are not made compostable.
    // Each call replaces the configured set, so /vd reload applies edits immediately.
    private void configureCompost(CropRegistry registry, VillagersDelightConfig cfg) {
        if (this.aiInjector == null) {
            return;
        }
        Set<String> ids = new HashSet<>();
        for (Key item : cfg.compostItems()) {
            ids.add(item.toString());
        }
        // Seeds are production stock, never compost surplus.  Keeping this guard here means a
        // mistaken compost-items entry cannot slowly erase a crop's only replanting source.
        if (registry != null) {
            for (FDCrop crop : registry.allCrops()) {
                Key seed = crop.seedItem();
                if (seed != null) {
                    ids.remove(seed.toString());
                }
            }
        }
        this.aiInjector.configureCeCompost(ids);
    }

    // Passes the share-items configuration to the NMS layer: villagers throw surplus configured
    // items (matched by CE custom id first, vanilla material key as fallback) at nearby villagers.
    // Each call replaces the configured set, so /vd reload applies edits immediately.
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

    /**
     * Writes a backpack view back onto the villager. Only slots the player actually changed are copied,
     * so slots the villager's AI touched while the view was open survive.
     *
     * @param viaScheduler true for the normal close path (hop to the villager's region); false at plugin
     *                     disable, where scheduled tasks never run and the write must happen inline.
     */
    private void applyBackpackEdits(BackpackSession session, org.bukkit.inventory.Inventory view,
                                    boolean viaScheduler) {
        org.bukkit.entity.Villager villager = session.villager();
        org.bukkit.inventory.ItemStack[] snapshot = session.snapshot();
        int size = Math.min(snapshot.length, view.getSize());
        org.bukkit.inventory.ItemStack[] edited = new org.bukkit.inventory.ItemStack[size];
        boolean[] changed = new boolean[size];
        for (int i = 0; i < size; i++) {
            org.bukkit.inventory.ItemStack now = view.getItem(i);
            if (!java.util.Objects.equals(now, snapshot[i])) {
                changed[i] = true;
                edited[i] = now == null ? null : now.clone();
            }
        }
        Runnable write = () -> {
            org.bukkit.inventory.Inventory inv = villager.getInventory();
            for (int i = 0; i < size && i < inv.getSize(); i++) {
                if (changed[i]) {
                    inv.setItem(i, edited[i]);
                }
            }
        };
        if (!viaScheduler) {
            try {
                write.run();
            } catch (RuntimeException t) {
                getLogger().warning("Failed to write a villager backpack back on disable: " + t);
            }
            this.openBackpackVillagers.remove(session.villagerId());
            return;
        }
        // Same null return as above. The write-back is the step that removes from the villager what the
        // player already took, so dropping it silently would leave a duplicate rather than lose an edit.
        io.papermc.paper.threadedregions.scheduler.ScheduledTask writeBack =
                villager.getScheduler().run(this, task -> {
            try {
                write.run();
            } finally {
                this.openBackpackVillagers.remove(session.villagerId());
            }
        }, () -> this.openBackpackVillagers.remove(session.villagerId()));
        if (writeBack == null) {
            this.openBackpackVillagers.remove(session.villagerId());
            getLogger().warning("Villager " + session.villagerId()
                    + " became unavailable before its backpack edits could be written back;"
                    + " its inventory still holds the items the editor removed.");
        }
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
            VillagersDelightPlugin.this.applyBackpackEdits(session, event.getInventory(), true);
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
    // never through the wanted-item sensor (villagers have no walk-to-wanted-item behavior). The tag includes
    // CE seed/food base materials (e.g. nether brick, steak), which also admits vanilla items with the same
    // material. Cancel items whose base material is injected but whose identity is not
    // a configured seed/food. Bread, real seeds, and other naturally wanted vanilla items use non-injected
    // base materials, so vanilla pickup and breeding remain intact.
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
