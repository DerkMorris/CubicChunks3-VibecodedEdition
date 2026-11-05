package io.github.opencubicchunks.cubicchunks.test.world.level.entity;

import static io.github.opencubicchunks.cubicchunks.testutils.Misc.mockLevelWithPalettes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.server.level.CubicServerLevel;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.Misc;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.github.opencubicchunks.cubicchunks.world.level.entity.CubeEntityGetter;
import io.github.opencubicchunks.cubicchunks.world.level.entity.CubicEntityManager;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Continuation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityPersistentStorage;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.entity.LevelCallback;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.entity.TransientEntitySectionManager;
import net.minecraft.world.level.entity.Visibility;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestCubeEntityVisibility extends BaseTest {
    @TempDir
    Path directory;

    @Test
    void cubeStatusControlsIdUuidEnumerationAndTrackingExactlyOnce() throws Exception {
        try (Fixture f = open()) {
            Entity pig = f.pig(at(32000000));
            assertNull(f.getter().get(pig.getId()));
            assertTrue(f.manager.isLoaded(pig.getUUID()));
            f.status(pig.position(), FullChunkStatus.FULL);
            assertSame(pig, f.getter().get(pig.getId()));
            assertSame(pig, f.getter().get(pig.getUUID()));
            assertEquals(List.of(pig), f.query(box(pig.position())));
            f.status(pig.position(), FullChunkStatus.ENTITY_TICKING);
            verify(f.callbacks).onCreated(pig);
            verify(f.callbacks).onTrackingStart(pig);
            verify(f.callbacks).onTickingStart(pig);
            f.status(pig.position(), FullChunkStatus.INACCESSIBLE);
            f.status(pig.position(), FullChunkStatus.INACCESSIBLE);
            assertNull(f.getter().get(pig.getUUID()));
            assertEquals(0, f.manager.count());
            assertTrue(f.manager.isLoaded(pig.getUUID()));
            verify(f.callbacks).onTrackingEnd(pig);
            verify(f.callbacks).onTickingEnd(pig);
            f.status(pig.position(), FullChunkStatus.FULL);
            assertEquals(1, f.manager.count());
            verify(f.callbacks, times(2)).onTrackingStart(pig);
        }
    }

    @Test
    void aliasedSectionsAtDifferentHeightsHaveIndependentVisibility() throws Exception {
        try (Fixture f = open()) {
            Vec3 high = at(32000000);
            Vec3 low = high.add(0, -(1 << 24), 0);
            Entity first = f.pig(high);
            Entity second = f.pig(low);
            assertEquals(SectionPos.asLong(first.blockPosition()), SectionPos.asLong(second.blockPosition()));
            f.status(high, FullChunkStatus.FULL);
            assertEquals(List.of(first), f.query(box(high)));
            assertTrue(f.query(box(low)).isEmpty());
            assertNull(f.getter().get(second.getUUID()));
            f.status(low, FullChunkStatus.FULL);
            assertEquals(List.of(second), f.query(box(low)));
            f.status(high, FullChunkStatus.INACCESSIBLE);
            assertEquals(List.of(second), f.query(box(low)));
            assertEquals(1, f.manager.count());
        }
    }

    @Test
    void movementAcrossPackedAliasesUpdatesVisibilityAndFullSectionCallbacks() throws Exception {
        try (Fixture f = open()) {
            Vec3 high = at(32000000);
            Vec3 alias = high.add(0, -(1 << 24), 0);
            f.status(high, FullChunkStatus.FULL);
            Entity pig = f.pig(high);
            clearInvocations(f.callbacks);
            pig.snapTo(alias);
            assertTrue(f.query(box(high)).isEmpty());
            assertNull(f.getter().get(pig.getId()));
            verify(f.callbacks).onTrackingEnd(pig);
            f.status(alias, FullChunkStatus.FULL);
            pig.snapTo(high);
            assertEquals(List.of(pig), f.query(box(high)));
            verify(f.callbacks).onSectionChange(pig);
            verify(f.callbacks).onTrackingStart(pig);
        }
    }

    @Test
    void withinSectionMotionDoesNotDuplicateCallbacksButQueriesUseCurrentBounds() throws Exception {
        try (Fixture f = open()) {
            Vec3 pos = at(-32000000);
            f.status(pos, FullChunkStatus.FULL);
            Entity pig = f.pig(pos);
            clearInvocations(f.callbacks);
            pig.snapTo(pos.add(5, 0, 0));
            assertTrue(f.query(box(pos)).isEmpty());
            assertEquals(List.of(pig), f.query(box(pig.position())));
            verify(f.callbacks, never()).onSectionChange(any());
            pig.snapTo(pos.add(0, 16, 0));
            verify(f.callbacks).onSectionChange(pig);
            verify(f.callbacks, never()).onTrackingStart(any());
            verify(f.callbacks, never()).onTrackingEnd(any());
        }
    }

    @Test
    void removalClearsVisibleLookupAndHiddenResidencyWithoutDuplicateNotifications() throws Exception {
        try (Fixture f = open()) {
            Vec3 pos = at(-32000000);
            f.status(pos, FullChunkStatus.FULL);
            Entity pig = f.pig(pos);
            Entity hidden = f.pig(pos.add(0, 64, 0));
            pig.discard();
            hidden.discard();
            f.status(pos, FullChunkStatus.FULL);
            assertEquals(0, f.manager.count());
            assertFalse(f.manager.isLoaded(pig.getUUID()));
            assertFalse(f.manager.isLoaded(hidden.getUUID()));
            verify(f.callbacks).onTrackingEnd(pig);
            verify(f.callbacks, never()).onTrackingEnd(hidden);
            verify(f.callbacks).onDestroyed(pig);
            verify(f.callbacks).onDestroyed(hidden);
        }
    }

    @Test
    void nativeLevelPredicatesTypeFiltersAndResultLimitsUseTheCubicGetter() throws Exception {
        try (Fixture f = open()) {
            Vec3 pos = at(32000000);
            f.status(pos, FullChunkStatus.FULL);
            Entity first = f.pig(pos);
            Entity second = f.pig(pos.add(1, 0, 0));
            AABB area = box(pos).inflate(4);
            assertEquals(List.of(second), f.level.getEntities(first, area, entity -> true));
            assertTrue(f.level.getEntities((Entity) null, area, entity -> false).isEmpty());
            List<Entity> limited = new ArrayList<>();
            f.level.getEntities(EntityTypes.PIG, area, entity -> true, limited, 1);
            assertEquals(1, limited.size());
            assertTrue(f.level.getEntities(EntityTypes.ARMOR_STAND, area, entity -> true).isEmpty());
            assertSame(first, f.level.getEntity(first.getId()));
        }
    }

    @Test
    void fullBoundsAndVeryLargeQueriesDoNotWrapOrVisitWorldCubes() throws Exception {
        try (Fixture f = open()) {
            Vec3 lower = new Vec3(CubicBlockBounds.MIN + 0.5, CubicBlockBounds.MIN + 0.5, CubicBlockBounds.MIN + 0.5);
            Vec3 upper = new Vec3(CubicBlockBounds.MAX + 0.5, CubicBlockBounds.MAX + 0.5, CubicBlockBounds.MAX + 0.5);
            f.status(lower, FullChunkStatus.FULL);
            f.status(upper, FullChunkStatus.FULL);
            Entity first = f.pig(lower);
            Entity last = f.pig(upper);
            assertEquals(List.of(first), f.query(box(lower)));
            assertEquals(List.of(last), f.query(box(upper)));
            assertEquals(2, f.query(new AABB(-1.0E100, -1.0E100, -1.0E100, 1.0E100, 1.0E100, 1.0E100)).size());
            verify(f.level, never()).getChunkSource();
        }
    }

    @Test
    void oversizedAndResizedHitboxesAreFoundOutsideTheirOriginCube() throws Exception {
        try (Fixture f = open()) {
            Vec3 pos = at(32000000);
            f.status(pos, FullChunkStatus.FULL);
            Entity pig = f.pig(pos);
            AABB far = box(pos.add(0, 100, 0));
            assertTrue(f.query(far).isEmpty());
            pig.setBoundingBox(box(pos).inflate(128));
            assertEquals(List.of(pig), f.query(far));
            List<Entity> typed = new ArrayList<>();
            f.getter().get(EntityTypes.PIG, far, entity -> {
                typed.add(entity);
                return Continuation.CONTINUE;
            });
            assertEquals(List.of(pig), typed);
            f.status(pos, FullChunkStatus.INACCESSIBLE);
            assertTrue(f.query(far).isEmpty());
        }
    }

    @Test
    void queryCallbacksMayRemoveEntitiesAndAbortWithoutStaleResults() throws Exception {
        try (Fixture f = open()) {
            Vec3 pos = at(32000000);
            f.status(pos, FullChunkStatus.FULL);
            Entity first = f.pig(pos);
            Entity second = f.pig(pos);
            List<Entity> result = new ArrayList<>();
            f.getter().get(box(pos), entity -> {
                result.add(entity);
                second.discard();
            });
            assertEquals(List.of(first), result);
            f.pig(pos);
            result.clear();
            f.getter().get(EntityTypes.PIG, box(pos), entity -> {
                result.add(entity);
                return Continuation.ABORT;
            });
            assertEquals(1, result.size());
        }
    }

    @Test
    void rejectedDuplicateUuidDoesNotCreateVisibilityEntry() throws Exception {
        try (Fixture f = open()) {
            Vec3 pos = at(32000000);
            f.status(pos, FullChunkStatus.FULL);
            Entity first = f.pig(pos);
            Entity duplicate = f.create(pos);
            duplicate.setUUID(first.getUUID());
            assertFalse(f.manager.addNewEntity(duplicate));
            f.status(pos, FullChunkStatus.INACCESSIBLE);
            f.status(pos, FullChunkStatus.FULL);
            assertEquals(List.of(first), f.query(box(pos)));
            verify(f.callbacks, never()).onTrackingStart(duplicate);
        }
    }

    @Test
    void alwaysTickingEntitiesKeepNativeTrackingAndGetAliasMovementNotifications() throws Exception {
        try (Fixture f = open()) {
            Entity always = spy(f.create(at(32000000)));
            doReturn(true).when(always).isAlwaysTicking();
            assertTrue(f.manager.addNewEntity(always));
            assertSame(always, f.getter().get(always.getId()));
            f.status(always.position(), FullChunkStatus.FULL);
            f.status(always.position(), FullChunkStatus.INACCESSIBLE);
            verify(f.callbacks).onTrackingStart(always);
            verify(f.callbacks).onTickingStart(always);
            always.snapTo(always.position().add(0, -(1 << 24), 0));
            verify(f.callbacks).onSectionChange(always);
            always.discard();
            verify(f.callbacks).onTrackingEnd(always);
            verify(f.callbacks).onTickingEnd(always);
        }
    }

    @Test
    void diskLoadedEntitiesBecomeVisibleOnlyWithAccessibleCubeAndUnloadCleanly() throws Exception {
        Vec3 pos = at(-32000000);
        UUID id;
        try (Fixture f = open()) {
            id = f.pig(pos).getUUID();
        }
        try (Fixture f = open()) {
            ((CubicEntityManager) f.manager).cc_entityPersistence().load(cube(pos));
            assertTrue(f.manager.isLoaded(id));
            assertNull(f.getter().get(id));
            f.status(pos, FullChunkStatus.FULL);
            Entity loaded = f.getter().get(id);
            assertNotNull(loaded);
            verify(f.callbacks, never()).onCreated(any());
            ((CubicServerLevel) f.level).cc_unloadCubeEntities(cube(pos));
            assertNull(f.getter().get(id));
            assertFalse(f.manager.isLoaded(id));
            verify(f.callbacks).onTrackingEnd(loaded);
            f.status(pos, FullChunkStatus.FULL);
            assertNotNull(f.getter().get(id));
        }
    }

    @Test
    void ordinaryServerLevelRetainsNativeVisibilityAndLookup() throws Exception {
        try (Fixture f = open()) {
            when(((CanBeCubic) f.level).cc_isCubic()).thenReturn(false);
            when(f.storage.loadEntities(any())).thenAnswer(i -> java.util.concurrent.CompletableFuture
                    .completedFuture(new net.minecraft.world.level.entity.ChunkEntities<Entity>(i.getArgument(0), List.of())));
            Entity pig = f.pig(at(96));
            f.manager.updateChunkStatus(pig.chunkPosition(), Visibility.TRACKED);
            f.manager.processPendingLoads();
            assertFalse(f.getter() instanceof CubeEntityGetter);
            assertSame(pig, f.getter().get(pig.getUUID()));
            assertEquals(List.of(pig), f.query(box(pig.position())));
            f.manager.updateChunkStatus(pig.chunkPosition(), Visibility.HIDDEN);
            assertNull(f.getter().get(pig.getUUID()));
        }
    }

    @Test
    void clientQueriesUseFullBoundsAndHonorRemovalAndIdReplacement() throws Exception {
        ClientLevel level = mockLevelWithPalettes(ClientLevel.class);
        setField(Level.class, level, "cc_isCubic", true);
        when(((CanBeCubic) level).cc_isCubic()).thenReturn(true);
        Misc.setupLevelHeight(level, -64, 384);
        when(level.enabledFeatures()).thenReturn(FeatureFlags.DEFAULT_FLAGS);
        int[] id = { 0 };
        when(level.getNextEntityId()).thenAnswer(i -> ++id[0]);
        var manager = new TransientEntitySectionManager<Entity>(Entity.class, mock());
        setField(ClientLevel.class, level, "entityStorage", manager);
        realGetter(level, ClientLevel.class);
        doCallRealMethod().when(level).addEntity(any());
        doCallRealMethod().when(level).removeEntity(anyInt(), any());
        doCallRealMethod().when(level).getEntity(anyInt());
        var method = ClientLevel.class.getDeclaredMethod("getEntities");
        method.setAccessible(true);
        var getter = (LevelEntityGetter<Entity>) method.invoke(level);
        assertInstanceOf(CubeEntityGetter.class, getter);
        Entity pig = EntityTypes.PIG.create(level, EntitySpawnReason.LOAD);
        assertNotNull(pig);
        pig.snapTo(at(-32000000));
        level.addEntity(pig);
        List<Entity> result = new ArrayList<>();
        getter.get(box(pig.position()), result::add);
        assertEquals(List.of(pig), result);
        pig.snapTo(at(32000000));
        result.clear();
        getter.get(box(at(-32000000)), result::add);
        assertTrue(result.isEmpty());
        level.removeEntity(pig.getId(), Entity.RemovalReason.DISCARDED);
        assertNull(getter.get(pig.getId()));
        assertNull(getter.get(pig.getUUID()));
        setField(Level.class, level, "cc_isCubic", false);
        assertSame(manager.getEntityGetter(), method.invoke(level));
    }

    private static Vec3 at(int y) {
        return new Vec3(2.5, y + 2.5, 2.5);
    }

    private static CubePos cube(Vec3 pos) {
        return CubePos.from(net.minecraft.core.BlockPos.containing(pos));
    }

    private static AABB box(Vec3 pos) {
        return new AABB(pos.add(-1, -1, -1), pos.add(1, 2, 1));
    }

    private static void setField(Class<?> type, Object object, String name, Object value) throws Exception {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    private static void realGetter(Level level, Class<?> type) throws Exception {
        var method = type.getDeclaredMethod("getEntities");
        method.setAccessible(true);
        doCallRealMethod().when(level);
        method.invoke(level);
    }

    private Fixture open() throws Exception {
        return new Fixture(directory);
    }

    private static final class Fixture implements AutoCloseable {
        private final ServerLevel level = mockLevelWithPalettes(ServerLevel.class);
        private final LevelCallback<Entity> callbacks = mock();
        private final EntityPersistentStorage<Entity> storage = mock();
        private final PersistentEntitySectionManager<Entity> manager = new PersistentEntitySectionManager<>(Entity.class, callbacks, storage);
        private int id;

        private Fixture(Path path) throws Exception {
            Misc.setupLevelHeight(level, -64, 384);
            when(((CanBeCubic) level).cc_isCubic()).thenReturn(true);
            when(level.enabledFeatures()).thenReturn(FeatureFlags.DEFAULT_FLAGS);
            when(level.getNextEntityId()).thenAnswer(i -> ++id);
            when(level.dimension()).thenReturn(Level.OVERWORLD);
            setField(ServerLevel.class, level, "entityManager", manager);
            ((CubicEntityManager) manager).cc_configureEntityStorage(level, path);
            CubicServerLevel bridge = (CubicServerLevel) level;
            doCallRealMethod().when(bridge).cc_onCubeEntityStatus(any(), any());
            doCallRealMethod().when(bridge).cc_unloadCubeEntities(any());
            realGetter(level, ServerLevel.class);
            doCallRealMethod().when(level).getEntity(anyInt());
            doCallRealMethod().when(level).getEntities(org.mockito.ArgumentMatchers.nullable(Entity.class), any(AABB.class), any());
            doCallRealMethod().when(level).getEntities(any(EntityTypeTest.class), any(AABB.class), any());
            doCallRealMethod().when(level).getEntities(any(EntityTypeTest.class), any(), any(), any());
            doCallRealMethod().when(level).getEntities(any(EntityTypeTest.class), any(), any(), any(), anyInt());
            when(level.dragonParts()).thenReturn(List.of());
        }

        private Entity create(Vec3 pos) {
            Entity entity = EntityTypes.PIG.create(level, EntitySpawnReason.LOAD);
            assertNotNull(entity);
            entity.snapTo(pos);
            return entity;
        }

        private Entity pig(Vec3 pos) {
            Entity entity = create(pos);
            assertTrue(manager.addNewEntity(entity));
            return entity;
        }

        private void status(Vec3 pos, FullChunkStatus status) {
            ((CubicServerLevel) level).cc_onCubeEntityStatus(cube(pos), status);
        }

        private LevelEntityGetter<Entity> getter() {
            return manager.getEntityGetter();
        }

        private List<Entity> query(AABB box) {
            List<Entity> result = new ArrayList<>();
            getter().get(box, result::add);
            return result;
        }

        @Override public void close() throws IOException {
            manager.close();
        }
    }
}
