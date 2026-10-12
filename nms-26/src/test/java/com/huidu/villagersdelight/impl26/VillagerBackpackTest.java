package com.huidu.villagersdelight.impl26;

import com.huidu.villagersdelight.impl26.common.VillagerBackpack;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Item;

import java.util.List;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

class VillagerBackpackTest {
    @Test
    void editsAndAiChangesShareLiveSlotsAndUnownedAccessCannotMutateThem() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // The server normally binds these components when loading registries.
        for (Item item : List.of(Items.BARRIER, Items.CARROT, Items.POTATO, Items.WHEAT_SEEDS, Items.DIAMOND)) {
            ((Holder.Reference<Item>) BuiltInRegistries.ITEM.wrapAsHolder(item)).bindComponents(
                    DataComponentMap.builder().set(DataComponents.MAX_STACK_SIZE, 64).build());
        }
        SimpleContainer villager = new SimpleContainer(8);
        AtomicBoolean accessible = new AtomicBoolean(true);
        ItemStack padding = new ItemStack(Items.BARRIER);
        VillagerBackpack view = new VillagerBackpack(villager, padding, accessible::get);
        villager.setItem(0, new ItemStack(Items.CARROT, 12));
        assert view.getContainerSize() == 9;
        assert view.getItem(0) == villager.getItem(0);
        assert view.removeItem(0, 5).getCount() == 5;
        assert villager.getItem(0).getCount() == 7;
        view.setItem(1, new ItemStack(Items.POTATO, 3));
        assert villager.getItem(1).getCount() == 3;
        // AI changes are visible immediately; closing has no stale copy to write back.
        villager.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 2));
        assert view.getContents().getFirst().is(Items.WHEAT_SEEDS);
        assert view.getContents().getFirst().getCount() == 2;
        assert view.removeItem(8, 1).isEmpty();
        assert view.removeItemNoUpdate(8).isEmpty();
        view.setItem(8, new ItemStack(Items.DIAMOND));
        assert view.getItem(8) == padding;
        accessible.set(false);
        assert !view.stillValid(null);
        assert view.getItem(0).isEmpty();
        assert view.removeItem(0, 64).isEmpty();
        assert view.removeItemNoUpdate(1).isEmpty();
        view.setItem(0, new ItemStack(Items.DIAMOND));
        view.clearContent();
        assert villager.getItem(0).is(Items.WHEAT_SEEDS);
        assert villager.getItem(0).getCount() == 2;
        assert villager.getItem(1).getCount() == 3;
        accessible.set(true);
        assert view.removeItemNoUpdate(1).getCount() == 3;
        assert villager.getItem(1).isEmpty();
        System.out.println("Live villager backpack transactions, AI updates, padding and retired ownership passed.");
    }
}
