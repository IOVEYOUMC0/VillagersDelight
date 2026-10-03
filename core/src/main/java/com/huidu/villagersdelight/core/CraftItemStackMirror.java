package com.huidu.villagersdelight.core;

import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.function.Supplier;

/**
 * The reflective lookup of CraftItemStack's NMS-to-Bukkit mirror factory.
 *
 *
 * Paper 26.3 renamed CraftItemStack.asCraftMirror(net.minecraft.world.item.ItemStack) to
 * asBukkitMirror and removed the old name, while the shared behaviour sources under
 * nms-common are compiled into every layer's jar: naming either method in that source would
 * only compile against the layers whose dev bundle carries it. The name is therefore looked up at
 * runtime from this class, which touches neither Minecraft nor CraftBukkit types, so it also compiles
 * and runs without a server.
 *
 *
 * The lookup is by shape, not just by name: a public static one-argument method that takes the item
 * stack type and returns a Bukkit stack. A build that renamed the factory once more is reported loudly
 * by require(Class, Class, String) instead of surfacing as a NoSuchMethodError.
 */
public final class CraftItemStackMirror {

    /** The name 26.3 onwards uses for the factory. */
    public static final String CURRENT_NAME = "asBukkitMirror";

    /** The name releases before 26.3 use for it. */
    public static final String LEGACY_NAME = "asCraftMirror";

    private CraftItemStackMirror() {
    }

    /**
     * The mirror factory declared by owner: asBukkitMirror where that exists,
     * otherwise asCraftMirror, otherwise null.
     *
     * @param owner         the class that declares the factory, normally
     *                      org.bukkit.craftbukkit.inventory.CraftItemStack
     * @param itemStackType the type that factory takes, normally
     *                      net.minecraft.world.item.ItemStack
     */
    public static Method resolve(Class<?> owner, Class<?> itemStackType) {
        Method method = find(owner, itemStackType, CURRENT_NAME);
        return method != null ? method : find(owner, itemStackType, LEGACY_NAME);
    }

    /**
     * As resolve(Class, Class), but a server that carries neither name is a hard failure that
     * names the version it was looked for on. Both names missing means this release moved the factory
     * again; an opaque NoSuchMethodError at the first villager would hide that.
     *
     * @throws IllegalStateException when owner declares neither name
     */
    public static Method require(Class<?> owner, Class<?> itemStackType, String serverVersion) {
        Method method = resolve(owner, itemStackType);
        if (method == null) {
            throw new IllegalStateException("CraftItemStack has neither " + CURRENT_NAME + " nor "
                    + LEGACY_NAME + "(" + itemStackType.getName() + ") on this server ("
                    + serverVersion + ")");
        }
        return method;
    }

    /**
     * The mirrored stack, never null: a null result is normalised to ItemStack#empty(), whose
     * material is AIR and whose material key is minecraft:air.
     */
    public static ItemStack normalise(ItemStack mirrored) {
        return normalise(mirrored, ItemStack::empty);
    }

    /**
     * The null case of normalise(ItemStack) against a caller-supplied empty stack, so the
     * branch can be driven by a check: ItemStack#empty() resolves through
     * Bukkit.getUnsafe() and therefore only exists on a running server, while the branch's
     * behaviour is the same either way.
     */
    static ItemStack normalise(ItemStack mirrored, Supplier<ItemStack> empty) {
        return mirrored == null ? empty.get() : mirrored;
    }

    private static Method find(Class<?> owner, Class<?> itemStackType, String name) {
        for (Method method : owner.getMethods()) {
            if (!method.getName().equals(name)
                    || !Modifier.isStatic(method.getModifiers())
                    || method.getParameterCount() != 1
                    || !method.getParameterTypes()[0].isAssignableFrom(itemStackType)
                    || !ItemStack.class.isAssignableFrom(method.getReturnType())) {
                continue;
            }
            return method;
        }
        return null;
    }
}
