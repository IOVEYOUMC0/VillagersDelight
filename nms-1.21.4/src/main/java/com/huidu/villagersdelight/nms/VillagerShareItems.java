package com.huidu.villagersdelight.impl214;

import com.huidu.villagersdelight.core.CeItemAccess;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.item.BukkitItemDefinition;
import net.momirealms.craftengine.core.util.Key;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import org.bukkit.Location;
import org.bukkit.craftbukkit.inventory.CraftItemStack;

import java.util.Map;

// Lets villagers share surplus items (configured via share-items in config.yml) with nearby
// villagers: on a small per-tick chance the villager takes one item from a stack that holds more
// than one and throws it toward a random nearby villager. Newer MC versions removed the vanilla
// ShareItems behaviour entirely, so this adds a config-driven replacement without touching the
// vanilla AI. CE items are matched by their custom id and re-created from the CE registry before
// being thrown, so they never drop as their underlying vanilla material.
public final class VillagerShareItems extends Behavior<Villager> {

    private static final int SCAN_RANGE = 6;
    private static final int PICKUP_DELAY = 40;

    public VillagerShareItems() {
        super(Map.of());
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, Villager villager) {
        return NmsVillagerAi.SHARE_ENABLED && level.getRandom().nextFloat() < 0.1F;
    }

    @Override
    protected void start(ServerLevel level, Villager villager, long gameTime) {
        ShareCandidate candidate = findShareItem(villager);
        if (candidate == null) {
            return;
        }
        // Share the surplus above half a stack in one throw, gated on the giver's OWN count against a fixed
        // floor. removeItem shrinks the giver immediately, so it drops below the floor after one throw and
        // stops qualifying next tick — self-limiting, no cooldown, and never a dump-to-1.
        int keep = candidate.maxStackSize() / 2;
        if (candidate.count() <= keep) {
            return;
        }
        int amount = candidate.count() / 2;
        ItemStack probe = rebuildIfCustom(villager.getInventory().getItem(candidate.slot()).copy(), candidate.customId());
        probe.setCount(amount);
        Villager receiver = findReceiver(level, villager, candidate, probe);
        if (receiver == null) {
            return;
        }
        ItemStack removed = villager.getInventory().removeItem(candidate.slot(), amount);
        if (removed.isEmpty()) {
            return;
        }
        ItemStack stack = rebuildIfCustom(removed, candidate.customId());
        ItemEntity item = new ItemEntity(level, villager.getX(), villager.getEyeY() - 0.3, villager.getZ(), stack);
        item.setThrower(villager);
        item.setPickUpDelay(PICKUP_DELAY);
        level.addFreshEntity(item);
    }

    // Finds a configured item held in surplus (count > 1) without removing it. A CE stack matches
    // by its custom id (when the id survived into the villager inventory); a vanilla item matches
    // by its material key. keptCount is what the thrower retains after giving one away, used as the
    // neediness threshold: a neighbour is a valid target only if it holds strictly fewer than this.
    private ShareCandidate findShareItem(Villager villager) {
        SimpleContainer inventory = villager.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || stack.getCount() <= 1) {
                continue;
            }
            org.bukkit.inventory.ItemStack bukkit = CraftItemStack.asBukkitCopy(stack);
            Key customId = CeItemAccess.customItemId(bukkit);
            if (customId != null && NmsVillagerAi.isShareId(customId.toString())) {
                return new ShareCandidate(i, customId, customId.toString(), true, stack.getCount(), stack.getMaxStackSize());
            }
            String vanillaKey = bukkit.getType().getKey().toString();
            if (NmsVillagerAi.isShareId(vanillaKey)) {
                return new ShareCandidate(i, null, vanillaKey, false, stack.getCount(), stack.getMaxStackSize());
            }
        }
        return null;
    }

    // The nearest villager that both wants the item and holds less than the fixed floor, i.e. a genuine
    // recipient. Runs on the villager's own region thread; the 6-block scan stays within the current region
    // so reading neighbour inventories here is Folia-safe.
    private Villager findReceiver(ServerLevel level, Villager villager, ShareCandidate candidate, ItemStack giveStack) {
        int keep = candidate.maxStackSize() / 2;
        Location center = new Location(level.getWorld(), villager.getX(), villager.getY(), villager.getZ());
        int minChunkX = ((int) Math.floor(center.getX() - SCAN_RANGE)) >> 4;
        int maxChunkX = ((int) Math.floor(center.getX() + SCAN_RANGE)) >> 4;
        int minChunkZ = ((int) Math.floor(center.getZ() - SCAN_RANGE)) >> 4;
        int maxChunkZ = ((int) Math.floor(center.getZ() + SCAN_RANGE)) >> 4;
        if (!org.bukkit.Bukkit.isOwnedByCurrentRegion(level.getWorld(), minChunkX, minChunkZ, maxChunkX, maxChunkZ)) {
            return null;
        }
        for (org.bukkit.entity.Entity entity : level.getWorld().getNearbyEntities(center, SCAN_RANGE, SCAN_RANGE, SCAN_RANGE)) {
            if (!(entity instanceof org.bukkit.entity.Villager other)
                    || !org.bukkit.Bukkit.isOwnedByCurrentRegion(other)
                    || other.getUniqueId().equals(villager.getUUID())) {
                continue;
            }
            Villager handle = ((org.bukkit.craftbukkit.entity.CraftVillager) other).getHandle();
            if (countHeld(handle, candidate) < keep && handle.wantsToPickUp(level, giveStack)) {
                return handle;
            }
        }
        return null;
    }

    // Total amount of the share item the given villager currently holds, matched the same way the
    // candidate was matched (custom id for CE items, material key for vanilla items).
    private static int countHeld(Villager villager, ShareCandidate candidate) {
        SimpleContainer inventory = villager.getInventory();
        int total = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            org.bukkit.inventory.ItemStack bukkit = CraftItemStack.asBukkitCopy(stack);
            if (candidate.custom()) {
                Key customId = CeItemAccess.customItemId(bukkit);
                if (customId != null && candidate.shareId().equals(customId.toString())) {
                    total += stack.getCount();
                }
            } else if (candidate.shareId().equals(bukkit.getType().getKey().toString())) {
                total += stack.getCount();
            }
        }
        return total;
    }

    // Re-creates a CE stack from the registry when its custom id was captured; plain items pass
    // through unchanged.
    private static ItemStack rebuildIfCustom(ItemStack removed, Key customId) {
        if (customId == null) {
            return removed;
        }
        try {
            BukkitItemDefinition definition = CraftEngineItems.byId(customId);
            if (definition == null) {
                return removed;
            }
            org.bukkit.inventory.ItemStack rebuilt = definition.buildBukkitItem();
            rebuilt.setAmount(removed.getCount());
            return CraftItemStack.asNMSCopy(rebuilt);
        } catch (RuntimeException ignored) {
            return removed;
        }
    }

    private record ShareCandidate(int slot, Key customId, String shareId, boolean custom, int count, int maxStackSize) {
    }
}
