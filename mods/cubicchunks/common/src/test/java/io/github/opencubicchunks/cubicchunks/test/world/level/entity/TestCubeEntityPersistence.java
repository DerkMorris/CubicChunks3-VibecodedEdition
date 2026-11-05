package io.github.opencubicchunks.cubicchunks.test.world.level.entity;

import static io.github.opencubicchunks.cubicchunks.testutils.Misc.mockLevelWithPalettes;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.mixin.access.common.EntityManagerAccess;
import io.github.opencubicchunks.cubicchunks.mixin.access.common.ServerLevelEntityAccess;
import io.github.opencubicchunks.cubicchunks.server.level.CubicServerLevel;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.CubeStorageBarrier;
import io.github.opencubicchunks.cubicchunks.testutils.Misc;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.github.opencubicchunks.cubicchunks.world.level.cube.storage.CubeStorage;
import io.github.opencubicchunks.cubicchunks.world.level.entity.CubeEntityPersistence;
import io.github.opencubicchunks.cubicchunks.world.level.entity.CubicEntityManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.painting.Painting;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityPersistentStorage;
import net.minecraft.world.level.entity.LevelCallback;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.entity.Visibility;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real files, entity NBT, UUID registry and native movement/removal callbacks; world services are mocked. */
class TestCubeEntityPersistence extends BaseTest {
    @TempDir
    Path directory;

    @Test
    void autosaveAndUnloadKeepEntitiesResidentWhileDiskIsBusy() throws Exception {
        Vec3 from = new Vec3(2.5, 32000031.5, 2.5);
        Vec3 to = from.add(0, 64, 0);
        UUID id;
        try (Fixture f = open()) {
            Entity pig = f.entity(EntityTypes.PIG, from);
            id = pig.getUUID();
            f.load(from);
            f.load(to);
            CubeStorage storage = CubeStorageBarrier.storage(CubeEntityPersistence.class, f.cubes, "storage");
            try (var barrier = new CubeStorageBarrier(storage)) {
                assertTimeout(Duration.ofSeconds(1), () -> f.manager.autoSave());
                assertTimeout(Duration.ofSeconds(1), () -> assertFalse(f.cubes.tryUnload(cube(from))));
                assertTrue(f.manager.isLoaded(id));
                assertFalse(pig.isRemoved());
                pig.snapTo(to);
                pig.setCustomName(Component.literal("Changed during async save"));
            }
            f.manager.saveAll();
            assertTrue(f.cubes.tryUnload(cube(from)));
            assertFalse(pig.isRemoved());
        }
        try (Fixture f = open()) {
            f.load(from);
            f.load(to);
            assertEquals(1, f.all().size());
            assertEquals(to, f.byId(id).position());
            assertEquals("Changed during async save", f.byId(id).getCustomName().getString());
        }
    }

    @Test
    void unloadingEmptySpawnAreaDoesNotRewriteEmptyEntityFiles() throws Exception {
        try (Fixture fixture = open()) {
            for (int y = -3; y <= 3; y++) {
                fixture.cubes.load(CubePos.of(0, y, 0));
            }
            fixture.cubes.saveAll();
            for (int y = -3; y <= 3; y++) {
                fixture.cubes.unload(CubePos.of(0, y, 0));
                assertFalse(Files.exists(file(CubePos.of(0, y, 0))));
            }
        }
        try (Fixture reopened = open()) {
            reopened.cubes.load(CubePos.of(0, 0, 0));
            assertTrue(reopened.all().isEmpty());
        }
    }

