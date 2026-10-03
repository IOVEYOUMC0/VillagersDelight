package com.huidu.villagersdelight.core;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Drives the mirror-factory lookup of CraftItemStackMirror without a server.
 *
 *
 * The real factory lives on org.bukkit.craftbukkit.inventory.CraftItemStack, which only exists
 * on the server, so the resolution takes the owner as an argument and is driven here with stand-ins
 * that imitate each release's shape: 26.3 has asBukkitMirror alone, releases before it have
 * asCraftMirror alone, and a build that renamed the factory again has neither.
 */
public final class CraftItemStackMirrorCheck {

    private CraftItemStackMirrorCheck() {
    }

    /** Stands in for net.minecraft.world.item.ItemStack as the factory's argument type. */
    private static final class NmsStack {
    }

    /** The 26.3-and-later shape. */
    public static final class CurrentShape {
        public static ItemStack asBukkitMirror(NmsStack stack) {
            return ItemStack.empty();
        }
    }

    /** The shape every release before 26.3 has. */
    public static final class LegacyShape {
        public static ItemStack asCraftMirror(NmsStack stack) {
            return ItemStack.empty();
        }
    }

    /** A build carrying both names: the current one must win. */
    public static final class BothNames {
        public static ItemStack asCraftMirror(NmsStack stack) {
            return ItemStack.empty();
        }

        public static ItemStack asBukkitMirror(NmsStack stack) {
            return ItemStack.empty();
        }
    }

    /** A build that renamed the factory once more: neither name exists. */
    public static final class RenamedAgain {
        public static ItemStack asSomethingElseAgain(NmsStack stack) {
            return ItemStack.empty();
        }
    }

    /** The right name, but an instance method rather than the static factory. */
    public static final class NotStatic {
        public ItemStack asBukkitMirror(NmsStack stack) {
            return ItemStack.empty();
        }
    }

    /** The right name, but taking something that is not an item stack. */
    public static final class WrongArgument {
        public static ItemStack asBukkitMirror(String stack) {
            return ItemStack.empty();
        }
    }

    /** The right name, but not returning a Bukkit stack. */
    public static final class WrongReturn {
        public static String asBukkitMirror(NmsStack stack) {
            return "";
        }
    }

    // An AIR stack without a server: the real empty stack comes from ItemStack.empty(), which goes
    // through Bukkit.getUnsafe() and so only exists on a running server.
    private static final class AirStandIn extends ItemStack {
        @Override
        public Material getType() {
            return Material.AIR;
        }
    }

    public static void main(String[] args) {
        Method current = CraftItemStackMirror.resolve(CurrentShape.class, NmsStack.class);
        check(current != null, "asBukkitMirror was not resolved where the server has it");
        check(current.getName().equals("asBukkitMirror"), "resolved " + current.getName());
        check(Modifier.isStatic(current.getModifiers()), "resolved a non-static method");
        check(CraftItemStackMirror.resolve(BothNames.class, NmsStack.class).getName().equals("asBukkitMirror"),
                "asCraftMirror won over asBukkitMirror where both exist");

        Method legacy = CraftItemStackMirror.resolve(LegacyShape.class, NmsStack.class);
        check(legacy != null, "asCraftMirror was not used as the fallback");
        check(legacy.getName().equals("asCraftMirror"), "fallback resolved " + legacy.getName());

        check(CraftItemStackMirror.resolve(RenamedAgain.class, NmsStack.class) == null,
                "a renamed factory was accepted");
        check(CraftItemStackMirror.resolve(NotStatic.class, NmsStack.class) == null,
                "an instance method was accepted as the factory");
        check(CraftItemStackMirror.resolve(WrongArgument.class, NmsStack.class) == null,
                "a factory taking another type was accepted");
        check(CraftItemStackMirror.resolve(WrongReturn.class, NmsStack.class) == null,
                "a factory returning another type was accepted");

        try {
            CraftItemStackMirror.require(RenamedAgain.class, NmsStack.class, "26.3-R0.1-SNAPSHOT");
            throw new AssertionError("require accepted a server with neither mirror factory");
        } catch (IllegalStateException expected) {
            String message = String.valueOf(expected.getMessage());
            check(message.contains("asBukkitMirror") && message.contains("asCraftMirror"),
                    "the failure does not name both factories: " + message);
            check(message.contains("26.3-R0.1-SNAPSHOT"),
                    "the failure does not name the server version: " + message);
        }

        // The null case normalises to an AIR stack, whose material key is minecraft:air. The empty
        // stack itself needs a running server, so the branch is driven through its factory seam; the
        // material the real ItemStack.empty() carries is pinned from Material.AIR.
        ItemStack air = CraftItemStackMirror.normalise(null, AirStandIn::new);
        check(air != null, "normalise returned null");
        check(air.getType() == Material.AIR, "normalise did not produce AIR");
        check(air.getType().getKey().toString().equals("minecraft:air"),
                "normalised material key is " + air.getType().getKey());
        check(Material.AIR.getKey().toString().equals("minecraft:air"),
                "Material.AIR no longer keys as minecraft:air");

        ItemStack present = new AirStandIn();
        boolean[] asked = {false};
        check(CraftItemStackMirror.normalise(present, () -> {
            asked[0] = true;
            return new AirStandIn();
        }) == present, "normalise replaced a non-null mirror");
        check(!asked[0], "normalise built an empty stack for a non-null mirror");

        System.out.println("CraftItemStackMirrorCheck: mirror resolution and empty-stack normalisation OK");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
