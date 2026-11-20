package io.github.opencubicchunks.cubicchunks.test.world.level.entity;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.Misc;
import io.github.opencubicchunks.cubicchunks.world.entity.EntityCubePosGetter;
import io.github.opencubicchunks.cubicchunks.world.level.entity.CubeEntityData;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Registry-wide storage smoke coverage, not a claim that each entity's AI or interactions are exercised. */
class TestCubeEntityRegistry extends BaseTest {
    @ParameterizedTest(name = "serializable entity registry at Y={0}")
    @ValueSource(ints = { 96, 32000, -32000, 32000000, -32000000 })
    void everySerializableEntityTypeKeepsIdentityAndFullHeightThroughCubeNbt(int y) {
        ServerLevel level = Misc.mockLevelWithPalettes(ServerLevel.class);
        Misc.setupLevelHeight(level, -64, 384);
        when(((CanBeCubic) level).cc_isCubic()).thenReturn(true);
        when(level.enabledFeatures()).thenReturn(FeatureFlags.DEFAULT_FLAGS);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        List<Executable> checks = new ArrayList<>();
        for (var type : BuiltInRegistries.ENTITY_TYPE) {
            if (!type.canSerialize()) {
                continue;
            }
            checks.add(() -> {
                String label = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
                Entity original = type.create(level, EntitySpawnReason.LOAD);
                assertNotNull(original, label);
                original.snapTo(31.5, y + 31.25, -0.5);
                original.setCustomName(Component.literal(label));
                original.setDeltaMovement(new Vec3(0.125, -0.25, 0.375));
                if (original instanceof ItemEntity item) {
                    item.setItem(new ItemStack(Items.DIAMOND, 7));
                }
                CubePos cube = CubePos.from(original.blockPosition());
                var saved = CubeEntityData.save(cube, List.of(original));
                var loaded = CubeEntityData.load(level, cube, saved);
                assertEquals(1, loaded.size(), label);
                Entity restored = loaded.getFirst();
                assertEquals(type, restored.getType(), label);
                assertEquals(original.getUUID(), restored.getUUID(), label);
                assertEquals(original.position(), restored.position(), label);
                assertEquals(original.getDeltaMovement(), restored.getDeltaMovement(), label);
                assertEquals(cube, ((EntityCubePosGetter) restored).cc_cubePosition(), label);
                assertEquals(label, restored.getCustomName().getString(), label);
                assertFalse(restored.isRemoved(), label);
                if (restored instanceof ItemEntity item) {
                    assertTrue(item.getItem().is(Items.DIAMOND), label);
                    assertEquals(7, item.getItem().getCount(), label);
                }
            });
        }
        assertTrue(checks.size() > 100, "The full serializable entity registry must be visited");
        assertAll("Entity registry at Y=" + y, checks);
        System.out.println("CC_ENTITY_REGISTRY_PASSED height=" + y + " serializableTypes=" + checks.size());
    }
}