    @Test
    void restartsPreserveStateUuidsAndDistinctPositiveNegativeHeights() throws Exception {
        Vec3 above = new Vec3(-32000000.25, 32000000.75, 32000000.5);
        Vec3 below = new Vec3(-32000000.25, -32000000.25, 32000000.5);
        UUID pigId;
        UUID standId;
        try (Fixture first = open()) {
            LivingEntity pig = first.entity(EntityTypes.PIG, above);
            pigId = pig.getUUID();
            pig.setCustomName(Component.literal("Высокая свинья"));
            pig.setHealth(4);
            pig.setDeltaMovement(0.1, -0.2, 0.3);
            LivingEntity stand = first.entity(EntityTypes.ARMOR_STAND, below);
            standId = stand.getUUID();
            stand.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
            first.manager.autoSave();
            verify(first.vanilla, never()).storeEntities(any());
        }
        try (Fixture second = open()) {
            second.load(above);
            second.load(below);
            LivingEntity pig = (LivingEntity) second.byId(pigId);
            LivingEntity stand = (LivingEntity) second.byId(standId);
            assertEquals(above, pig.position());
            assertEquals(below, stand.position());
            assertEquals(4, pig.getHealth());
            assertEquals("Высокая свинья", pig.getCustomName().getString());
            assertEquals(new Vec3(0.1, -0.2, 0.3), pig.getDeltaMovement());
            assertTrue(stand.getItemBySlot(EquipmentSlot.HEAD).is(Items.DIAMOND_HELMET));
            verify(second.callbacks, never()).onCreated(any());
            second.load(above);
            assertEquals(2, second.all().size());
        }
    }

    @Test
    void unloadedCubeReleasesUuidsOnlyAfterSavingAndCanBeReloaded() throws Exception {
        Vec3 at = new Vec3(2.5, 32000031.5, 2.5);
        try (Fixture f = open()) {
            Entity original = f.entity(EntityTypes.PIG, at);
            UUID id = original.getUUID();
            f.cubes.unload(cube(at));
            assertTrue(original.isRemoved());
            assertFalse(f.manager.isLoaded(id));
            assertFalse(f.cubes.isLoaded(cube(at)));
            f.load(at);
            assertEquals(at, f.byId(id).position());
            assertFalse(f.byId(id).isRemoved());
            assertEquals(1, f.all().size());
        }
    }

    @Test
    void packedSectionAliasesMoveAndSaveToDifferentCubeFiles() throws Exception {
        Vec3 from = new Vec3(7.5, 32000000.25, -7.5);
        Vec3 to = from.add(0, -(1 << 24), 0);
        assertEquals(SectionPos.asLong(BlockPos.containing(from)), SectionPos.asLong(BlockPos.containing(to)));
        UUID moved;
        UUID stayed;
        try (Fixture f = open()) {
            Entity first = f.entity(EntityTypes.PIG, from);
            moved = first.getUUID();
            stayed = f.entity(EntityTypes.PIG, from.add(1, 0, 0)).getUUID();
            f.manager.saveAll();
            first.snapTo(to);
            f.cubes.unload(cube(from));
            assertFalse(first.isRemoved());
            assertTrue(f.manager.isLoaded(moved));
            assertFalse(f.manager.isLoaded(stayed));
        }
        try (Fixture f = open()) {
            f.load(from);
            f.load(to);
            assertEquals(to, f.byId(moved).position());
            assertEquals(2, f.all().size());
        }
    }

    @Test
    void movingTheLastRootClearsItsOldFileAndRemovalDoesNotResurrect() throws Exception {
        Vec3 from = new Vec3(-1.25, -32000001.25, -1.25);
        Vec3 to = from.add(64, 65, 64);
        UUID id;
        try (Fixture f = open()) {
            Entity entity = f.entity(EntityTypes.PIG, from);
            id = entity.getUUID();
            f.manager.saveAll();
            entity.snapTo(to);
            f.manager.autoSave();
        }
        try (Fixture f = open()) {
            f.load(from);
            assertTrue(f.all().isEmpty());
            f.load(to);
            f.byId(id).discard();
        }
        try (Fixture f = open()) {
            f.load(from);
            f.load(to);
            assertTrue(f.all().isEmpty());
        }
    }

    @Test
    void passengerTreesCrossCubeBoundariesAndUnloadAsOneTree() throws Exception {
        Vec3 at = new Vec3(3, 32000031.75, 3);
        Vec3 passengerAt = at.add(0, 1.25, 0);
        UUID rootId;
        UUID childId;
        try (Fixture f = open()) {
            Entity root = f.entity(EntityTypes.PIG, at);
            Entity child = f.entity(EntityTypes.PIG, passengerAt);
            rootId = root.getUUID();
            childId = child.getUUID();
            assertTrue(child.startRiding(root, true, false));
            f.manager.saveAll();
            f.cubes.unload(cube(passengerAt));
            assertFalse(root.isRemoved());
            assertFalse(child.isRemoved());
            f.cubes.unload(cube(at));
            assertTrue(f.all().isEmpty());
            f.load(at);
            assertEquals(2, f.all().size());
            assertSame(f.byId(rootId), f.byId(childId).getVehicle());
        }
        try (Fixture f = open()) {
            f.load(passengerAt);
            assertTrue(f.all().isEmpty());
            f.load(at);
            assertSame(f.byId(rootId), f.byId(childId).getVehicle());
            assertEquals(passengerAt, f.byId(childId).position());
        }
    }

