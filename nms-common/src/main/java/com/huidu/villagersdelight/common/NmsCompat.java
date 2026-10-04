package com.huidu.villagersdelight.common;

import io.papermc.paper.event.entity.EntityCompostItemEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.util.context.ContextKey;
import net.minecraft.util.context.ContextKeySet;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.block.CraftBlock;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;

/**
 * The one place where villager-relevant Minecraft changes between the supported releases are
 * absorbed. The behaviour sources under nms-common are compiled into every layer, so they
 * have to compile against every layer's dev bundle and run on every server that layer
 * targets; a member that exists on some of those releases and not on others is exactly the kind of
 * difference that belongs behind a single documented seam instead of at each call site.
 *
 *
 * What moved in 26.3, and what happens here:
 * - CraftItemStack.asCraftMirror became asBukkitMirror - see
 *       VillagerItems#bukkitStack(ItemStack), which resolves whichever name the running
 *       server has instead of calling either one directly.
 * - Villager.FOOD_POINTS became the VILLAGER_FOOD item component -
 *       vanillaVillagerFood(ItemStack).
 * - ComposterBlock.COMPOSTABLES became the COMPOSTABLE item component, and the
 *       caller's random roll moved into that component's provider -
 *       vanillaCompostLevels(ServerLevel, Entity, BlockPos, BlockState, ItemStack, int).
 * - BonemealableBlock.isValidBonemealTarget gained a BonemealSource argument -
 *       isValidBonemealTarget(BonemealableBlock, ServerLevel, BlockPos, BlockState).
 * - EntityCompostItemEvent carries levels to raise instead of a boolean -
 *       compostEvent / compostLevels.
 *
 *
 * Every lookup happens once, in the static initialiser, and a member that cannot be found is not
 * an error: it means the running server predates that change, so the other branch of the pair runs.
 * Only the 26.3 side of each pair is version-specific, which is why a failed lookup is silently
 * null here but a failed call throws.
 */
public final class NmsCompat {

    private NmsCompat() {
    }

    // ---------------------------------------------------------------------------------------------
    // Villager food
    // ---------------------------------------------------------------------------------------------

    /** Villager.FOOD_POINTS (Item -> nutrition) before 26.3, else null. */
    @Nullable
    private static final Map<?, ?> FOOD_POINTS = staticField(Villager.class, "FOOD_POINTS", Map.class);

    /** DataComponents.VILLAGER_FOOD, the component that replaced the table above. */
    @Nullable
    private static final DataComponentType<?> VILLAGER_FOOD =
            staticField(DataComponents.class, "VILLAGER_FOOD", DataComponentType.class);

    /** VillagerFood.nutrition(), the accessor of the value that component carries. */
    @Nullable
    private static final Method NUTRITION = method(findClass("net.minecraft.world.food.VillagerFood"), "nutrition");

    /**
     * The vanilla food value of a stack, or null when vanilla does not count the item as villager
     * food. Feeds both the vanilla-points lookups and the check that keeps vanilla food items out of
     * the addon's own configured list.
     */
    @Nullable
    public static Integer vanillaVillagerFood(ItemStack stack) {
        if (VILLAGER_FOOD != null) {
            Object food = stack.get(VILLAGER_FOOD);
            return food == null ? null : (Integer) invoke(NUTRITION, food);
        }
        return FOOD_POINTS == null ? null : (Integer) FOOD_POINTS.get(stack.getItem());
    }

    // ---------------------------------------------------------------------------------------------
    // Composting
    // ---------------------------------------------------------------------------------------------

    /** ComposterBlock.COMPOSTABLES (ItemLike -> chance) before 26.3, else null. */
    @Nullable
    private static final Map<?, ?> COMPOSTABLES = staticField(ComposterBlock.class, "COMPOSTABLES", Map.class);

    /** DataComponents.COMPOSTABLE, the component that replaced the table above. */
    @Nullable
    private static final DataComponentType<?> COMPOSTABLE =
            staticField(DataComponents.class, "COMPOSTABLE", DataComponentType.class);

    /** Compostable.layers(), the value that component carries. */
    @Nullable
    private static final Method COMPOSTABLE_LAYERS =
            method(findClass("net.minecraft.world.item.component.Compostable"), "layers");

