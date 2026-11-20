package io.github.opencubicchunks.cubicchunks.test.world.level.cube;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.CubeMobTestWorld;
import io.github.opencubicchunks.cubicchunks.testutils.Misc;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.github.opencubicchunks.cubicchunks.world.level.block.entity.CubeBeaconBeam;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import io.github.opencubicchunks.cubicchunks.world.level.entity.CubeEntityGetter;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.heightmap.CubeColumnBlocks;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.heightmap.CubeColumnSnapshot;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.heightmap.CubeColumnSource;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ARGB;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

/** Native beacon tick, base validation and effects; real cube blocks, detached columns, controlled players and clock. */
class TestCubeBeaconMechanics extends BaseTest {
    @ParameterizedTest
    @ValueSource(ints = { 96, 32000, -32000, 32000000, -32000000 })
    void nativePyramidBeamColorsAndEffectsWorkAcrossCubeSeams(int y) throws Exception {
        World world = new World(y);
        world.pyramid();
        world.put(world.pos.above(), Blocks.STAINED_GLASS.red().defaultBlockState());
        world.put(world.pos.above(2), Blocks.BEDROCK.defaultBlockState());
        world.put(world.pos.above(3), Blocks.STAINED_GLASS.blue().defaultBlockState());
        world.beaconEffects(MobEffects.SPEED, MobEffects.REGENERATION);
        world.tick(160);
        assertEquals(4, world.levels());
        var beam = world.beacon.getBeamSections();
        assertEquals(3, beam.size());
        assertEquals(DyeColor.WHITE.getTextureDiffuseColor(), beam.get(0).getColor());
        assertEquals(1, beam.get(0).getHeight());
        assertEquals(DyeColor.RED.getTextureDiffuseColor(), beam.get(1).getColor());
        assertEquals(2, beam.get(1).getHeight(), "Bedrock is transparent to a beacon, despite being opaque to skylight");
        assertEquals(ARGB.average(DyeColor.RED.getTextureDiffuseColor(), DyeColor.BLUE.getTextureDiffuseColor()), beam.get(2).getColor());
        var applied = ArgumentCaptor.forClass(MobEffectInstance.class);
        verify(world.player, atLeastOnce()).addEffect(applied.capture());
        assertTrue(applied.getAllValues().stream()
                .anyMatch(e -> e.getEffect().equals(MobEffects.SPEED) && e.getAmplifier() == 0 && e.getDuration() == 340));
        assertTrue(applied.getAllValues().stream()
                .anyMatch(e -> e.getEffect().equals(MobEffects.REGENERATION) && e.getAmplifier() == 0 && e.getDuration() == 340));
        assertEquals(world.pos.getY() - 50.0, world.effectBox.minY);
        assertEquals(CubicBlockBounds.MAX + 1.0, world.effectBox.maxY);
        assertEquals(101.0, world.effectBox.getXsize());
        verify(world.level, never()).getHeight(any(Heightmap.Types.class), anyInt(), anyInt());
        verify(world.level, atLeastOnce()).sendBlockUpdated(eq(world.pos), any(), any(), eq(Block.UPDATE_CLIENTS));
        world.put(world.pos.offset(4, -4, 4), Blocks.AIR.defaultBlockState());
        world.tick(80);
        assertEquals(3, world.levels(), "Damaging the outer base layer must demote the native pyramid");
    }

    @ParameterizedTest
    @ValueSource(ints = { 96, 32000, -32000, 32000000, -32000000 })
    void nativeEffectsReachDistantPlayersThroughSparseEntityQueriesWithoutWideningBaseRadius(int y) throws Exception {
        World world = new World(y);
        world.pyramid();
        Player above = player(1, world.pos.getX() + 0.5, world.pos.getY() + 1_000_000.0, world.pos.getZ() + 0.5);
        Player outsideX = player(2, world.pos.getX() + 52.0, world.pos.getY() + 1_000_000.0, world.pos.getZ() + 0.5);
        Player outsideZ = player(3, world.pos.getX() + 0.5, world.pos.getY() + 1_000_000.0, world.pos.getZ() + 52.0);
        Player below = player(4, world.pos.getX() + 0.5, world.pos.getY() - 52.0, world.pos.getZ() + 0.5);
        Player nearBelow = player(5, world.pos.getX() + 0.5, world.pos.getY() - 49.0, world.pos.getZ() + 0.5);
        LevelEntityGetter<Entity> visible = mock();
        List<Entity> players = List.of(above, outsideX, outsideZ, below, nearBelow);
        when(visible.getAll()).thenReturn(players);
        for (Entity entity : players) {
            when(visible.get(entity.getId())).thenReturn(entity);
        }
        PersistentEntitySectionManager<Entity> manager = mock();
        when(manager.getEntityGetter()).thenReturn(new CubeEntityGetter<>(visible));
        CubeMobTestWorld.setField(ServerLevel.class, world.level, "entityManager", manager);
        var getter = ServerLevel.class.getDeclaredMethod("getEntities");
        getter.setAccessible(true);
        getter.invoke(doCallRealMethod().when(world.level));
        doCallRealMethod().when(world.level).getEntitiesOfClass(any(), any(AABB.class));
        doCallRealMethod().when(world.level).getEntitiesOfClass(any(), any(AABB.class), any());
        doCallRealMethod().when(world.level).getEntities(any(EntityTypeTest.class), any(AABB.class), any());
        doCallRealMethod().when(world.level).getEntities(any(EntityTypeTest.class), any(), any(), any());
        doCallRealMethod().when(world.level).getEntities(any(EntityTypeTest.class), any(), any(), any(), anyInt());
        world.tick(160);
        assertEquals(4, world.levels());
        verify(above, atLeastOnce()).addEffect(any());
        verify(nearBelow, atLeastOnce()).addEffect(any());
        verify(outsideX, never()).addEffect(any());
        verify(outsideZ, never()).addEffect(any());
        verify(below, never()).addEffect(any());
        verify(visible, atLeastOnce()).getAll();
        // No native section-range traversal is delegated to the packed-height entity index.
        verify(visible, never()).get(any(EntityTypeTest.class), any(AABB.class), any());
    }