    @Test
    void saveMergesPreviouslyUnrequestedDiskEntitiesBeforeWriting() throws Exception {
        Vec3 at = new Vec3(3.5, -32000000.5, 3.5);
        UUID old;
        try (Fixture f = open()) {
            old = f.entity(EntityTypes.PIG, at).getUUID();
        }
        UUID fresh;
        try (Fixture f = open()) {
            fresh = f.entity(EntityTypes.PIG, at.add(1, 0, 0)).getUUID();
            f.manager.autoSave();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (f.all().size() != 2 && System.nanoTime() < deadline) {
                f.manager.tick();
                java.util.concurrent.locks.LockSupport.parkNanos(1000000);
            }
            assertEquals(2, f.all().size());
            assertNotNull(f.byId(old));
        }
        try (Fixture f = open()) {
            f.load(at);
            assertNotNull(f.byId(old));
            assertNotNull(f.byId(fresh));
        }
    }

    @Test
    void corruptedReadPreventsSavingAndDoesNotOverwriteOldBytes() throws Exception {
        Vec3 at = new Vec3(0, 32000000, 0);
        try (Fixture f = open()) {
            f.entity(EntityTypes.PIG, at);
        }
        Path file = file(cube(at));
        byte[] corrupt = new byte[] { 0, 2, 3, 4 };
        Files.write(file, corrupt);
        Fixture f = open();
        f.entity(EntityTypes.PIG, at.add(1, 0, 0));
        assertThrows(RuntimeException.class, f.manager::saveAll);
        assertThrows(IOException.class, f::close);
        assertArrayEquals(corrupt, Files.readAllBytes(file));
        try (CubeStorage unlocked = new CubeStorage(directory)) {
            assertNotNull(unlocked);
        }
    }

    @Test
    void invalidRootOrPassengerRejectsTheWholeFileBeforeRegistration() throws Exception {
        assertInvalidFile(tag -> tag.getListOrEmpty("Entities").getCompoundOrEmpty(1).putString("id", "missing:mob"));
    }

    @Test
    void unknownPassengerTypeDoesNotPartiallyRestoreItsVehicle() throws Exception {
        assertInvalidFile(tag -> {
            CompoundTag root = tag.getListOrEmpty("Entities").getCompoundOrEmpty(0);
            CompoundTag child = root.copy();
            child.store("UUID", UUIDUtil.CODEC, UUID.randomUUID());
            child.putString("id", "missing:passenger");
            ListTag passengers = new ListTag();
            passengers.add(child);
            root.put("Passengers", passengers);
        });
    }

    @Test
    void playersRemainInMemoryAndAreNeverWrittenOrUnloadedWithTheirCube() throws Exception {
        Vec3 at = new Vec3(0, 32000000, 0);
        try (Fixture f = open()) {
            Player player = mock();
            when(player.getUUID()).thenReturn(UUID.randomUUID());
            when(player.blockPosition()).thenReturn(BlockPos.containing(at));
            when(player.level()).thenReturn(f.level);
            assertTrue(f.manager.addNewEntity(player));
            f.entity(EntityTypes.PIG, at);
            f.cubes.unload(cube(at));
            assertEquals(List.of(player), f.all());
            verify(player, never()).saveAsPassenger(any());
            verify(player, never()).setRemoved(any());
        }
        try (Fixture f = open()) {
            f.load(at);
            assertEquals(1, f.all().size());
            assertEquals(EntityTypes.PIG, f.all().getFirst().getType());
        }
    }

