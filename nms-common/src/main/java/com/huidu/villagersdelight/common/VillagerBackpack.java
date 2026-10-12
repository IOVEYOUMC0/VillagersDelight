package com.huidu.villagersdelight.common;

import io.papermc.paper.adventure.PaperAdventure;
import net.kyori.adventure.text.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.bukkit.inventory.Inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** A native chest menu over the eight live villager slots, with one locked padding slot. */
public final class VillagerBackpack extends SimpleContainer {

    private final SimpleContainer inventory;
    private final ItemStack padding;
    private final BooleanSupplier accessible;

    public VillagerBackpack(SimpleContainer inventory, ItemStack padding, BooleanSupplier accessible) {
        super(9);
        if (inventory.getContainerSize() != 8) throw new IllegalArgumentException("Expected eight villager slots");
        this.inventory = inventory;
        this.padding = padding;
        this.accessible = accessible;
    }

    public Inventory open(ServerPlayer player, Component title) {
        var opened = player.openMenu(new SimpleMenuProvider((id, backpack, viewer) -> {
            ChestMenu menu = new ChestMenu(MenuType.GENERIC_9x1, id, backpack, this, 1) {
                @Override
                public boolean stillValid(Player viewer) {
                    return VillagerBackpack.this.stillValid(viewer);
                }
            };
            Slot locked = new Slot(this, 8, 152, 18) {
                @Override
                public boolean mayPlace(ItemStack stack) { return false; }

                @Override
                public boolean mayPickup(Player viewer) { return false; }

                @Override
                public int getMaxStackSize() { return 0; }
            };
            locked.index = 8;
            menu.slots.set(8, locked);
            return menu;
        }, PaperAdventure.asVanilla(title)));
        return opened.isPresent() ? player.containerMenu.getBukkitView().getTopInventory() : null;
    }

    @Override
    public ItemStack getItem(int slot) {
        if (!accessible.getAsBoolean()) return ItemStack.EMPTY;
        return slot == 8 ? padding : inventory.getItem(slot);
    }

    @Override
    public List<ItemStack> getContents() {
        List<ItemStack> contents = new ArrayList<>(9);
        for (int slot = 0; slot < 9; slot++) contents.add(getItem(slot));
        return contents;
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        return slot < 8 && accessible.getAsBoolean() ? inventory.removeItem(slot, amount) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return slot < 8 && accessible.getAsBoolean() ? inventory.removeItemNoUpdate(slot) : ItemStack.EMPTY;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot < 8 && accessible.getAsBoolean()) inventory.setItem(slot, stack);
    }

    @Override
    public void setChanged() {
        if (accessible.getAsBoolean()) inventory.setChanged();
    }

    @Override
    public boolean isEmpty() {
        return !accessible.getAsBoolean() || inventory.isEmpty();
    }

    @Override
    public boolean stillValid(Player player) {
        return accessible.getAsBoolean();
    }

    @Override
    public void clearContent() {
        if (accessible.getAsBoolean()) inventory.clearContent();
    }
}