    private static Player player(int id, double x, double y, double z) {
        Player player = mock();
        when(player.getId()).thenReturn(id);
        when(player.getBoundingBox()).thenReturn(new AABB(x - 0.3, y, z - 0.3, x + 0.3, y + 1.8, z + 0.3));
        return player;
    }

    @Test
    void distantOpaqueRoofBlocksWithoutWalkingTheAirGapAndRecoversAfterRemoval() throws Exception {
        World world = new World(32000);
        world.pyramid();
        BlockPos roof = world.pos.above(1_000_000);
        world.put(roof, Blocks.TINTED_GLASS.defaultBlockState());
        world.tick(160);
        assertTrue(world.beacon.getBeamSections().isEmpty());
        verify(world.player, never()).addEffect(any());
        world.put(roof, Blocks.AIR.defaultBlockState());
        world.tick(160);
        assertFalse(world.beacon.getBeamSections().isEmpty());
        assertEquals(4, world.levels());
    }

    @Test
    void missingDeepTerrainAndExplicitUnknownUpperCubeNeverGrantSky() throws Exception {
        World world = new World(-32000);
        world.pyramid();
        world.minGeneratedY = -64;
        world.maxGeneratedY = 319;
        world.tick(160);
        assertTrue(world.beacon.getBeamSections().isEmpty());
        verify(world.player, never()).addEffect(any());
        World upper = new World(32000);
        upper.pyramid();
        upper.unknown = Set.of(Coords.blockToCube(upper.pos.getY() + 1_000_000));
        upper.tick(160);
        assertTrue(upper.beacon.getBeamSections().isEmpty());
    }

    @Test
    void nativeBaseHonorsAbsoluteBottomAndOrdinaryDimensionMinimum() throws Exception {
        var update = BeaconBlockEntity.class.getDeclaredMethod("updateBase", Level.class, int.class, int.class, int.class);
        update.setAccessible(true);
        World world = new World(CubicBlockBounds.MIN + 1);
        world.pyramid();
        assertEquals(0, update.invoke(null, world.level, world.pos.getX(), world.pos.getY(), world.pos.getZ()));
        World ordinary = new World(-64);
        ordinary.pyramid();
        when(((CanBeCubic) ordinary.level).cc_isCubic()).thenReturn(false);
        assertEquals(0, update.invoke(null, ordinary.level, ordinary.pos.getX(), ordinary.pos.getY(), ordinary.pos.getZ()));
    }

    @Test
    void ordinaryBeaconUsesNativeHeightmapAndScanner() throws Exception {
        World world = new World(96);
        world.pyramid();
        when(((CanBeCubic) world.level).cc_isCubic()).thenReturn(false);
        when(world.level.getHeight(Heightmap.Types.WORLD_SURFACE, world.pos.getX(), world.pos.getZ())).thenReturn(world.pos.getY() + 1);
        world.tick(160);
        assertEquals(4, world.levels());
        assertFalse(world.beacon.getBeamSections().isEmpty());
        assertEquals(world.pos.getY() - 50.0, world.effectBox.minY);
        assertEquals(world.pos.getY() + 1 + 50.0 + 384, world.effectBox.maxY);
        assertEquals(101.0, world.effectBox.getXsize());
        verify(world.columns, never()).cc_column(anyInt(), anyInt());
        verify(world.level, atLeastOnce()).getHeight(Heightmap.Types.WORLD_SURFACE, world.pos.getX(), world.pos.getZ());
        assertFalse(world.beacon.getUpdateTag(world.level.registryAccess()).contains("CubicBeam"));
    }

