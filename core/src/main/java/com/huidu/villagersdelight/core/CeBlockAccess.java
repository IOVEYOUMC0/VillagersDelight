package com.huidu.villagersdelight.core;

import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.item.BukkitItem;
import net.momirealms.craftengine.bukkit.world.BukkitExistingBlock;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.plugin.context.ContextHolder;
import net.momirealms.craftengine.core.plugin.context.EventTrigger;
import net.momirealms.craftengine.core.plugin.context.PlayerOptionalContext;
import net.momirealms.craftengine.core.plugin.context.function.Function;
import net.momirealms.craftengine.core.plugin.context.parameter.DirectContextParameters;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.World;
import net.momirealms.craftengine.core.world.WorldPosition;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

// Thin wrapper over the CraftEngine Bukkit API used by the villager AI. Keeps all CE access in one
// place so the NMS layer only sees plain Bukkit types and FDCrop.
public final class CeBlockAccess {

    private CeBlockAccess() {
    }

    // Low-frequency Bukkit-side lookup of a custom block state at a location.
    @Nullable
    public static ImmutableBlockState customStateAt(Block block) {
        return CraftEngineBlocks.getCustomBlockState(block);
    }

    /** The configured crop behind a live block, or null. */
    @Nullable
    public static FDCrop cropAt(Block block) {
        ImmutableBlockState state = customStateAt(block);
        return state == null ? null : CropRegistry.cropOf(state);
    }

    /**
     * Drops declared by the custom block's configured loot table. The returned stacks are copies;
     * callers own them. CE event-function drops are collected separately by {@link #eventDrops}.
     */
    public static List<ItemStack> configuredDrops(Block block) {
        ImmutableBlockState state = customStateAt(block);
        if (state == null) {
            return List.of();
        }
        try {
            World world = BukkitAdaptor.adapt(block.getWorld());
            WorldPosition position = new WorldPosition(world,
                    block.getX() + 0.5, block.getY() + 0.5, block.getZ() + 0.5);
            ContextHolder.Builder builder = ContextHolder.builder()
                    .withParameter(DirectContextParameters.BLOCK, new BukkitExistingBlock(block))
                    .withParameter(DirectContextParameters.POSITION, position)
                    .withParameter(DirectContextParameters.CUSTOM_BLOCK_STATE, state);
            List<ItemStack> drops = new ArrayList<>();
            for (Item item : state.getDrops(builder.build(), world, null)) {
                addCopy(drops, item);
            }
            return List.copyOf(drops);
        } catch (RuntimeException | LinkageError unavailable) {
            return List.of();
        }
    }