    @Test
    void boardingAndDismountingAcrossCubesDoNotDuplicatePassengers() throws Exception {
        Vec3 vehicleAt = new Vec3(1, -32000001, 1);
        Vec3 childAt = vehicleAt.add(0, 32, 0);
        UUID rootId;
        UUID childId;
        try (Fixture f = open()) {
            Entity root = f.entity(EntityTypes.PIG, vehicleAt);
            Entity child = f.entity(EntityTypes.PIG, childAt);
            rootId = root.getUUID();
            childId = child.getUUID();
            f.manager.saveAll();
            assertTrue(child.startRiding(root, true, false));
        }
        try (Fixture f = open()) {
            f.load(childAt);
            assertTrue(f.all().isEmpty());
            f.load(vehicleAt);
            assertSame(f.byId(rootId), f.byId(childId).getVehicle());
            f.byId(childId).stopRiding();
        }
        try (Fixture f = open()) {
            f.load(childAt);
            f.load(vehicleAt);
            assertEquals(2, f.all().size());
            assertFalse(f.byId(childId).isPassenger());
        }
    }

    @Test
    void duplicateUuidsInFileAreRejected() throws Exception {
        assertInvalidFile(tag -> {
            ListTag list = tag.getListOrEmpty("Entities");
            list.getCompoundOrEmpty(1).put("UUID", list.getCompoundOrEmpty(0).get("UUID").copy());
        });
    }

    @Test
    void wrongFileCoordinatesAreRejected() throws Exception {
        assertInvalidFile(tag -> tag.putIntArray("Position", new int[] { 0, 0, 0 }));
    }

    @Test
    void unknownFormatOrDataVersionIsNotSilentlyRegenerated() throws Exception {
        assertInvalidFile(tag -> tag.putInt("DataVersion", Integer.MAX_VALUE));
    }

    @Test
    void invalidPassengerCoordinatesAreRejected() throws Exception {
        assertInvalidFile(tag -> {
            CompoundTag root = tag.getListOrEmpty("Entities").getCompoundOrEmpty(0);
            CompoundTag child = root.copy();
            child.store("UUID", UUIDUtil.CODEC, UUID.randomUUID());
            child.store("Pos", Vec3.CODEC, new Vec3(0, CubicBlockBounds.MAX + 1.0, 0));
            ListTag passengers = new ListTag();
            passengers.add(child);
            root.put("Passengers", passengers);
        });
    }

    @Test
    void residentUuidConflictDoesNotReplaceEitherEntityOrFile() throws Exception {
        Vec3 at = new Vec3(0, 32000000, 0);
        UUID id;
        try (Fixture f = open()) {
            id = f.entity(EntityTypes.PIG, at).getUUID();
        }
        byte[] before = Files.readAllBytes(file(cube(at)));
        Fixture f = open();
        Entity conflict = EntityTypes.PIG.create(f.level, EntitySpawnReason.LOAD);
        conflict.setUUID(id);
        conflict.snapTo(at);
        assertTrue(f.level.addFreshEntity(conflict));
        assertThrows(RuntimeException.class, () -> f.load(at));
        assertSame(conflict, f.byId(id));
        assertThrows(IOException.class, f::close);
        assertArrayEquals(before, Files.readAllBytes(file(cube(at))));
    }

    @Test
    void failedDiskWriteKeepsLiveEntitiesAndTheirUuids() throws Exception {
        Vec3 at = new Vec3(0, 32000000, 0);
        Fixture f = open();
        Entity entity = f.entity(EntityTypes.PIG, at);
        f.load(at);
        // A directory at the final file path deterministically fails atomic replacement on Windows and Unix.
        Files.createDirectories(file(cube(at)));
        assertThrows(RuntimeException.class, () -> f.cubes.unload(cube(at)));
        assertFalse(entity.isRemoved());
        assertTrue(f.manager.isLoaded(entity.getUUID()));
        assertThrows(IOException.class, f::close);
    }

    @Test
    void serializationFailurePreservesEveryOldSnapshot() throws Exception {
        Vec3 at = new Vec3(0, -32000000, 0);
        Fixture f = open();
        Entity root = f.entity(EntityTypes.PIG, at);
        f.manager.saveAll();
        byte[] before = Files.readAllBytes(file(cube(at)));
        Entity parent = root;
        for (int i = 0; i < 32; i++) {
            Entity child = f.entity(EntityTypes.PIG, at);
            assertTrue(child.startRiding(parent, true, false));
            parent = child;
        }
        assertThrows(RuntimeException.class, f.manager::saveAll);
        assertArrayEquals(before, Files.readAllBytes(file(cube(at))));
        assertThrows(IOException.class, f::close);
    }

