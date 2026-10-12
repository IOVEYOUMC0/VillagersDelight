package com.huidu.villagersdelight.impl26;

import com.google.common.collect.ImmutableList;
import com.mojang.datafixers.util.Pair;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.behavior.GateBehavior;
import net.minecraft.world.entity.ai.behavior.HarvestFarmland;
import net.minecraft.world.entity.ai.behavior.ShufflingList;
import net.minecraft.world.entity.ai.behavior.UseBonemeal;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.schedule.Activity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class VillagerBehaviorRestoreTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void removingTopLevelReplacementRestoresTheVanillaActivityEntry() throws Exception {
        Brain<Villager> brain = new Brain<>();
        VillagerFarmBehavior replacement = new VillagerFarmBehavior();
        brain.addActivity(Activity.WORK, ImmutableList.of(Pair.of(5, replacement)), Set.of(), Set.of());
        NmsVillagerAi injector = new NmsVillagerAi();
        Method remove = NmsVillagerAi.class.getDeclaredMethod("removeBehavior", Brain.class, Class.class);
        remove.setAccessible(true);
        assertEquals(1, remove.invoke(injector, brain, VillagerFarmBehavior.class));
        var field = Brain.class.getDeclaredField("availableBehaviorsByPriority");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var priorities = (Map<Integer, Map<Activity, Set<BehaviorControl<?>>>>) field.get(brain);
        var restored = priorities.get(5).get(Activity.WORK);
        assertEquals(1, restored.size());
        assertEquals(HarvestFarmland.class, restored.iterator().next().getClass());
        assertEquals(0, remove.invoke(injector, brain, VillagerFarmBehavior.class));
    }

    @Test
    void removingNestedReplacementKeepsTheGateWeightAndOtherEntry() throws Exception {
        UseBonemeal untouched = new UseBonemeal();
        GateBehavior<Villager> gate = new GateBehavior<>(Map.of(), Set.of(),
                GateBehavior.OrderPolicy.ORDERED, GateBehavior.RunningPolicy.RUN_ONE,
                List.of(Pair.of(new VillagerFarmBehavior(), 7), Pair.of(untouched, 3)));
        Method remove = NmsVillagerAi.class.getDeclaredMethod("removeNested", GateBehavior.class, Class.class);
        remove.setAccessible(true);
        assertEquals(1, remove.invoke(new NmsVillagerAi(), gate, VillagerFarmBehavior.class));
        var field = GateBehavior.class.getDeclaredField("behaviors");
        field.setAccessible(true);
        var entries = ShufflingList.class.getDeclaredField("entries");
        entries.setAccessible(true);
        var restored = (List<?>) entries.get(field.get(gate));
        assertEquals(2, restored.size());
        var first = (ShufflingList.WeightedEntry<?>) restored.getFirst();
        assertEquals(7, first.getWeight());
        assertEquals(HarvestFarmland.class, first.getData().getClass());
        var second = (ShufflingList.WeightedEntry<?>) restored.get(1);
        assertSame(untouched, second.getData());
        assertEquals(3, second.getWeight());
    }
}