    /**
     * Executes the custom block's CE event functions while capturing natural item drops, so a
     * function-based loot table (for example {@code drop_loot}) is included in the returned list.
     * Non-item effects still run normally.
     */
    public static List<ItemStack> eventDrops(Block block, EventTrigger trigger) {
        ImmutableBlockState state = customStateAt(block);
        if (state == null) {
            return List.of();
        }
        try {
            World realWorld = BukkitAdaptor.adapt(block.getWorld());
            List<ItemStack> captured = new ArrayList<>();
            World captureWorld = (World) Proxy.newProxyInstance(
                    World.class.getClassLoader(), new Class<?>[]{World.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("dropItemNaturally") && args != null && args.length == 2
                                && args[1] instanceof Item item) {
                            addCopy(captured, item);
                            return null;
                        }
                        try {
                            return method.invoke(realWorld, args);
                        } catch (InvocationTargetException invocation) {
                            throw invocation.getCause();
                        }
                    });
            WorldPosition position = new WorldPosition(captureWorld,
                    block.getX() + 0.5, block.getY() + 0.5, block.getZ() + 0.5);
            ContextHolder.Builder builder = ContextHolder.builder()
                    .withParameter(DirectContextParameters.WORLD, captureWorld)
                    .withParameter(DirectContextParameters.BLOCK, new BukkitExistingBlock(block))
                    .withParameter(DirectContextParameters.POSITION, position)
                    .withParameter(DirectContextParameters.CUSTOM_BLOCK_STATE, state);
            Function.execute(PlayerOptionalContext.of(null, builder), state.owner().value().eventFunctions(trigger));
            return List.copyOf(captured);
        } catch (RuntimeException | LinkageError unavailable) {
            return List.of();
        }
    }

    /** Loot-table drops plus captured CE event-function drops, in that order. */
    public static List<ItemStack> harvestDrops(Block block) {
        List<ItemStack> drops = new ArrayList<>(configuredDrops(block));
        drops.addAll(eventDrops(block, EventTrigger.BLOCK_BREAK));
        return List.copyOf(drops);
    }

    private static void addCopy(List<ItemStack> target, Item item) {
        if (item instanceof BukkitItem bukkitItem) {
            ItemStack stack = bukkitItem.getBukkitItem();
            if (stack != null && !stack.isEmpty()) {
                target.add(stack.clone());
            }
        }
    }

    /**
     * Validates the configured soil/water requirement for a live planting target. Vanilla farmland is
     * always accepted for dry crops; CE soils use the crop's configured soil set and extra soils.
     */
    public static boolean canPlantAt(Block target, FDCrop crop) {
        if (target == null || crop == null) {
            return false;
        }
        if (target.getType().isAir()) {
            if (crop.water() != null) {
                return false;
            }
            Block below = target.getRelative(BlockFace.DOWN);
            if (below.getType() == Material.FARMLAND) {
                return true;
            }
            ImmutableBlockState belowState = customStateAt(below);
            return belowState != null && CropRegistry.canPlantOn(crop, belowState);
        }
        if (!target.isLiquid() || crop.water() == null) {
            return false;
        }
        String waterId = crop.water().toString();
        if ("minecraft:water".equals(waterId) && target.getType() != Material.WATER) {
            return false;
        }
        // A water crop also checks the fluid bed against its configured soils.
        Block bed = target.getRelative(BlockFace.DOWN);
        String bedId = bed.getType().getKey().toString();
        if (crop.soils().stream().anyMatch(id -> id.toString().equals(bedId))) {
            return true;
        }
        ImmutableBlockState bedState = customStateAt(bed);
        return bedState != null && CropRegistry.canPlantOn(crop, bedState);
    }

    /** Places a configured crop block at age 0 using a string id; false for an invalid/unknown id. */
    public static boolean placeCrop(Block block, String blockId, int age, boolean playSound) {
        if (block == null || blockId == null) {
            return false;
        }
        try {
            return placeCrop(block.getLocation(), Key.of(blockId), age, playSound);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    /** Resets a TALL crop's lower half after its upper half was harvested. */
    public static boolean resetTallLower(Block lower, FDCrop crop) {
        if (lower == null || crop == null) {
            return false;
        }
        ImmutableBlockState state = customStateAt(lower);
        if (state == null) {
            return false;
        }
        ImmutableBlockState reset = crop.resetAfterUpperHarvest(state);
        return reset != state && placeState(lower, reset);
    }

    // Places a custom crop at its first growth stage. Returns false if the block id is unknown.
    public static boolean placeCrop(Location location, Key blockId, int age, boolean playSound) {
        BlockDefinition definition = CraftEngineBlocks.byId(blockId);
        if (definition == null) {
            return false;
        }
        ImmutableBlockState state = definition.defaultState();
        Property<Integer> ageProperty = BlockBehaviorFactory.getOptionalProperty(definition, "age", Integer.class);
        if (ageProperty != null) {
            state = state.with(ageProperty, age);
        }
        return CraftEngineBlocks.place(location, state, playSound);
    }

    // Removes a custom block from the world. No drops are produced by CE here; the caller decides.
    public static boolean removeBlock(Block block) {
        return CraftEngineBlocks.remove(block);
    }

    // Places a specific custom block state (e.g. a reset lower half after harvesting the upper one).
    public static boolean placeState(Block block, ImmutableBlockState state) {
        return CraftEngineBlocks.place(block.getLocation(), state, false);
    }

    // NMS-level block state a custom block would occupy, for use as the "to-be-placed" state in
    // Bukkit pre-placement events. Returns null if the block id is unknown.
    @Nullable
    public static Object placeStateMinecraft(Key blockId) {
        BlockDefinition definition = CraftEngineBlocks.byId(blockId);
        return definition == null ? null : definition.defaultState().customBlockState().minecraftState();
    }
}
