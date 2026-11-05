package io.github.opencubicchunks.cubicchunks.test.world.level.levelgen.structure;

import static io.github.opencubicchunks.cubicchunks.testutils.Misc.mockLevelWithPalettes;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
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
import io.github.opencubicchunks.cubicchunks.network.CCServerboundCubeSetStructureBlockPacket;
import io.github.opencubicchunks.cubicchunks.server.level.CCServerPlayer;
import io.github.opencubicchunks.cubicchunks.server.level.CloTrackingView;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.Misc;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeSource;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.structure.CubeTemplateBlockMatcher;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.RandomSource;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.StructureBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.StructureBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.StructureMode;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockRotProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource.LevelStorageAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestCubeStructureTemplates extends BaseTest {
    private static final Identifier NAME = Identifier.fromNamespaceAndPath("cubicchunks", "test_template");
    private static final Vec3i SIZE = new Vec3i(3, 4, 5);
    @TempDir
    Path directory;

    @Test
    void realDiskRoundTripAtLargePositiveAndNegativeHeightsIncludesAirAndPendingInventory() throws Exception {
        for (int y : new int[] { -32000001, -4097, 2047, 32000031 }) {
            Fixture f = fixture("disk_" + y);
            BlockPos from = new BlockPos(31, y, -1);
            f.sample(from);
            BlockPos chest = from.offset(1, 1, 1);
            var pending = f.at(chest).getBlockEntity(chest).saveWithFullMetadata(f.level.registryAccess());
            f.at(chest).getBlockEntities().remove(chest);
            f.at(chest).setBlockEntityNbt(pending);
            assertTrue(f.save(from, SIZE, true));
            var cached = f.manager.get(NAME).orElseThrow();
            CompoundTag disk = NbtIo.readCompressed(f.file(), NbtAccounter.unlimitedHeap());
            assertEquals(59, disk.getListOrEmpty("blocks").size()); // air included, one structure void omitted
            var reloaded = f.manager().get(NAME).orElseThrow();
            assertNotSame(cached, reloaded);
            assertEquals(SIZE, reloaded.getSize());
            assertEquals(cached.save(new CompoundTag()), reloaded.save(new CompoundTag()));
            BlockPos to = new BlockPos(-33, -y, 31);
            f.fill(to, SIZE, Blocks.OBSIDIAN.defaultBlockState());
            assertTrue(f.place(reloaded, to, settings()));
            assertEquals(Blocks.STONE.defaultBlockState(), f.state(to));
            assertTrue(f.state(to.offset(2, 3, 3)).isAir());
            assertTrue(f.state(to.offset(2, 3, 4)).is(Blocks.OBSIDIAN)); // void does not clear the target
            f.assertChest(to.offset(1, 1, 1), Direction.NORTH);
            f.noColumnOrLoading();
        }
    }

    @Test
    void allRotationsAndMirrorsPreserveStatesNbtAndRelativePositionsAcrossCubeBoundaries() {
        Fixture f = fixture("transforms");
        BlockPos from = new BlockPos(31, -32000001, 31);
        f.sample(from);
        assertTrue(f.save(from, SIZE, false));
        StructureTemplate template = f.manager.get(NAME).orElseThrow();
        CompoundTag before = template.save(new CompoundTag()).copy();
        int index = 0;
        for (Mirror mirror : Mirror.values()) {
            for (Rotation rotation : Rotation.values()) {
                BlockPos to = new BlockPos(31 + index++ * 64, 32000031, 31);
                var settings = settings().setMirror(mirror).setRotation(rotation).setRotationPivot(new BlockPos(1, 0, 2));
                assertTrue(f.place(template, to, settings));
                BlockPos stone = StructureTemplate.calculateRelativePosition(settings, BlockPos.ZERO).offset(to);
                BlockPos chest = StructureTemplate.calculateRelativePosition(settings, new BlockPos(1, 1, 1)).offset(to);
                assertTrue(f.state(stone).is(Blocks.STONE));
                BlockState expected = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH).mirror(mirror).rotate(rotation);
                f.assertChest(chest, expected.getValue(ChestBlock.FACING));
            }
        }
        assertEquals(before, template.save(new CompoundTag()));
    }

    @Test
    void missingSourceLeavesCachedTemplateDiskAndPendingEntitiesUntouchedThenAllowsRetry() throws Exception {
        Fixture f = fixture("missing_source");
        BlockPos from = new BlockPos(31, 32000031, 31);
        f.sample(from);
        assertTrue(f.save(from, SIZE, true));
        var template = f.manager.get(NAME).orElseThrow();
        CompoundTag before = template.save(new CompoundTag()).copy();
        byte[] disk = Files.readAllBytes(f.file());
        BlockPos chest = from.offset(1, 1, 1);
        var tag = f.at(chest).getBlockEntity(chest).saveWithFullMetadata(f.level.registryAccess());
        f.at(chest).getBlockEntities().remove(chest);
        f.at(chest).setBlockEntityNbt(tag);
        f.unavailable.add(CubePos.from(from));
        assertFalse(f.save(from, SIZE, true));
        assertThrows(IllegalStateException.class, () -> template.fillFromWorld(f.level, from, SIZE, false, List.of()));
        assertEquals(before, template.save(new CompoundTag()));
        assertArrayEquals(disk, Files.readAllBytes(f.file()));
        assertFalse(f.at(chest).getBlockEntities().containsKey(chest));
        f.unavailable.clear();
        assertTrue(f.save(from, SIZE, true));
        f.noColumnOrLoading();
    }

    @Test
    void missingDestinationOrNeighborRejectsBeforeAnyWriteOrRandomConsumption() {
        Fixture f = fixture("missing_target");
        BlockPos from = new BlockPos(4, -32000000, 4);
        f.sample(from);
        assertTrue(f.save(from, SIZE, false));
        var template = f.manager.get(NAME).orElseThrow();
        BlockPos to = new BlockPos(32, 32000032, 32);
        for (BlockPos missing : List.of(to.offset(1, 1, 1), to.offset(-1, -1, -1))) {
            f.unavailable.add(CubePos.from(missing));
            clearInvocations(f.level);
            RandomSource random = mock();
            assertFalse(template.placeInWorld(f.level, to, to, settings(), random, 2));
            verify(f.level, never()).setBlock(any(), any(), anyInt());
            verify(random, never()).nextLong();
            f.unavailable.clear();
        }
        assertTrue(f.place(template, to, settings()));
    }

    @Test
    void structureBlockReportsRejectedPlacementAndRetainsVanillaSizeHandshake() {
        Fixture f = fixture("controller");
        BlockPos from = new BlockPos(0, -32000000, 0);
        f.sample(from);
        assertTrue(f.save(from, SIZE, false));
        BlockPos controllerPos = new BlockPos(31, 32000031, 31);
        var controller = f.controller(controllerPos, StructureMode.LOAD);
        controller.setStructurePos(new BlockPos(1, 1, 1));
        assertFalse(controller.placeStructureIfSameSize(f.level));
        assertEquals(SIZE, controller.getStructureSize());
        f.unavailable.add(CubePos.from(controllerPos.offset(1, 1, 1)));
        clearInvocations(f.level);
        assertFalse(controller.placeStructureIfSameSize(f.level));
        verify(f.level, never()).setBlock(any(), any(), anyInt());
        f.unavailable.clear();
        assertTrue(controller.placeStructureIfSameSize(f.level));
        f.assertChest(controllerPos.offset(2, 2, 2), Direction.NORTH);
    }

    @Test
    void captureAtExactCoordinateEndpointsAndPlacementWithNeighborMarginDoNotWrap() {
        Fixture f = fixture("limits");
        Vec3i one = new Vec3i(1, 1, 1);
        for (int end : new int[] { CubicBlockBounds.MIN, CubicBlockBounds.MAX }) {
            BlockPos from = new BlockPos(end, end, end);
            f.put(from, Blocks.GOLD_BLOCK.defaultBlockState());
            assertTrue(f.save(from, one, false));
            var template = f.manager.get(NAME).orElseThrow();
            assertFalse(f.place(template, from, settings())); // default shape/water checks need representable neighbors
            BlockPos to = from.offset(end == CubicBlockBounds.MIN ? 1 : -1, end == CubicBlockBounds.MIN ? 1 : -1,
                    end == CubicBlockBounds.MIN ? 1 : -1);
            assertTrue(f.place(template, to, settings()));
            assertTrue(f.state(to).is(Blocks.GOLD_BLOCK));
            assertTrue(f.place(template, from, settings().setKnownShape(true).setLiquidSettings(LiquidSettings.IGNORE_WATERLOGGING)));
        }
        f.noColumnOrLoading();
    }

    @Test
    void invalidSizeOverflowAndRotatedOutOfRangeAreasFailWithoutStorageAccess() {
        Fixture f = fixture("invalid");
        for (Vec3i size : List.of(Vec3i.ZERO, new Vec3i(-1, 1, 1), new Vec3i(Integer.MAX_VALUE, 1, 1),
                new Vec3i(1, CubicBlockBounds.MAX - CubicBlockBounds.MIN + 2, 1))) {
            assertFalse(f.save(BlockPos.ZERO, size, false));
        }
        assertFalse(f.save(new BlockPos(0, Integer.MAX_VALUE, 0), SIZE, false));
        assertFalse(f.save(new BlockPos(0, CubicBlockBounds.MAX, 0), SIZE, false));
        verify(f.source, never()).cc_getCubeNow(anyInt(), anyInt(), anyInt());
        f.sample(new BlockPos(0, 32000000, 0));
        assertTrue(f.save(new BlockPos(0, 32000000, 0), SIZE, false));
        var template = f.manager.get(NAME).orElseThrow();
        clearInvocations(f.source, f.level);
        assertFalse(f.place(template, new BlockPos(CubicBlockBounds.MIN + 1, 32000000, 0), settings().setRotation(Rotation.CLOCKWISE_90)));
        assertFalse(f.place(template, BlockPos.ZERO, settings().setRotationPivot(new BlockPos(Integer.MAX_VALUE, 0, 0))));
        verify(f.source, never()).cc_getCubeNow(anyInt(), anyInt(), anyInt());
        verify(f.level, never()).setBlock(any(), any(), anyInt());
    }

    @Test
    void wrongCubeOrWorldCannotBeUsedForCaptureOrPlacement() {
        Fixture f = fixture("identity");
        BlockPos pos = new BlockPos(0, -32000000, 0);
        f.sample(pos);
        assertTrue(f.save(pos, SIZE, false));
        var template = f.manager.get(NAME).orElseThrow();
        CubePos cp = CubePos.from(pos);
        for (LevelCube wrong : List.of(new LevelCube(f.level, CubePos.of(0, 0, 0)), new LevelCube(mockLevelWithPalettes(ServerLevel.class), cp))) {
            when(f.source.cc_getCubeNow(cp.getX(), cp.getY(), cp.getZ())).thenReturn(wrong);
            assertFalse(f.save(pos, SIZE, false));
            assertFalse(f.place(template, pos, settings()));
        }
    }

    @Test
    void boundsAndIntegrityProcessorsRetainVanillaPlacementSemantics() {
        Fixture f = fixture("processors");
        BlockPos from = new BlockPos(31, -32000001, 31);
        f.sample(from);
        assertTrue(f.save(from, SIZE, false));
        var template = f.manager.get(NAME).orElseThrow();
        BlockPos to = new BlockPos(31, 32000031, 31);
        f.fill(to, SIZE, Blocks.DIRT.defaultBlockState());
        assertTrue(f.place(template, to, settings().addProcessor(new BlockRotProcessor(0))));
        assertTrue(f.state(to).is(Blocks.DIRT));
        assertTrue(f.state(to.offset(1, 1, 1)).is(Blocks.DIRT));
        assertTrue(f.place(template, to, settings().setBoundingBox(new BoundingBox(to))));
        assertTrue(f.state(to).is(Blocks.STONE));
        assertTrue(f.state(to.offset(1, 1, 1)).is(Blocks.DIRT));
    }

    @Test
    void ordinaryWorldStillUsesItsVanillaMatcherAndDoesNotRequireCubeCoverage() {
        Fixture f = fixture("ordinary");
        BlockPos pos = new BlockPos(1, 64, 1);
        f.sample(pos);
        var matcher = new CubeTemplateBlockMatcher(f.level, pos, pos.offset(SIZE).offset(-1, -1, -1));
        when(f.level.findBlocksIn(any(BlockPos.class), any(BlockPos.class))).thenReturn(matcher);
        when(((CanBeCubic) f.level).cc_isCubic()).thenReturn(false);
        f.unavailable.addAll(f.cubes.keySet());
        clearInvocations(f.source);
        assertTrue(f.save(pos, SIZE, false));
        assertTrue(f.place(f.manager.get(NAME).orElseThrow(), new BlockPos(10, 64, 10), settings()));
        verify(f.level).findBlocksIn(pos, pos.offset(SIZE).offset(-1, -1, -1));
        verify(f.source, never()).cc_getCubeNow(anyInt(), anyInt(), anyInt());
    }

    @Test
    void sizesDetectedByCornersAreNotLimitedToTheEditorPacketLimit() {
        Fixture f = fixture("large_selection");
        BlockPos from = new BlockPos(31, -32000001, 31);
        Vec3i size = new Vec3i(2, 81, 2);
        f.put(from.above(80), Blocks.EMERALD_BLOCK.defaultBlockState());
        assertTrue(f.save(from, size, false));
        BlockPos to = new BlockPos(31, 32000031, 31);
        assertTrue(f.place(f.manager.get(NAME).orElseThrow(), to, settings()));
        assertTrue(f.state(to.above(80)).is(Blocks.EMERALD_BLOCK));
    }

    @Test
    void waterloggingAndNeighborShapeRepairOperateAcrossCubeEdgesAtHighY() {
        Fixture f = fixture("neighbors");
        BlockPos from = new BlockPos(1, -32000000, 1);
        BlockPos to = new BlockPos(31, 32000031, 31);
        Vec3i one = new Vec3i(1, 1, 1);
        f.put(from, Blocks.CHEST.defaultBlockState());
        assertTrue(f.save(from, one, false));
        f.put(to, Blocks.WATER.defaultBlockState());
        assertTrue(f.place(f.manager.get(NAME).orElseThrow(), to, settings()));
        assertTrue(f.state(to).getValue(ChestBlock.WATERLOGGED));
        f.put(from, Blocks.OAK_FENCE.defaultBlockState());
        assertTrue(f.save(from, one, false));
        f.put(to, Blocks.AIR.defaultBlockState());
        f.put(to.east(), Blocks.OAK_FENCE.defaultBlockState());
        assertTrue(f.place(f.manager.get(NAME).orElseThrow(), to, settings()));
        assertTrue(f.state(to).getValue(FenceBlock.EAST));
        assertTrue(f.state(to.east()).getValue(FenceBlock.WEST));
        verify(f.level).updateNeighborsAt(to, Blocks.OAK_FENCE);
        clearInvocations(f.level);
        assertTrue(f.place(f.manager.get(NAME).orElseThrow(), to, settings().setKnownShape(true)));
        assertFalse(f.state(to).getValue(FenceBlock.EAST));
        verify(f.level, never()).updateNeighborsAt(any(), any());
    }

    @Test
    void decodedSaveAndLoadPacketsUseRealTemplateManagerAndVanillaHandlersAtBothHeightSigns() throws Exception {
        for (int y : new int[] { -32000001, 32000031 }) {
            Fixture f = fixture("packets_" + y);
            BlockPos controllerPos = new BlockPos(31, y, 31);
            var controller = f.controller(controllerPos, StructureMode.SAVE);
            BlockPos sourcePos = controllerPos.offset(1, 1, 1);
            f.sample(sourcePos);
            ServerPlayer player = mock();
            when(player.level()).thenReturn(f.level);
            when(((CCServerPlayer) player).cc_getCloTrackingView()).thenReturn(CloTrackingView.cc_of(CloPos.cube(CubePos.from(controllerPos)), 5));
            ServerGamePacketListenerImpl listener = mock();
            listener.player = player;
            player.connection = listener;
            when(listener.hasClientLoaded()).thenReturn(true);
            doCallRealMethod().when(listener).handleSetStructureBlock(any());
            CubePos cubePos = CubePos.from(controllerPos);
            when(f.source.cc_getCube(cubePos.getX(), cubePos.getY(), cubePos.getZ(), ChunkStatus.FULL, false)).thenReturn(f.at(controllerPos));
            ServerPlayNetworking.Context context = mock();
            when(context.player()).thenReturn(player);
            var save = editPacket(controllerPos, StructureBlockEntity.UpdateType.SAVE_AREA, StructureMode.SAVE, new BlockPos(1, 1, 1));
            try (var ignored = mockStatic(PacketUtils.class)) {
                new CCServerboundCubeSetStructureBlockPacket.Handler().receive(save, context.player());
                assertFalse(Files.exists(f.file()));
                when(player.canUseGameMasterBlocks()).thenReturn(true);
                new CCServerboundCubeSetStructureBlockPacket.Handler().receive(save, context.player());
                assertTrue(Files.exists(f.file()));
                // Force the LOAD packet to read the saved file instead of the captured in-memory palette.
                f.manager.remove(NAME);
                var load = editPacket(controllerPos, StructureBlockEntity.UpdateType.LOAD_AREA, StructureMode.LOAD, new BlockPos(10, 10, 10));
                new CCServerboundCubeSetStructureBlockPacket.Handler().receive(load, context.player());
            }
            assertEquals(StructureMode.LOAD, controller.getMode());
            assertTrue(f.state(controllerPos.offset(10, 10, 10)).is(Blocks.STONE));
            f.assertChest(controllerPos.offset(11, 11, 11), Direction.NORTH);
        }
    }

    private static CCServerboundCubeSetStructureBlockPacket editPacket(
            BlockPos pos, StructureBlockEntity.UpdateType type, StructureMode mode, BlockPos offset
    ) {
        var packet = new CCServerboundCubeSetStructureBlockPacket(pos, type, mode, NAME.toString(), offset, SIZE, Mirror.NONE, Rotation.NONE, "",
                true, false, false, true, 1, 42);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            CCServerboundCubeSetStructureBlockPacket.STREAM_CODEC.encode(buffer, packet);
            return CCServerboundCubeSetStructureBlockPacket.STREAM_CODEC.decode(buffer);
        } finally {
            buffer.release();
        }
    }

    private Fixture fixture(String name) {
        return new Fixture(directory.resolve(name));
    }

    private static StructurePlaceSettings settings() {
        return new StructurePlaceSettings().setIgnoreEntities(true).setRandom(RandomSource.create(42));
    }

    private static final class Fixture {
        private final ServerLevel level = mockLevelWithPalettes(ServerLevel.class);
        private final ServerChunkCache cache = mock();
        private final CubeSource source = (CubeSource) cache;
        private final Map<CubePos, LevelCube> cubes = new HashMap<>();
        private final Set<CubePos> unavailable = new HashSet<>();
        private final Path directory;
        private final StructureTemplateManager manager;

        private Fixture(Path directory) {
            this.directory = directory;
            Misc.setupLevelHeight(level, -64, 384);
            when(((CanBeCubic) level).cc_isCubic()).thenReturn(true);
            when(level.getChunkSource()).thenReturn(cache);
            when(cache.getLightEngine()).thenReturn(mock());
            when(level.getRandom()).thenReturn(RandomSource.create(42));
            when(source.cc_getCubeNow(anyInt(), anyInt(), anyInt())).thenAnswer(i -> {
                CubePos pos = CubePos.of(i.getArgument(0), i.getArgument(1), i.getArgument(2));
                // Unlisted cubes model resident air; this fixture never invokes a loader or generator.
                return unavailable.contains(pos) ? null : at(pos.asBlockPos());
            });
            when(level.getBlockState(any())).thenAnswer(i -> state(i.getArgument(0)));
            when(level.getFluidState(any())).thenAnswer(i -> state(i.getArgument(0)).getFluidState());
            when(level.getBlockEntity(any(BlockPos.class))).thenAnswer(i -> at(i.getArgument(0)).getBlockEntity(i.getArgument(0)));
            when(level.setBlock(any(), any(), anyInt()))
                    .thenAnswer(i -> at(i.getArgument(0)).setBlockState(i.getArgument(0), i.getArgument(1)) != null);
            doCallRealMethod().when(level).setBlockAndUpdate(any(), any());
            manager = manager();
            when(level.getStructureTemplateManager()).thenReturn(manager);
        }

        private StructureTemplateManager manager() {
            LevelStorageAccess storage = mock();
            when(storage.getLevelPath(LevelResource.GENERATED_DIR)).thenReturn(directory);
            return new StructureTemplateManager(mock(ResourceManager.class), storage, DataFixers.getDataFixer(), BuiltInRegistries.BLOCK);
        }

        private Path file() {
            return directory.resolve("cubicchunks/structure/test_template.nbt");
        }

        private LevelCube at(BlockPos pos) {
            return cubes.computeIfAbsent(CubePos.from(pos), p -> new LevelCube(level, p));
        }

        private BlockState state(BlockPos pos) {
            return at(pos).getBlockState(pos);
        }

        private void put(BlockPos pos, BlockState state) {
            at(pos).setBlockState(pos, state);
        }

        private void fill(BlockPos pos, Vec3i size, BlockState state) {
            BlockPos.betweenClosed(pos, pos.offset(size).offset(-1, -1, -1)).forEach(p -> put(p, state));
        }

        private void sample(BlockPos pos) {
            put(pos, Blocks.STONE.defaultBlockState());
            put(pos.offset(2, 3, 4), Blocks.STRUCTURE_VOID.defaultBlockState());
            BlockPos chestPos = pos.offset(1, 1, 1);
            put(chestPos, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH));
            ((ChestBlockEntity) at(chestPos).getBlockEntity(chestPos)).setItem(0, new ItemStack(Items.DIAMOND, 17));
        }

        private void assertChest(BlockPos pos, Direction direction) {
            assertEquals(direction, state(pos).getValue(ChestBlock.FACING));
            var chest = (ChestBlockEntity) at(pos).getBlockEntity(pos);
            assertEquals(pos, chest.getBlockPos());
            assertTrue(chest.getItem(0).is(Items.DIAMOND));
            assertEquals(17, chest.getItem(0).getCount());
            verify(level).blockEntityChanged(pos);
        }

        private boolean save(BlockPos pos, Vec3i size, boolean disk) {
            return StructureBlockEntity.saveStructure(level, NAME, pos, size, true, "CubicChunks test", disk, List.of());
        }

        private boolean place(StructureTemplate template, BlockPos pos, StructurePlaceSettings settings) {
            return template.placeInWorld(level, pos, pos, settings, RandomSource.create(42), 2);
        }

        private StructureBlockEntity controller(BlockPos pos, StructureMode mode) {
            put(pos, Blocks.STRUCTURE_BLOCK.defaultBlockState().setValue(StructureBlock.MODE, mode));
            var entity = (StructureBlockEntity) at(pos).getBlockEntity(pos);
            entity.setMode(mode);
            entity.setStructureName(NAME);
            entity.setIgnoreEntities(true);
            return entity;
        }

        private void noColumnOrLoading() {
            verify(level, never()).findBlocksIn(any(BlockPos.class), any(BlockPos.class));
            verify(level, never()).getChunkAt(any());
            verify(source, never()).cc_getCube(anyInt(), anyInt(), anyInt(), any(), anyBoolean());
            verify(source, never()).cc_getCube(anyInt(), anyInt(), anyInt(), anyBoolean());
        }
    }
}
