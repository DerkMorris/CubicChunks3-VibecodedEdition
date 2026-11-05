package io.github.opencubicchunks.cubicchunks.test.world.level.levelgen.structure;

import static io.github.opencubicchunks.cubicchunks.testutils.Misc.mockLevelWithPalettes;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.world.level.CloPos;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.mixin.access.common.EntityManagerAccess;
import io.github.opencubicchunks.cubicchunks.mixin.access.common.ServerLevelEntityAccess;
import io.github.opencubicchunks.cubicchunks.network.CCServerboundCubeSetStructureBlockPacket;
import io.github.opencubicchunks.cubicchunks.server.level.CCServerPlayer;
import io.github.opencubicchunks.cubicchunks.server.level.CloTrackingView;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.Misc;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeSource;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.RandomSource;
import net.minecraft.util.datafix.DataFixers;
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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.StructureBlock;
import net.minecraft.world.level.block.entity.StructureBlockEntity;
import net.minecraft.world.level.block.state.properties.StructureMode;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.entity.LevelCallback;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource.LevelStorageAccess;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestCubeTemplateEntities extends BaseTest {
    private static final Identifier NAME = Identifier.fromNamespaceAndPath("cubicchunks", "entities");
    private static final Vec3i SIZE = new Vec3i(5, 6, 5);
    @TempDir
    Path directory;

    @Test
    void diskRoundTripRestoresActualMobsEquipmentNamesAndFractionalCoordinatesAtBothHeightSigns() throws Exception {
        for (int y : new int[] { -32000001, 32000031 }) {
            Fixture f = new Fixture(directory.resolve("disk_" + y));
            BlockPos source = new BlockPos(31, y, -1);
            var pig = f.entity(EntityTypes.PIG, Vec3.atLowerCornerOf(source).add(1.25, 2.5, 2.75));
            pig.setCustomName(Component.literal("High pig"));
            pig.setHealth(7);
            var stand = f.entity(EntityTypes.ARMOR_STAND, Vec3.atLowerCornerOf(source).add(3.25, 1.25, 1.75));
            stand.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
            assertTrue(f.save(source, true));
            var template = f.templates().get(NAME).orElseThrow();
            assertEquals(2, template.save(new CompoundTag()).getListOrEmpty("entities").size());
            BlockPos target = new BlockPos(-33, -y, 31);
            clearInvocations(f.callbacks);
            assertTrue(f.place(template, target, settings()));
            var copies = f.inBox(target, SIZE);
            assertEquals(2, copies.size());
            Entity copy = copies.stream().filter(e -> e.getType() == EntityTypes.PIG).findFirst().orElseThrow();
            assertEquals(Vec3.atLowerCornerOf(target).add(1.25, 2.5, 2.75), copy.position());
            assertEquals("High pig", copy.getCustomName().getString());
            assertEquals(7, ((LivingEntity) copy).getHealth());
            assertNotEquals(pig.getUUID(), copy.getUUID());
            assertTrue(f.entities.isLoaded(copy.getUUID()));
            verify(f.callbacks).onCreated(copy);
            var equipment = (LivingEntity) copies.stream().filter(e -> e.getType() == EntityTypes.ARMOR_STAND).findFirst().orElseThrow();
            assertTrue(equipment.getItemBySlot(EquipmentSlot.HEAD).is(Items.DIAMOND_HELMET));
            // Recapture from the real entity manager, not a detached list of created objects.
            assertTrue(f.save(target, false));
            assertEquals(2, f.templates.get(NAME).orElseThrow().save(new CompoundTag()).getListOrEmpty("entities").size());
        }
    }

    @Test
    void packedSectionAliasesAreSeparatedByFullBoundingBoxesAndFollowMovementAndRemoval() throws Exception {
        Fixture f = new Fixture(directory);
        BlockPos high = new BlockPos(31, 32000031, 31);
        int aliasY = SectionPos.sectionToBlockCoord(SectionPos.y(SectionPos.asLong(high)));
        var wanted = f.entity(EntityTypes.PIG, Vec3.atLowerCornerOf(high).add(2, 1, 2));
        var alias = f.entity(EntityTypes.PIG, new Vec3(33, aliasY + 1, 33));
        assertTrue(f.save(high, false));
        assertEquals(1, f.savedEntities().size());
        alias.snapTo(wanted.position().add(1, 0, 0));
        assertTrue(f.save(high, false));
        assertEquals(2, f.savedEntities().size());
        wanted.discard();
        assertFalse(f.entities.isLoaded(wanted.getUUID()));
        assertTrue(f.save(high, false));
        assertEquals(1, f.savedEntities().size());
    }

    @Test
    void passengerTreesArePlacedOnceWithFreshUuidsOnEveryPaste() throws Exception {
        Fixture f = new Fixture(directory);
        BlockPos from = new BlockPos(31, -32000001, 31);
        var vehicle = f.entity(EntityTypes.PIG, Vec3.atLowerCornerOf(from).add(2, 1, 2));
        var rider = f.entity(EntityTypes.ARMOR_STAND, Vec3.atLowerCornerOf(from).add(2, 2, 2));
        assertTrue(rider.startRiding(vehicle, true, false));
        assertTrue(f.save(from, false));
        assertEquals(1, f.savedEntities().size());
        var template = f.templates.get(NAME).orElseThrow();
        CompoundTag before = template.save(new CompoundTag()).copy();
        Set<java.util.UUID> uuids = new HashSet<>(Set.of(vehicle.getUUID(), rider.getUUID()));
        for (int x : new int[] { 31, 95 }) {
            BlockPos to = new BlockPos(x, 32000031, 31);
            assertTrue(f.place(template, to, settings()));
            var placed = f.inBox(to, SIZE);
            assertEquals(2, placed.size());
            Entity root = placed.stream().filter(e -> !e.isPassenger()).findFirst().orElseThrow();
            assertEquals(1, root.getPassengers().size());
            assertEquals(root, root.getFirstPassenger().getVehicle());
            assertEquals(Vec3.atLowerCornerOf(to).add(2, 2, 2), root.getFirstPassenger().position());
            placed.forEach(e -> assertTrue(uuids.add(e.getUUID())));
        }
        assertEquals(before, template.save(new CompoundTag()));
    }

    @Test
    void rotationsMirrorsAndPivotsTransformFractionalPositionsAndYaw() throws Exception {
        Fixture f = new Fixture(directory);
        BlockPos from = new BlockPos(0, -32000000, 0);
        var entity = f.entity(EntityTypes.PIG, Vec3.atLowerCornerOf(from).add(1.25, 1.5, 2.75));
        entity.setYRot(35);
        assertTrue(f.save(from, false));
        var template = f.templates.get(NAME).orElseThrow();
        int index = 0;
        for (Mirror mirror : Mirror.values()) {
            for (Rotation rotation : Rotation.values()) {
                BlockPos to = new BlockPos(100 + index++ * 32, 32000000, 100);
                var settings = settings().setMirror(mirror).setRotation(rotation).setRotationPivot(new BlockPos(1, 0, 2));
                assertTrue(f.place(template, to, settings));
                Vec3 expected = StructureTemplate.transform(new Vec3(1.25, 1.5, 2.75), mirror, rotation, settings.getRotationPivot())
                        .add(Vec3.atLowerCornerOf(to));
                Entity copy = f.all().stream().filter(e -> e.position().equals(expected)).findFirst().orElseThrow();
                float yaw = entity.rotate(rotation) + entity.mirror(mirror) - entity.getYRot();
                assertEquals(yaw, copy.getYRot());
            }
        }
    }

    @Test
    void blockAttachedEntityKeepsAnchorAndItemBeyondVanillaLoadClamp() throws Exception {
        Fixture f = new Fixture(directory);
        BlockPos from = new BlockPos(31, -32000001, 31);
        var frame = new ItemFrame(f.level, from.offset(2, 2, 2), Direction.SOUTH);
        frame.setItem(new ItemStack(Items.DIAMOND, 3));
        assertTrue(f.level.addFreshEntity(frame));
        assertTrue(f.save(from, false));
        BlockPos to = new BlockPos(31, 32000031, 31);
        assertTrue(f.place(f.templates.get(NAME).orElseThrow(), to, settings().setRotation(Rotation.CLOCKWISE_90)));
        var copy = (ItemFrame) f.all().stream().filter(e -> e != frame && e instanceof ItemFrame).findFirst().orElseThrow();
        assertEquals(to.offset(-2, 2, 2), copy.getPos());
        assertEquals(Direction.WEST, copy.getDirection());
        assertTrue(copy.getItem().is(Items.DIAMOND));
    }

    @Test
    void ignoreEntitiesSkipsCaptureAndPlacementWithoutTouchingEntityManager() throws Exception {
        Fixture f = new Fixture(directory);
        BlockPos source = new BlockPos(31, 32000031, 31);
        f.entity(EntityTypes.PIG, Vec3.atLowerCornerOf(source).add(1, 1, 1));
        assertTrue(f.save(source, false));
        int count = f.all().size();
        assertTrue(f.place(f.templates.get(NAME).orElseThrow(), source.below(32000000), settings().setIgnoreEntities(true)));
        assertEquals(count, f.all().size());
        clearInvocations(f.level);
        assertTrue(StructureBlockEntity.saveStructure(f.level, NAME, source, SIZE, true, "test", false, List.of()));
        assertEquals(0, f.savedEntities().size());
        verify((ServerLevelEntityAccess) f.level, never()).cc_entityManager();
    }

    @Test
    void invalidOrUnknownEntitiesAndMissingCoverageRejectBeforeAnyBlocksOrEntitiesAreAdded() throws Exception {
        Fixture f = new Fixture(directory);
        BlockPos from = new BlockPos(31, 32000031, 31);
        f.entity(EntityTypes.PIG, Vec3.atLowerCornerOf(from).add(1, 1, 1));
        assertTrue(f.save(from, false));
        var good = f.templates.get(NAME).orElseThrow();
        CompoundTag bad = good.save(new CompoundTag()).copy();
        bad.getListOrEmpty("entities").getCompoundOrEmpty(0).getCompoundOrEmpty("nbt").putString("id", "test:missing_entity");
        var invalid = new StructureTemplate();
        invalid.load(BuiltInRegistries.BLOCK, bad);
        BlockPos to = new BlockPos(31, -32000001, 31);
        clearInvocations(f.level);
        assertFalse(f.place(invalid, to, settings()));
        verify(f.level, never()).setBlock(any(), any(), anyInt());
        verify(f.level, never()).addFreshEntity(any());
        f.unavailable.add(CubePos.from(to));
        assertFalse(f.place(good, to, settings()));
        verify(f.level, never()).setBlock(any(), any(), anyInt());
    }

    @Test
    void entityLoadKeepsCubicFractionalHeightWhileOrdinaryWorldRetainsItsClamp() throws Exception {
        Fixture f = new Fixture(directory);
        var entity = f.entity(EntityTypes.PIG, new Vec3(1, CubicBlockBounds.MAX - 2.25, 1));
        var template = new StructureTemplate();
        template.fillFromWorld(f.level, entity.blockPosition().below(), new Vec3i(2, 3, 2), true, List.of());
        CompoundTag nbt = template.save(new CompoundTag()).getListOrEmpty("entities").getCompoundOrEmpty(0).getCompoundOrEmpty("nbt");
        var input = net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING, f.level.registryAccess(),
                nbt);
        var copy = EntityType.create(input, f.level, new net.minecraft.world.entity.EntitySpawnRequest(EntitySpawnReason.STRUCTURE, false))
                .orElseThrow();
        assertEquals(entity.position(), copy.position());
        when(((CanBeCubic) f.level).cc_isCubic()).thenReturn(false);
        var vanilla = EntityType.create(input, f.level, new net.minecraft.world.entity.EntitySpawnRequest(EntitySpawnReason.STRUCTURE, false))
                .orElseThrow();
        assertEquals(20000000, vanilla.getY());
    }

    @Test
    void playersOutsideEntitiesAndUnselectedVehiclesAreExcludedWithoutLosingSelectedPassengers() throws Exception {
        Fixture f = new Fixture(directory);
        BlockPos from = new BlockPos(31, 32000031, 31);
        Player player = mock();
        when(player.getUUID()).thenReturn(java.util.UUID.randomUUID());
        when(player.blockPosition()).thenReturn(from.offset(1, 1, 1));
        when(player.level()).thenReturn(f.level);
        when(player.getBoundingBox()).thenReturn(new net.minecraft.world.phys.AABB(from));
        assertTrue(f.entities.addNewEntity(player));
        var vehicle = f.entity(EntityTypes.PIG, Vec3.atLowerCornerOf(from).add(20, 1, 20));
        var rider = f.entity(EntityTypes.ARMOR_STAND, Vec3.atLowerCornerOf(from).add(2, 1, 2));
        assertTrue(rider.startRiding(vehicle, true, false));
        assertTrue(f.save(from, false));
        assertEquals(1, f.savedEntities().size());
        verify(player, never()).saveAsPassenger(any());
        BlockPos to = new BlockPos(31, -32000001, 31);
        assertTrue(f.place(f.templates.get(NAME).orElseThrow(), to, settings()));
        var copies = f.inBox(to, SIZE);
        assertEquals(1, copies.size());
        assertEquals(EntityTypes.ARMOR_STAND, copies.getFirst().getType());
        assertFalse(copies.getFirst().isPassenger());
    }

    @Test
    void invalidPassengerAndOutOfRangeEntityPositionsRejectTheWholePaste() throws Exception {
        Fixture f = new Fixture(directory);
        BlockPos from = new BlockPos(31, 32000031, 31);
        f.entity(EntityTypes.PIG, Vec3.atLowerCornerOf(from).add(2, 1, 2));
        assertTrue(f.save(from, false));
        var template = f.templates.get(NAME).orElseThrow();
        BlockPos to = new BlockPos(31, -32000001, 31);
        for (int scenario = 0; scenario < 3; scenario++) {
            CompoundTag data = template.save(new CompoundTag()).copy();
            CompoundTag entry = data.getListOrEmpty("entities").getCompoundOrEmpty(0);
            if (scenario == 0) {
                ListTag passengers = new ListTag();
                CompoundTag invalid = new CompoundTag();
                invalid.putString("id", "cubicchunks:missing");
                passengers.add(invalid);
                entry.getCompoundOrEmpty("nbt").put("Passengers", passengers);
            } else {
                entry.store("pos", Vec3.CODEC, new Vec3(1, scenario == 1 ? Double.NaN : Integer.MAX_VALUE, 1));
            }
            var bad = new StructureTemplate();
            bad.load(BuiltInRegistries.BLOCK, data);
            clearInvocations(f.level);
            assertFalse(f.place(bad, to, settings()));
            verify(f.level, never()).setBlock(any(), any(), anyInt());
            verify(f.level, never()).addFreshEntity(any());
            assertEquals(1, f.all().size());
        }
    }

    @Test
    void oversizedCapturePreservesPreviousPaletteAndFileBeforeScanningBlocks() throws Exception {
        Fixture f = new Fixture(directory);
        BlockPos from = new BlockPos(31, 32000031, 31);
        f.entity(EntityTypes.PIG, Vec3.atLowerCornerOf(from).add(2, 1, 2));
        assertTrue(f.save(from, true));
        var template = f.templates.get(NAME).orElseThrow();
        CompoundTag before = template.save(new CompoundTag()).copy();
        Path file = directory.resolve("cubicchunks/structure/entities.nbt");
        byte[] bytes = Files.readAllBytes(file);
        for (int i = 0; i < 1024; i++) {
            f.entity(EntityTypes.ITEM, Vec3.atLowerCornerOf(from).add(2, 1, 2));
        }
        clearInvocations(f.level);
        assertFalse(f.save(from, true));
        assertEquals(before, template.save(new CompoundTag()));
        assertArrayEquals(bytes, Files.readAllBytes(file));
        verify(f.level, never()).getBlockEntity(any(BlockPos.class));
    }

    @Test
    void decodedSaveLoadPacketsIncludeEntitiesAndFailureIsReturnedByController() throws Exception {
        Fixture f = new Fixture(directory);
        BlockPos pos = new BlockPos(31, 32000031, 31);
        f.at(pos).setBlockState(pos, Blocks.STRUCTURE_BLOCK.defaultBlockState().setValue(StructureBlock.MODE, StructureMode.SAVE));
        var controller = (StructureBlockEntity) f.at(pos).getBlockEntity(pos);
        var pig = f.entity(EntityTypes.PIG, Vec3.atLowerCornerOf(pos).add(2, 2, 2));
        ServerPlayer player = mock();
        when(player.level()).thenReturn(f.level);
        when(player.canUseGameMasterBlocks()).thenReturn(true);
        when(((CCServerPlayer) player).cc_getCloTrackingView()).thenReturn(CloTrackingView.cc_of(CloPos.cube(CubePos.from(pos)), 5));
        ServerGamePacketListenerImpl listener = mock();
        player.connection = listener;
        listener.player = player;
        when(listener.hasClientLoaded()).thenReturn(true);
        doCallRealMethod().when(listener).handleSetStructureBlock(any());
        CubePos cp = CubePos.from(pos);
        when(f.source.cc_getCube(cp.getX(), cp.getY(), cp.getZ(), ChunkStatus.FULL, false)).thenReturn(f.at(pos));
        ServerPlayNetworking.Context context = mock();
        when(context.player()).thenReturn(player);
        try (var ignored = mockStatic(PacketUtils.class)) {
            receiveEdit(pos, StructureMode.SAVE, StructureBlockEntity.UpdateType.SAVE_AREA, new BlockPos(1, 1, 1), context);
            f.templates.remove(NAME);
            receiveEdit(pos, StructureMode.LOAD, StructureBlockEntity.UpdateType.LOAD_AREA, new BlockPos(10, 10, 10), context);
        }
        var copies = f.inBox(pos.offset(10, 10, 10), SIZE);
        assertEquals(1, copies.size());
        assertNotEquals(pig.getUUID(), copies.getFirst().getUUID());
        CompoundTag tag = f.templates.get(NAME).orElseThrow().save(new CompoundTag());
        tag.getListOrEmpty("entities").getCompoundOrEmpty(0).getCompoundOrEmpty("nbt").putString("id", "test:missing");
        f.templates.get(NAME).orElseThrow().load(BuiltInRegistries.BLOCK, tag);
        clearInvocations(f.level);
        assertFalse(controller.placeStructureIfSameSize(f.level));
        verify(f.level, never()).setBlock(any(), any(), anyInt());
    }

    private static void receiveEdit(
            BlockPos pos, StructureMode mode, StructureBlockEntity.UpdateType type, BlockPos offset, ServerPlayNetworking.Context context
    ) {
        var packet = new CCServerboundCubeSetStructureBlockPacket(pos, type, mode, NAME.toString(), offset, SIZE, Mirror.NONE, Rotation.NONE, "",
                false, false, false, true, 1, 42);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            CCServerboundCubeSetStructureBlockPacket.STREAM_CODEC.encode(buffer, packet);
            new CCServerboundCubeSetStructureBlockPacket.Handler().receive(CCServerboundCubeSetStructureBlockPacket.STREAM_CODEC.decode(buffer),
                    context.player());
        } finally {
            buffer.release();
        }
    }

    @Test
    void largeEntityRequiresCoverageBeyondTheBlockTemplateBorder() throws Exception {
        Fixture f = new Fixture(directory);
        BlockPos from = new BlockPos(26, 32000026, 26);
        f.entity(EntityTypes.GHAST, Vec3.atLowerCornerOf(from).add(4.9, 1, 2));
        assertTrue(f.save(from, false));
        var template = f.templates.get(NAME).orElseThrow();
        BlockPos to = new BlockPos(26, -32000006, 26);
        // Block template plus its one-block margin ends at X=31; the ghast's box extends into X=32.
        f.unavailable.add(CubePos.from(to.offset(6, 1, 2)));
        clearInvocations(f.level);
        assertFalse(f.place(template, to, settings()));
        verify(f.level, never()).setBlock(any(), any(), anyInt());
        verify(f.level, never()).addFreshEntity(any());
        f.unavailable.clear();
        assertTrue(f.place(template, to, settings()));
        assertEquals(1, f.inBox(to, SIZE).size());
    }

    @Test
    void ordinaryWorldKeepsVanillaEntityQueryAndPlacement() throws Exception {
        Fixture f = new Fixture(directory);
        BlockPos from = new BlockPos(1, 64, 1);
        var pig = f.entity(EntityTypes.PIG, Vec3.atLowerCornerOf(from).add(2, 1, 2));
        when(((CanBeCubic) f.level).cc_isCubic()).thenReturn(false);
        when(f.level.getLevel()).thenReturn(f.level);
        when(f.level.getEntitiesOfClass(eq(Entity.class), any(net.minecraft.world.phys.AABB.class), any())).thenReturn(List.of(pig));
        clearInvocations(f.source, f.level);
        assertTrue(f.save(from, false));
        BlockPos to = new BlockPos(10, 64, 10);
        assertTrue(f.place(f.templates.get(NAME).orElseThrow(), to, settings()));
        assertEquals(1, f.inBox(to, SIZE).size());
        verify(f.level).getEntitiesOfClass(eq(Entity.class), any(net.minecraft.world.phys.AABB.class), any());
        verify((ServerLevelEntityAccess) f.level, never()).cc_entityManager();
        verify(f.source, never()).cc_getCubeNow(anyInt(), anyInt(), anyInt());
    }

    @Test
    void excessivePassengerDepthFailsCaptureAndPlacementWithoutChangingTheTemplateOrWorld() throws Exception {
        Fixture f = new Fixture(directory);
        BlockPos from = new BlockPos(31, 32000031, 31);
        Vec3 position = Vec3.atLowerCornerOf(from).add(2, 1, 2);
        Entity parent = f.entity(EntityTypes.PIG, position);
        assertTrue(f.save(from, false));
        var template = f.templates.get(NAME).orElseThrow();
        CompoundTag before = template.save(new CompoundTag()).copy();
        CompoundTag bad = before.copy();
        CompoundTag root = bad.getListOrEmpty("entities").getCompoundOrEmpty(0).getCompoundOrEmpty("nbt");
        CompoundTag childData = root.copy();
        CompoundTag cursor = root;
        for (int i = 0; i < 32; i++) {
            ListTag passengers = new ListTag();
            CompoundTag child = childData.copy();
            passengers.add(child);
            cursor.put("Passengers", passengers);
            cursor = child;
            Entity passenger = f.entity(EntityTypes.PIG, position);
            assertTrue(passenger.startRiding(parent, true, false));
            parent = passenger;
        }
        assertFalse(f.save(from, false));
        assertEquals(before, template.save(new CompoundTag()));
        StructureTemplate invalid = new StructureTemplate();
        invalid.load(BuiltInRegistries.BLOCK, bad);
        clearInvocations(f.level);
        assertFalse(f.place(invalid, from.below(64000000), settings()));
        verify(f.level, never()).setBlock(any(), any(), anyInt());
        verify(f.level, never()).addFreshEntity(any());
    }

    @Test
    void evenSizedPaintingsKeepTheirAnchorVariantAndSpecializedBoundingBoxForAllTransforms() throws Exception {
        Fixture f = new Fixture(directory);
        BlockPos from = new BlockPos(31, -32000001, 31);
        var variant = f.level.registryAccess().lookupOrThrow(Registries.PAINTING_VARIANT).listElements()
                .filter(holder -> holder.value().width() == 2 && holder.value().height() == 2).findFirst().orElseThrow();
        var painting = new Painting(f.level, from.offset(2, 2, 2), Direction.SOUTH, variant);
        assertTrue(f.level.addFreshEntity(painting));
        assertTrue(f.save(from, true));
        StructureTemplate template = f.templates().get(NAME).orElseThrow();
        int index = 0;
        for (Mirror mirror : Mirror.values()) {
            for (Rotation rotation : Rotation.values()) {
                BlockPos to = new BlockPos(100 + index++ * 32, 32000031, 100);
                var settings = settings().setMirror(mirror).setRotation(rotation);
                assertTrue(f.place(template, to, settings));
                BlockPos anchor = StructureTemplate.calculateRelativePosition(settings, new BlockPos(2, 2, 2)).offset(to);
                var copy = (Painting) f.all().stream().filter(e -> e instanceof Painting p && p.getPos().equals(anchor)).findFirst().orElseThrow();
                assertEquals(variant, copy.getVariant());
                assertEquals(2, copy.getBoundingBox().getYsize());
                assertEquals(copy.position(), copy.getBoundingBox().getCenter());
                assertEquals(0.0625, Math.min(copy.getBoundingBox().getXsize(), copy.getBoundingBox().getZsize()));
            }
        }
    }

    private static StructurePlaceSettings settings() {
        return new StructurePlaceSettings().setIgnoreEntities(false).setRandom(RandomSource.create(42));
    }

    private static final class Fixture {
        private final ServerLevel level = mockLevelWithPalettes(ServerLevel.class);
        private final ServerChunkCache cache = mock();
        private final CubeSource source = (CubeSource) cache;
        private final Map<CubePos, LevelCube> cubes = new HashMap<>();
        private final Set<CubePos> unavailable = new HashSet<>();
        private final LevelCallback<Entity> callbacks = mock();
        private final PersistentEntitySectionManager<Entity> entities = new PersistentEntitySectionManager<>(Entity.class, callbacks, mock());
        private final StructureTemplateManager templates;
        private final Path directory;
        private int nextId;

        private Fixture(Path directory) throws Exception {
            this.directory = directory;
            Misc.setupLevelHeight(level, -64, 384);
            when(((CanBeCubic) level).cc_isCubic()).thenReturn(true);
            when(level.enabledFeatures()).thenReturn(FeatureFlags.DEFAULT_FLAGS);
            when(level.getNextEntityId()).thenAnswer(i -> ++nextId);
            when(level.dimension()).thenReturn(Level.OVERWORLD);
            when(level.getChunkSource()).thenReturn(cache);
            when(cache.getLightEngine()).thenReturn(mock());
            when(level.getRandom()).thenReturn(RandomSource.create(42));
            when(source.cc_getCubeNow(anyInt(), anyInt(), anyInt())).thenAnswer(i -> {
                CubePos pos = CubePos.of(i.getArgument(0), i.getArgument(1), i.getArgument(2));
                return unavailable.contains(pos) ? null : at(pos.asBlockPos());
            });
            when(level.getBlockState(any())).thenAnswer(i -> at(i.getArgument(0)).getBlockState(i.getArgument(0)));
            when(level.getFluidState(any())).thenAnswer(i -> at(i.getArgument(0)).getFluidState(i.getArgument(0)));
            when(level.getBlockEntity(any(BlockPos.class))).thenAnswer(i -> at(i.getArgument(0)).getBlockEntity(i.getArgument(0)));
            when(level.setBlock(any(), any(), anyInt()))
                    .thenAnswer(i -> at(i.getArgument(0)).setBlockState(i.getArgument(0), i.getArgument(1)) != null);
            var field = ServerLevel.class.getDeclaredField("entityManager");
            field.setAccessible(true);
            field.set(level, entities);
            doCallRealMethod().when((ServerLevelEntityAccess) level).cc_entityManager();
            doCallRealMethod().when(level).addFreshEntity(any());
            doCallRealMethod().when(level).addFreshEntityWithPassengers(any());
            doCallRealMethod().when(level).tryAddFreshEntityWithPassengers(any());
            templates = templates();
            when(level.getStructureTemplateManager()).thenReturn(templates);
        }

        private StructureTemplateManager templates() {
            LevelStorageAccess storage = mock();
            when(storage.getLevelPath(LevelResource.GENERATED_DIR)).thenReturn(directory);
            return new StructureTemplateManager(mock(ResourceManager.class), storage, DataFixers.getDataFixer(), BuiltInRegistries.BLOCK);
        }

        private LevelCube at(BlockPos pos) {
            return cubes.computeIfAbsent(CubePos.from(pos), p -> new LevelCube(level, p));
        }

        private <T extends Entity> T entity(EntityType<T> type, Vec3 pos) {
            T entity = type.create(level, EntitySpawnReason.STRUCTURE);
            assertNotNull(entity);
            entity.snapTo(pos);
            assertTrue(level.addFreshEntity(entity));
            return entity;
        }

        private List<Entity> all() {
            var sections = ((EntityManagerAccess) entities).cc_sectionStorage();
            return sections.getAllChunksWithExistingSections().longStream().boxed().flatMap(sections::getExistingSectionsInChunk)
                    .flatMap(s -> s.getEntities()).toList();
        }

        private List<Entity> inBox(BlockPos origin, Vec3i size) {
            var box = net.minecraft.world.phys.AABB.encapsulatingFullBlocks(origin, origin.offset(size).offset(-1, -1, -1));
            return all().stream().filter(e -> e.getBoundingBox().intersects(box)).toList();
        }

        private boolean save(BlockPos pos, boolean disk) {
            return StructureBlockEntity.saveStructure(level, NAME, pos, SIZE, false, "entities", disk, List.of());
        }

        private ListTag savedEntities() {
            return templates.get(NAME).orElseThrow().save(new CompoundTag()).getListOrEmpty("entities");
        }

        private boolean place(StructureTemplate template, BlockPos pos, StructurePlaceSettings settings) {
            return template.placeInWorld(level, pos, pos, settings, RandomSource.create(42), 2);
        }
    }
}
