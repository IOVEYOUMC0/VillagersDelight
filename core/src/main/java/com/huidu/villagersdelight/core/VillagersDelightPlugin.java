package com.huidu.villagersdelight.core;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.kyori.adventure.text.Component;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.api.event.CraftEngineReloadEvent;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.Listener;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemFlag;
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
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class VillagersDelightPlugin extends JavaPlugin {

    private static VillagersDelightPlugin instance;

    @Nullable
    private VillagerAiInjector aiInjector;
    private VillagersDelightConfig config;
    private final VillagersDelightLanguage language = new VillagersDelightLanguage(this);
    // The open backpack view plus the villager's inventory as it was at open time, so on close only the
    // slots the player actually changed are written back (AI-mutated slots are left alone). Keyed on the
    // view inventory; concurrent because open/click/close run on different players' region threads.
    private final Map<Inventory, BackpackSession> openBackpackViews = new ConcurrentHashMap<>();
    private final Set<UUID> openBackpackVillagers = ConcurrentHashMap.newKeySet();

    private record BackpackSession(Villager villager, UUID villagerId, ItemStack[] snapshot) {
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
            new NmsCandidate("com.huidu.villagersdelight.impl215.NmsVillagerAi",
                    List.of("1.21.5", "1.21.6", "1.21.7", "1.21.8", "1.21.9", "1.21.10"),
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
                + TimeUnit.MILLISECONDS.toNanos(BACKPACK_FLUSH_BUDGET_MILLIS);
        int skipped = 0;
        for (Map.Entry<Inventory, BackpackSession> entry
                : new ArrayList<>(this.openBackpackViews.entrySet())) {
            if (System.nanoTime() >= deadline) {
                skipped++;
                continue;
            }
            applyBackpackEdits(entry.getValue(), entry.getKey(), false);
            for (HumanEntity viewer : List.copyOf(entry.getKey().getViewers())) {
                try {
                    viewer.closeInventory();
                } catch (RuntimeException ignored) {
                    // A viewer in another region may refuse the close; the write-back already happened.
                }
            }
        }
        if (skipped > 0) {
            // Same wording family as FarmersDelight's shutdown budget: the language layer names the step and
            // says what did not happen, instead of a hardcoded English sentence.
            getLogger().warning(language.get("messages.disable_budget_exhausted",
                    "step", language.get("messages.disable_step_write_back_backpacks"),
                    "count", skipped));
        }
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
        // Inventory state belongs to the villager's region. Build the view only after a snapshot has
        // been captured there, then switch to the player's region for the inventory API calls.
        // EntityScheduler.schedule returns false for an already retired entity WITHOUT running the
        // retired callback, so the null return has to be handled here or the guard entry never clears.
        ScheduledTask opening;
        try {
            opening = targetVillager.getScheduler().run(this, task -> {
                try {
                    int villagerSize = targetVillager.getInventory().getSize();
                    ItemStack[] snapshot = new ItemStack[villagerSize];
                    for (int i = 0; i < villagerSize; i++) {
                        ItemStack item = targetVillager.getInventory().getItem(i);
                        snapshot[i] = item == null ? null : item.clone();
                    }
                    ScheduledTask playerOpen = player.getScheduler().run(this,
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

    private void openBackpackView(Player player, Villager villager,
                                  UUID villagerId, ItemStack[] snapshot) {
        try {
            int villagerSize = snapshot.length;
            int viewSize = Math.max(9, ((villagerSize + 8) / 9) * 9);
            Inventory view = getServer().createInventory(null, viewSize,
                    language.component("messages.inventory_title"));
            for (int i = 0; i < villagerSize; i++) {
                view.setItem(i, snapshot[i]);
            }
            if (viewSize > villagerSize) {
                ItemStack placeholder = new ItemStack(Material.BARRIER);
                ItemMeta meta = placeholder.getItemMeta();
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
        configureCompost(registry, this.config);
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

    /**
     * Writes a backpack view back onto the villager. Only slots the player actually changed are copied,
     * so slots the villager's AI touched while the view was open survive.
     *
     * @param viaScheduler true for the normal close path (hop to the villager's region); false at plugin
     *                     disable, where scheduled tasks never run and the write must happen inline.
     */
    private void applyBackpackEdits(BackpackSession session, Inventory view,
                                    boolean viaScheduler) {
        Villager villager = session.villager();
        ItemStack[] snapshot = session.snapshot();
        int size = Math.min(snapshot.length, view.getSize());
        ItemStack[] edited = new ItemStack[size];
        boolean[] changed = new boolean[size];
        for (int i = 0; i < size; i++) {
            ItemStack now = view.getItem(i);
            if (!Objects.equals(now, snapshot[i])) {
                changed[i] = true;
                edited[i] = now == null ? null : now.clone();
            }
        }
        Runnable write = () -> {
            Inventory inv = villager.getInventory();
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
        ScheduledTask writeBack =
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
        public void onClose(InventoryCloseEvent event) {
            BackpackSession session = VillagersDelightPlugin.this.openBackpackViews.remove(event.getInventory());
            if (session == null) {
                return;
            }
            VillagersDelightPlugin.this.applyBackpackEdits(session, event.getInventory(), true);
        }

        @EventHandler(ignoreCancelled = true)
        public void onClick(InventoryClickEvent event) {
            if (!VillagersDelightPlugin.this.openBackpackViews.containsKey(event.getView().getTopInventory())) {
                return;
            }
            Inventory view = event.getView().getTopInventory();
            if (event.getRawSlot() >= 0 && event.getRawSlot() < view.getSize()
                    && event.getRawSlot() == view.getSize() - 1) {
                event.setCancelled(true);
            }
        }

        @EventHandler(ignoreCancelled = true)
        public void onDrag(InventoryDragEvent event) {
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