    @Test
    void cubicStatusAndUnloadUseCubeLifecycleWhileColumnOperationsStayInactive() throws Exception {
        Vec3 at = new Vec3(0, 32000000, 0);
        try (Fixture f = open()) {
            CubicServerLevel bridge = (CubicServerLevel) f.level;
            doCallRealMethod().when(bridge).cc_onCubeEntityStatus(any(), any());
            doCallRealMethod().when(bridge).cc_unloadCubeEntities(any());
            doCallRealMethod().when(bridge).cc_areCubeEntitiesLoaded(any());
            bridge.cc_onCubeEntityStatus(cube(at), FullChunkStatus.INACCESSIBLE);
            assertFalse(bridge.cc_areCubeEntitiesLoaded(cube(at)));
            bridge.cc_onCubeEntityStatus(cube(at), FullChunkStatus.FULL);
            assertTrue(bridge.cc_areCubeEntitiesLoaded(cube(at)));
            Entity entity = f.entity(EntityTypes.PIG, at);
            f.manager.updateChunkStatus(new ChunkPos(0, 0), Visibility.TICKING);
            f.manager.updateChunkStatus(new ChunkPos(0, 0), FullChunkStatus.INACCESSIBLE);
            f.manager.tick();
            verify(f.vanilla, never()).loadEntities(any());
            verify(f.vanilla, never()).storeEntities(any());
            assertFalse(entity.isRemoved());
            bridge.cc_unloadCubeEntities(cube(at));
            assertTrue(entity.isRemoved());
            bridge.cc_onCubeEntityStatus(cube(at), FullChunkStatus.ENTITY_TICKING);
            assertEquals(1, f.all().size());
        }
    }

    @Test
    void ordinaryLevelsRetainNativeEntityStorage() throws Exception {
        try (Fixture f = open()) {
            when(((CanBeCubic) f.level).cc_isCubic()).thenReturn(false);
            when(f.vanilla.loadEntities(any())).thenAnswer(
                    i -> CompletableFuture.completedFuture(new net.minecraft.world.level.entity.ChunkEntities<Entity>(i.getArgument(0), List.of())));
            f.manager.updateChunkStatus(new ChunkPos(0, 0), Visibility.TRACKED);
            f.manager.processPendingLoads();
            f.entity(EntityTypes.PIG, new Vec3(0, 100, 0));
            f.manager.saveAll();
            verify(f.vanilla).loadEntities(new ChunkPos(0, 0));
            verify(f.vanilla).storeEntities(any());
            assertFalse(Files.exists(directory.resolve(".cube-storage.lock")));
        }
    }

    @Test
    void attachedEntitiesAndFractionalBoundsKeepNativeState() throws Exception {
        UUID frameId;
        UUID paintingId;
        BlockPos anchor = new BlockPos(3, -32000000, 3);
        Vec3 endpoint = new Vec3(CubicBlockBounds.MIN + 0.25, CubicBlockBounds.MAX + 0.75, CubicBlockBounds.MAX + 0.5);
        UUID endpointId;
        try (Fixture f = open()) {
            var frame = new ItemFrame(f.level, anchor, Direction.NORTH);
            frame.setItem(new ItemStack(Items.DIAMOND), false);
            assertTrue(f.level.addFreshEntity(frame));
            frameId = frame.getUUID();
            var variant = f.level.registryAccess().lookupOrThrow(Registries.PAINTING_VARIANT).listElements()
                    .filter(v -> v.value().width() == 2 && v.value().height() == 2).findFirst().orElseThrow();
            var painting = new Painting(f.level, anchor.offset(4, 0, 0), Direction.SOUTH, variant);
            assertTrue(f.level.addFreshEntity(painting));
            paintingId = painting.getUUID();
            endpointId = f.entity(EntityTypes.PIG, endpoint).getUUID();
        }
        try (Fixture f = open()) {
            f.load(Vec3.atLowerCornerOf(anchor));
            f.load(endpoint);
            var frame = (ItemFrame) f.byId(frameId);
            var painting = (Painting) f.byId(paintingId);
            assertEquals(anchor, frame.getPos());
            assertTrue(frame.getItem().is(Items.DIAMOND));
            assertEquals(anchor.offset(4, 0, 0), painting.getPos());
            assertEquals(2, painting.getBoundingBox().getYsize());
            assertEquals(endpoint, f.byId(endpointId).position());
        }
    }

