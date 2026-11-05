package io.github.opencubicchunks.cubicchunks.testutils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cubicchunks.server.level.CubicServerLevel;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightProvider;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.storage.LevelData.RespawnData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Registry-backed mobs, cubes, insertion and callbacks; controlled players, clock, and light publications. */
public final class CubeSpawnTestWorld implements AutoCloseable {
    public final CubeMobTestWorld world;
    public final ServerLevel level;
    public final net.minecraft.world.entity.ai.village.poi.PoiManager poi;
    public final List<ServerPlayer> players = new ArrayList<>();
    public final Map<CubePos, CubeLightView> lights = new HashMap<>();

    public CubeSpawnTestWorld(Path path) throws Exception {
        world = new CubeMobTestWorld(path);
        level = world.level;
        poi = PoiTestSupport.attach(level, path.resolve("points"));
        when(level.players()).thenReturn(players);
        when(level.getAllEntities()).thenAnswer(i -> world.manager.getEntityGetter().getAll());
        when(level.tickRateManager().runsNormally()).thenReturn(true);
        when(level.getRespawnData()).thenReturn(RespawnData.of(Level.OVERWORLD, BlockPos.ZERO, 0, 0));
        when(level.getDifficulty()).thenReturn(Difficulty.NORMAL);
        when(level.getCurrentDifficultyAt(any())).thenReturn(new DifficultyInstance(Difficulty.NORMAL, 0, 0, 0));
        var dimension = level.registryAccess().lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(BuiltinDimensionTypes.OVERWORLD).value();
        when(level.dimensionType()).thenReturn(dimension);
        var plains = level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
        doReturn(plains).when(level).getUncachedNoiseBiome(anyInt(), anyInt(), anyInt());
        when(level.getBiomeManager()).thenReturn(new BiomeManager(level::getNoiseBiome, 1));
        doCallRealMethod().when(level).getNoiseBiome(anyInt(), anyInt(), anyInt());
        doCallRealMethod().when(level).getBiome(any());
        doCallRealMethod().when(level).getBlockState(any());
        doCallRealMethod().when(level).getFluidState(any());
        doCallRealMethod().when(level).getBrightness(any(), any());
        doCallRealMethod().when(level).getRawBrightness(any(), anyInt());
        doCallRealMethod().when(level).getMaxLocalRawBrightness(any());
        doCallRealMethod().when(level).getMaxLocalRawBrightness(any(), anyInt());
        doCallRealMethod().when(level).noCollision(any(AABB.class));
        doCallRealMethod().when(level).noCollision(org.mockito.ArgumentMatchers.nullable(net.minecraft.world.entity.Entity.class), any(AABB.class));
        doCallRealMethod().when(level).isUnobstructed(any(), any());
        doCallRealMethod().when(level).isUnobstructed(any(net.minecraft.world.entity.Entity.class));
        doCallRealMethod().when(level).containsAnyLiquid(any());
        doCallRealMethod().when(level).findBlocksIn(any(AABB.class));
        doCallRealMethod().when(level).addFreshEntity(any());
        doCallRealMethod().when(level).addFreshEntityWithPassengers(any());
        doCallRealMethod().when(level).tryAddFreshEntityWithPassengers(any());
        when(((CubeLightProvider) level.getChunkSource()).cc_getCubeLightView(any())).thenAnswer(i -> lights.get(i.getArgument(0)));
        settings(new MobSpawnSettings.Builder().addSpawn(EntityTypes.PIG, 1, 2, 4).build());
    }

    public void settings(MobSpawnSettings settings) {
        when(level.environmentAttributes()).thenReturn(
                EnvironmentAttributeSystem.builder().addConstantLayer(EnvironmentAttributes.NATURAL_MOB_SPAWNS, previous -> settings).build());
    }

    public ServerPlayer player(double x, double y, double z) {
        ServerPlayer player = mock();
        when(player.level()).thenReturn(level);
        when(player.position()).thenReturn(new Vec3(x, y, z));
        players.add(player);
        return player;
    }

    public void platform(int y, int light) {
        for (int x = 0; x < 32; x++) {
            for (int z = 0; z < 32; z++) {
                world.block(new BlockPos(x, y - 1, z), Blocks.GRASS_BLOCK.defaultBlockState());
            }
        }
        world.cube(new BlockPos(16, y + 2, 16));
        for (var cube : world.cubes.values()) {
            CubePos pos = cube.cc_getCubePos();
            ((CubicServerLevel) level).cc_onCubeEntityStatus(pos, FullChunkStatus.ENTITY_TICKING);
            byte[][] block = new byte[CubicConstants.SECTION_COUNT][DataLayer.SIZE];
            for (byte[] section : block) {
                Arrays.fill(section, (byte) (light | light << 4));
            }
            lights.put(pos, CubeLightView.fromExact(pos, block, new byte[CubicConstants.SECTION_COUNT][DataLayer.SIZE]));
        }
    }

    @Override public void close() throws Exception {
        try (var closePoi = poi) {
            world.close();
        }
    }
}
