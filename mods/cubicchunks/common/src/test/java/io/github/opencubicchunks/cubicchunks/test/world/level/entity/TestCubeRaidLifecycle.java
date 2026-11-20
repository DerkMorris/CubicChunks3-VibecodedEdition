package io.github.opencubicchunks.cubicchunks.test.world.level.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.CubeMobTestWorld;
import io.github.opencubicchunks.cubicchunks.testutils.CubeVillagerTestWorld;
import io.github.opencubicchunks.cubicchunks.world.level.entity.CubeEntityData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.entity.raid.Raids;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Native raid state machine and POI center selection over cube blocks; the clock and server services are controlled. */
class TestCubeRaidLifecycle extends BaseTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(ints = { 96, 32000, -32000, 32000000, -32000000 })
    void wavesUseLocalLoadedSurfaceEvenWhenColumnSurfaceIsFarAway(int y) throws Exception {
        try (var f = new CubeVillagerTestWorld(directory)) {
            setup(f, y);
            Raid raid = new Raid(new BlockPos(0, y, 0), Difficulty.NORMAL);
            CubeMobTestWorld.setField(Raid.class, raid, "random", mock(RandomSource.class));
            CubeMobTestWorld.setField(Raid.class, raid, "raidCooldownTicks", 0);
            when(f.level.getHeight(any(), anyInt(), anyInt())).thenReturn(y + 256);
            BlockPos selected = (BlockPos) invoke(raid, "findRandomSpawnPos", new Class<?>[] { ServerLevel.class, int.class }, f.level, 1);
            assertNotNull(selected, "A nearby underground village needs a local wave surface, not the top of the entire column");
            assertEquals(y, selected.getY());
            raid.tick(f.level);
            assertEquals(1, raid.getGroupsSpawned());
            assertTrue(raid.isStarted());
            assertTrue(raid.getTotalRaidersAlive() >= 4);
            assertNotNull(raid.getLeader(1));
            for (Raider raider : raid.getAllRaiders()) {
                assertSame(raid, raider.getCurrentRaid());
                assertEquals(1, raider.getWave());
                assertEquals(y + 1, raider.getY());
            }
            f.world.cubes.clear();
            assertNull(invoke(raid, "findRandomSpawnPos", new Class<?>[] { ServerLevel.class, int.class }, f.level, 1),
                    "Unloaded terrain must not be force loaded or treated as a valid wave surface");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 96, 32000, -32000, 32000000, -32000000 })
    void occupiedVillageCenterAndRaidStateSurviveNativeCodecAndVerticalSeparation(int y) throws Exception {
        try (var f = new CubeVillagerTestWorld(directory)) {
            setup(f, y);
            var homeType = f.level.registryAccess().lookupOrThrow(Registries.POINT_OF_INTEREST_TYPE).getOrThrow(PoiTypes.HOME);
            for (BlockPos home : List.of(new BlockPos(0, y, 0), new BlockPos(8, y + 2, 0), new BlockPos(0, y + 512, 0))) {
                f.poi.add(home, homeType);
                assertTrue(f.poi.take(type -> true, (type, pos) -> pos.equals(home), home, 0).isPresent());
            }
            Raids raids = f.level.getRaids();
            ServerPlayer player = mock(ServerPlayer.class);
            when(player.level()).thenReturn(f.level);
            Raid raid = raids.createOrExtendRaid(player, new BlockPos(0, y, 0));
            assertNotNull(raid);
            assertEquals(new BlockPos(4, y + 1, 0), raid.getCenter());
            int id = raids.getId(raid).orElseThrow();
            raid.setRaidOmenLevel(3);
            CubeMobTestWorld.setField(Raid.class, raid, "started", true);
            CubeMobTestWorld.setField(Raid.class, raid, "groupsSpawned", 2);
            CubeMobTestWorld.setField(Raid.class, raid, "raidCooldownTicks", 123);
            Raider participant = EntityTypes.PILLAGER.create(f.level, EntitySpawnReason.LOAD);
            assertNotNull(participant);
            participant.snapTo(4.5, y + 1, 0.5);
            raid.joinRaid(f.level, 2, participant, null, true);
            CubePos participantCube = CubePos.from(participant.blockPosition());
            CompoundTag savedParticipant = CubeEntityData.save(participantCube, List.of(participant));
            CompoundTag saved = (CompoundTag) Raids.CODEC.encodeStart(NbtOps.INSTANCE, raids).getOrThrow();
            Raids loaded = Raids.load(saved);
            Raid restored = loaded.get(id);
            assertNotNull(restored);
            assertEquals(raid.getCenter(), restored.getCenter());
            assertTrue(restored.isStarted());
            assertEquals(2, restored.getGroupsSpawned());
            assertEquals(3, restored.getRaidOmenLevel());
            assertEquals(saved, Raids.CODEC.encodeStart(NbtOps.INSTANCE, loaded).getOrThrow());
            assertSame(restored, loaded.getNearbyRaid(raid.getCenter(), Raid.VALID_RAID_RADIUS_SQR));
            assertNull(loaded.getNearbyRaid(raid.getCenter().above(512), Raid.VALID_RAID_RADIUS_SQR));
            when(f.level.getRaids()).thenReturn(loaded);
            Raider restoredParticipant = (Raider) CubeEntityData.load(f.level, participantCube, savedParticipant).getFirst();
            assertSame(restored, restoredParticipant.getCurrentRaid());
            assertEquals(2, restoredParticipant.getWave());
            assertEquals(1, restored.getTotalRaidersAlive());
            assertEquals(raid.getTotalHealth(), restored.getTotalHealth(), "Loading participants must not double the saved wave health");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 96, 32000, -32000, 32000000, -32000000 })
    void participantsVictoryLossAndInactivePauseFollowNativeLifecycle(int y) throws Exception {
        try (var f = new CubeVillagerTestWorld(directory)) {
            setup(f, y);
            Raid raid = new Raid(new BlockPos(0, y, 0), Difficulty.EASY);
            CubeMobTestWorld.setField(Raid.class, raid, "started", true);
            CubeMobTestWorld.setField(Raid.class, raid, "groupsSpawned", raid.getNumGroups(Difficulty.EASY));
            Raider raider = EntityTypes.PILLAGER.create(f.level, EntitySpawnReason.LOAD);
            assertNotNull(raider);
            raider.snapTo(0, y, 0);
            raid.joinRaid(f.level, 3, raider, null, true);
            assertEquals(1, raid.getTotalRaidersAlive());
            raid.tick(f.level);
            assertFalse(raid.isVictory());
            raid.removeFromRaid(f.level, raider, false);
            assertNull(raider.getCurrentRaid());
            when(f.level.hasChunkAt(raid.getCenter())).thenReturn(false);
            for (int i = 0; i < 50; i++) {
                raid.tick(f.level);
            }
            assertFalse(raid.isActive());
            assertFalse(raid.isVictory());
            when(f.level.hasChunkAt(raid.getCenter())).thenReturn(true);
            for (int i = 0; i < 41; i++) {
                raid.tick(f.level);
            }
            assertTrue(raid.isVictory());
            for (int i = 0; i < 600; i++) {
                raid.tick(f.level);
            }
            assertTrue(raid.isStopped());

            Raid loss = new Raid(new BlockPos(0, y, 0), Difficulty.EASY);
            CubeMobTestWorld.setField(Raid.class, loss, "groupsSpawned", 1);
            when(f.level.isVillage(any(BlockPos.class))).thenReturn(false);
            when(f.level.isVillage(any(SectionPos.class))).thenReturn(false);
            loss.tick(f.level);
            assertTrue(loss.isLoss());
            Raid peaceful = new Raid(new BlockPos(0, y, 0), Difficulty.NORMAL);
            when(f.level.getDifficulty()).thenReturn(Difficulty.PEACEFUL);
            peaceful.tick(f.level);
            assertTrue(peaceful.isStopped());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 64, 96 })
    void vanillaWaveHeightStillUsesColumnSurface(int y) throws Exception {
        try (var f = new CubeVillagerTestWorld(directory)) {
            setup(f, y);
            when(((CanBeCubic) f.level).cc_isCubic()).thenReturn(false);
            when(f.level.getHeight(any(), anyInt(), anyInt())).thenReturn(y);
            when(f.level.hasChunksAt(anyInt(), anyInt(), anyInt(), anyInt())).thenReturn(true);
            Raid raid = new Raid(new BlockPos(0, y, 0), Difficulty.NORMAL);
            CubeMobTestWorld.setField(Raid.class, raid, "random", mock(RandomSource.class));
            CubeMobTestWorld.setField(Raid.class, raid, "raidCooldownTicks", 0);
            assertNotNull(invoke(raid, "findRandomSpawnPos", new Class<?>[] { ServerLevel.class, int.class }, f.level, 1));
            verify(f.level).getHeight(Heightmap.Types.WORLD_SURFACE, -8, 0);
        }
    }

    private static void setup(CubeVillagerTestWorld f, int y) {
        when(f.level.getDifficulty()).thenReturn(Difficulty.NORMAL);
        when(f.level.getCurrentDifficultyAt(any())).thenReturn(new DifficultyInstance(Difficulty.NORMAL, 0, 0, 0));
        when(f.level.getRaids()).thenReturn(new Raids());
        when(f.level.getRaidAt(any())).thenAnswer(i -> f.level.getRaids().getNearbyRaid(i.getArgument(0), Raid.VALID_RAID_RADIUS_SQR));
        when(f.level.isVillage(any(BlockPos.class))).thenReturn(true);
        doReturn(true).when(f.level).isPositionEntityTicking(any());
        when(f.level.environmentAttributes())
                .thenReturn(EnvironmentAttributeSystem.builder().addConstantLayer(EnvironmentAttributes.CAN_START_RAID, previous -> true).build());
        for (int x = -20; x <= 20; x++) {
            for (int z = -12; z <= 12; z++) {
                f.world.block(new BlockPos(x, y - 1, z), Blocks.STONE.defaultBlockState());
                f.world.cube(new BlockPos(x, y + 3, z));
            }
        }
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(target, args);
    }
}