    @Test
    void ownerThreadIsRequiredBeforeAnyDiskOrWorldMutation() throws Exception {
        try (Fixture f = open()) {
            Throwable failure = CompletableFuture.supplyAsync(() -> {
                try {
                    f.cubes.load(CubePos.of(0, 1000000, 0));
                    return null;
                } catch (RuntimeException exception) {
                    return exception;
                }
            }).join();
            assertTrue(failure instanceof IllegalStateException);
            assertFalse(Files.exists(directory.resolve(".cube-storage.lock")));
        }
    }

    private void assertInvalidFile(Consumer<CompoundTag> mutate) throws Exception {
        Vec3 at = new Vec3(0, 32000000, 0);
        try (Fixture f = open()) {
            f.entity(EntityTypes.PIG, at);
            f.entity(EntityTypes.PIG, at.add(1, 0, 0));
        }
        Path file = file(cube(at));
        CompoundTag tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        mutate.accept(tag);
        NbtIo.writeCompressed(tag, file);
        byte[] before = Files.readAllBytes(file);
        Fixture f = open();
        assertThrows(RuntimeException.class, () -> f.load(at));
        assertTrue(f.all().isEmpty());
        assertThrows(IOException.class, f::close);
        assertArrayEquals(before, Files.readAllBytes(file));
    }

    private Path file(CubePos pos) {
        return directory.resolve("r." + (pos.getX() >> 5) + "." + (pos.getY() >> 5) + "." + (pos.getZ() >> 5))
                .resolve("c." + pos.getX() + "." + pos.getY() + "." + pos.getZ() + ".nbt");
    }

    private static CubePos cube(Vec3 pos) {
        return CubePos.from(BlockPos.containing(pos));
    }

    private Fixture open() throws Exception {
        return new Fixture(directory);
    }

    private static final class Fixture implements AutoCloseable {
        private final ServerLevel level = mockLevelWithPalettes(ServerLevel.class);
        private final EntityPersistentStorage<Entity> vanilla = mock();
        private final LevelCallback<Entity> callbacks = mock();
        private final PersistentEntitySectionManager<Entity> manager = new PersistentEntitySectionManager<>(Entity.class, callbacks, vanilla);
        private final CubeEntityPersistence cubes;
        private int nextId;

        private Fixture(Path directory) throws Exception {
            Misc.setupLevelHeight(level, -64, 384);
            when(((CanBeCubic) level).cc_isCubic()).thenReturn(true);
            when(level.enabledFeatures()).thenReturn(FeatureFlags.DEFAULT_FLAGS);
            when(level.getNextEntityId()).thenAnswer(i -> ++nextId);
            when(level.dimension()).thenReturn(Level.OVERWORLD);
            var field = ServerLevel.class.getDeclaredField("entityManager");
            field.setAccessible(true);
            field.set(level, manager);
            doCallRealMethod().when((ServerLevelEntityAccess) level).cc_entityManager();
            doCallRealMethod().when(level).addFreshEntity(any());
            ((CubicEntityManager) manager).cc_configureEntityStorage(level, directory);
            cubes = ((CubicEntityManager) manager).cc_entityPersistence();
            assertNotNull(cubes);
        }

        private <T extends Entity> T entity(EntityType<T> type, Vec3 pos) {
            T entity = type.create(level, EntitySpawnReason.STRUCTURE);
            assertNotNull(entity);
            entity.snapTo(pos);
            assertTrue(level.addFreshEntity(entity));
            return entity;
        }

        private void load(Vec3 pos) {
            cubes.load(cube(pos));
        }

        private List<Entity> all() {
            var sections = ((EntityManagerAccess) manager).cc_sectionStorage();
            return sections.getAllChunksWithExistingSections().longStream().boxed().flatMap(sections::getExistingSectionsInChunk)
                    .flatMap(section -> section.getEntities()).toList();
        }

        private Entity byId(UUID id) {
            return all().stream().filter(entity -> entity.getUUID().equals(id)).findFirst().orElseThrow();
        }

        @Override public void close() throws IOException {
            manager.close();
        }
    }
}