    @Test
    void cubicClientRendersServerBeamTagWithoutScanningUnavailableColumn() throws Exception {
        World world = new World(32000000);
        world.pyramid();
        world.put(world.pos.above(), Blocks.STAINED_GLASS.green().defaultBlockState());
        world.tick(160);
        CompoundTag packet = world.beacon.getUpdateTag(world.level.registryAccess());
        World client = new World(32000000);
        when(client.level.isClientSide()).thenReturn(true);
        client.beacon.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, client.level.registryAccess(), packet));
        client.tick(10);
        assertEquals(4, client.levels());
        assertArrayEquals(CubeBeaconBeam.encode(world.beacon.getBeamSections()), CubeBeaconBeam.encode(client.beacon.getBeamSections()));
        verify(client.columns, never()).cc_column(anyInt(), anyInt());
        assertFalse(world.beacon.saveCustomOnly(world.level.registryAccess()).contains("CubicBeam"),
                "Derived beam state is transported, not trusted after disk reload");
    }

    @Test
    void beamTransportRejectsInvalidLengthsAndHeights() {
        assertTrue(CubeBeaconBeam.decode(new int[] { 1 }).isEmpty());
        assertTrue(CubeBeaconBeam.decode(new int[] { 1, -1 }).isEmpty());
        assertTrue(CubeBeaconBeam.decode(new int[] { 1, Integer.MAX_VALUE }).isEmpty());
    }

    private static final class World {
        final ServerLevel level = Misc.mockLevelWithPalettes(ServerLevel.class);
        final Map<CubePos, LevelCube> cubes = new HashMap<>();
        final BlockPos pos;
        final BeaconBlockEntity beacon;
        final Player player = mock();
        final CubeColumnSource columns;
        int minGeneratedY;
        int maxGeneratedY;
        Set<Integer> unknown = Set.of();
        long time;
        AABB effectBox;

        World(int y) throws Exception {
            pos = new BlockPos(31, y - 1, 31);
            minGeneratedY = pos.getY() - 32;
            maxGeneratedY = pos.getY() + 32;
            Misc.setupLevelHeight(level, -64, 384);
            when(((CanBeCubic) level).cc_isCubic()).thenReturn(true);
            when(level.getGameTime()).thenAnswer(call -> time);
            when(level.getBlockState(any())).thenAnswer(call -> get(call.getArgument(0)));
            when(level.getEntitiesOfClass(eq(ServerPlayer.class), any())).thenReturn(List.of());
            when(level.getEntitiesOfClass(eq(Player.class), any())).thenAnswer(call -> {
                effectBox = call.getArgument(1);
                return List.of(player);
            });
            var source = level.getChunkSource();
            var map = source.chunkMap;
            if (map == null) {
                map = mock(org.mockito.Mockito.RETURNS_DEEP_STUBS);
                CubeMobTestWorld.setField(net.minecraft.server.level.ServerChunkCache.class, source, "chunkMap", map);
            }
            columns = (CubeColumnSource) map;
            when(columns.cc_column(anyInt(), anyInt())).thenAnswer(call -> snapshot());
            put(pos, Blocks.BEACON.defaultBlockState());
            // The synthetic authoritative band is deliberately relocated to test beam mechanics at all five heights.
            cube(pos.above(32));
            beacon = new BeaconBlockEntity(pos, Blocks.BEACON.defaultBlockState());
            beacon.setLevel(level);
            beaconEffects(MobEffects.SPEED, MobEffects.SPEED);
        }

        void beaconEffects(
                net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> primary,
                net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> secondary
        ) throws Exception {
            CubeMobTestWorld.setField(BeaconBlockEntity.class, beacon, "primaryPower", primary);
            CubeMobTestWorld.setField(BeaconBlockEntity.class, beacon, "secondaryPower", secondary);
        }

        void pyramid() {
            for (int depth = 1; depth <= 4; depth++) {
                for (int x = -depth; x <= depth; x++) {
                    for (int z = -depth; z <= depth; z++) {
                        BlockPos base = pos.offset(x, -depth, z);
                        if (CubicBlockBounds.contains(base)) {
                            put(base, Blocks.IRON_BLOCK.defaultBlockState());
                        }
                    }
                }
            }
        }

        void tick(int count) {
            for (int i = 0; i < count; i++) {
                time++;
                BeaconBlockEntity.tick(level, pos, Blocks.BEACON.defaultBlockState(), beacon);
            }
        }

        int levels() {
            return beacon.saveCustomOnly(level.registryAccess()).getIntOr("Levels", -1);
        }

        CubeColumnSnapshot snapshot() {
            TreeMap<Integer, CubeColumnBlocks> column = new TreeMap<>();
            cubes.forEach((key, cube) -> {
                if (key.getX() == Coords.blockToCube(pos.getX()) && key.getZ() == Coords.blockToCube(pos.getZ())) {
                    column.put(key.getY(), CubeColumnBlocks.capture(cube));
                }
            });
            return new CubeColumnSnapshot(Coords.blockToCube(pos.getX()), Coords.blockToCube(pos.getZ()), minGeneratedY, maxGeneratedY, column,
                    unknown);
        }

        LevelCube cube(BlockPos pos) {
            return cubes.computeIfAbsent(CubePos.from(pos), key -> new LevelCube(level, key));
        }

        BlockState get(BlockPos pos) {
            LevelCube cube = cubes.get(CubePos.from(pos));
            return cube == null ? Blocks.AIR.defaultBlockState() : cube.getBlockState(pos);
        }

        void put(BlockPos pos, BlockState state) {
            cube(pos).getSection(Coords.blockToIndex(pos)).setBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15, state);
        }
    }
}