    /** ResolvableInt.get(LootContext, int), which resolves - and rolls - those layers. */
    @Nullable
    private static final Method RESOLVE_LAYERS = method(
            findClass("net.minecraft.world.level.storage.loot.providers.number.ints.ResolvableInt"),
            "get", LootContext.class, int.class);

    /** LootContextParamSets.BLOCK_INTERACT, the set ComposterBlock.addLayer resolves in. */
    @Nullable
    private static final ContextKeySet BLOCK_INTERACT =
            staticField(LootContextParamSets.class, "BLOCK_INTERACT", ContextKeySet.class);

    /** LootContextParams.INTERACTING_ENTITY, absent before 26.3. */
    @Nullable
    private static final ContextKey<?> INTERACTING_ENTITY =
            staticField(LootContextParams.class, "INTERACTING_ENTITY", ContextKey.class);

    /**
     * Levels the vanilla rules would raise the composter by for this stack, or null when vanilla does
     * not know the item.
     *
     *
     * 26.3 answers with the number of levels directly and rolls inside the provider, so nothing is
     * left for the caller to do; older releases only expose a chance per item, so the roll the old
     * caller performed is done here. Both models are folded into one answer so the behaviour above
     * does not have to know which one it is running on.
     */
    @Nullable
    static Integer vanillaCompostLevels(ServerLevel level, Entity entity, BlockPos pos, BlockState state,
                                       ItemStack stack, int fillLevel) {
        if (COMPOSTABLE != null) {
            Object compostable = stack.get(COMPOSTABLE);
            if (compostable == null) {
                return null;
            }
            LootParams.Builder params = new LootParams.Builder(level)
                    .withParameter(LootContextParams.BLOCK_STATE, state)
                    .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(pos));
            if (INTERACTING_ENTITY != null) {
                withInteractingEntity(params, entity);
            }
            LootContext context = new LootContext.Builder(params.create(BLOCK_INTERACT)).create(Optional.empty());
            return (Integer) invoke(RESOLVE_LAYERS, invoke(COMPOSTABLE_LAYERS, compostable), context, 0);
        }
        Object chance = COMPOSTABLES == null ? null : COMPOSTABLES.get(stack.getItem());
        return chance == null ? null : rolledLevels(level.getRandom(), fillLevel, (Float) chance);
    }

    /**
     * The pre-26.3 roll, also used for the chances this plugin configures itself: a level-0 composter
     * always accepts an item with a non-zero chance, otherwise the chance is the probability of a
     * single level. The chance is clamped first so an out-of-range configured value cannot turn into
     * a probability above 1.
     */
    static int rolledLevels(RandomSource random, int fillLevel, float chance) {
        float clamped = Math.max(0.0F, Math.min(1.0F, chance));
        if (clamped <= 0.0F) {
            return 0;
        }
        return fillLevel == 0 || random.nextDouble() < clamped ? 1 : 0;
    }

    // withOptionalParameter is generic in the key's type and the key is only known as a
    // ContextKey<?> here, so the call is wrapped instead of leaking an unchecked cast to the caller.
    @SuppressWarnings("unchecked")
    private static void withInteractingEntity(LootParams.Builder params, Entity entity) {
        params.withOptionalParameter((ContextKey<Entity>) INTERACTING_ENTITY, entity);
    }

    // ---------------------------------------------------------------------------------------------
    // Compost event
    // ---------------------------------------------------------------------------------------------

    /** EntityCompostItemEvent(Entity, Block, ItemStack, int|boolean); the last type differs. */
    private static final Constructor<EntityCompostItemEvent> COMPOST_EVENT = compostEventConstructor();

    /** True where that constructor takes the levels to raise (26.3) instead of a boolean. */
    private static final boolean COMPOST_EVENT_TAKES_LEVELS = COMPOST_EVENT.getParameterTypes()[3] == int.class;

    /** CompostItemEvent.getLevelsToRaise(), present only where the constructor takes levels. */
    @Nullable
    private static final Method GET_LEVELS_TO_RAISE = method(EntityCompostItemEvent.class, "getLevelsToRaise");

    private static Constructor<EntityCompostItemEvent> compostEventConstructor() {
        Class<?>[] prefix = {org.bukkit.entity.Entity.class, Block.class,
                org.bukkit.inventory.ItemStack.class};
        try {
            return EntityCompostItemEvent.class.getConstructor(prefix[0], prefix[1], prefix[2], int.class);
        } catch (NoSuchMethodException withLevels) {
            try {
                return EntityCompostItemEvent.class.getConstructor(prefix[0], prefix[1], prefix[2], boolean.class);
            } catch (NoSuchMethodException withFlag) {
                throw new IllegalStateException("EntityCompostItemEvent constructor not found", withFlag);
            }
        }
    }

    /**
     * The compost event for one attempt, created with whichever shape of the last argument the
     * running server expects. Listeners read it back through compostLevels.
     */
    static EntityCompostItemEvent compostEvent(Entity entity, ServerLevel level, BlockPos pos, ItemStack stack,
                                               int levels) {
        Object raise = COMPOST_EVENT_TAKES_LEVELS ? levels : levels > 0;
        try {
            return COMPOST_EVENT.newInstance(entity.getBukkitEntity(), CraftBlock.at(level, pos),
                    VillagerItems.bukkitStack(stack), raise);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot build EntityCompostItemEvent", e);
        }
    }

    /** Levels the event ended up carrying, after any listener override. */
    static int compostLevels(EntityCompostItemEvent event) {
        if (GET_LEVELS_TO_RAISE == null) {
            return event.willRaiseLevel() ? 1 : 0;
        }
        return (Integer) invoke(GET_LEVELS_TO_RAISE, event);
    }

    // ---------------------------------------------------------------------------------------------
    // Bone meal
    // ---------------------------------------------------------------------------------------------

    /**
     * BonemealableBlock.isValidBonemealTarget: 26.3 appended a BonemealSource, so the
     * method is resolved by name and called with as many arguments as it declares.
     */
    private static final Method IS_VALID_BONEMEAL_TARGET = bonemealTargetMethod();

    /** BonemealSource.MOB: this path always runs for a mob, never for a player interaction. */
    @Nullable
    private static final Object BONEMEAL_SOURCE_MOB =
            enumConstant("net.minecraft.world.level.block.BonemealSource", "MOB");

    private static Method bonemealTargetMethod() {
        for (Method method : BonemealableBlock.class.getMethods()) {
            if ("isValidBonemealTarget".equals(method.getName())) {
                return method;
            }
        }
        throw new IllegalStateException("BonemealableBlock.isValidBonemealTarget not found");
    }

    static boolean isValidBonemealTarget(BonemealableBlock block, ServerLevel level, BlockPos pos, BlockState state) {
        Object[] args = IS_VALID_BONEMEAL_TARGET.getParameterCount() == 4
                ? new Object[]{level, pos, state, BONEMEAL_SOURCE_MOB}
                : new Object[]{level, pos, state};
        return (Boolean) invoke(IS_VALID_BONEMEAL_TARGET, block, args);
    }

    // ---------------------------------------------------------------------------------------------
    // Reflection helpers. A missing member is a version difference, not a failure.
    // ---------------------------------------------------------------------------------------------

    @Nullable
    private static Class<?> findClass(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    @Nullable
    private static Method method(@Nullable Class<?> owner, String name, Class<?>... parameterTypes) {
        if (owner == null) {
            return null;
        }
        try {
            return owner.getMethod(name, parameterTypes);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    @Nullable
    private static <T> T staticField(Class<?> owner, String name, Class<T> type) {
        Object value;
        try {
            value = owner.getField(name).get(null);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            return null;
        }
        return type.isInstance(value) ? type.cast(value) : null;
    }

    @Nullable
    private static Object enumConstant(String className, String constant) {
        Class<?> clazz = findClass(className);
        Object[] constants = clazz == null ? null : clazz.getEnumConstants();
        if (constants == null) {
            return null;
        }
        for (Object value : constants) {
            if (value instanceof Enum<?> candidate && candidate.name().equals(constant)) {
                return value;
            }
        }
        return null;
    }

    private static Object invoke(Method method, Object target, Object... args) {
        try {
            return method.invoke(target, args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot call " + method, e);
        }
    }
}
