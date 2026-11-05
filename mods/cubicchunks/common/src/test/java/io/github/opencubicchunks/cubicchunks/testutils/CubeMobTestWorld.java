package io.github.opencubicchunks.cubicchunks.testutils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.server.level.CubicServerLevel;
import io.github.opencubicchunks.cubicchunks.world.level.CubicLevelReader;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeSource;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import io.github.opencubicchunks.cubicchunks.world.level.entity.CubeMobBlockView;
import io.github.opencubicchunks.cubicchunks.world.level.entity.CubicEntityManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.entity.EntityPersistentStorage;
import net.minecraft.world.level.entity.EntityTickList;
import net.minecraft.world.level.entity.LevelCallback;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.AABB;

/** Real cubes, manager callbacks, tick list, and per-entity world tick body; unrelated world services remain mocked. */
public final class CubeMobTestWorld implements AutoCloseable {
    public final ServerLevel level = Misc.mockLevelWithPalettes(ServerLevel.class);
    public final Map<CubePos, LevelCube> cubes = new HashMap<>();
    public final EntityTickList ticks = new EntityTickList();
    public final PersistentEntitySectionManager<Entity> manager;
    public final TickRateManager rate = mock();
    public final DistanceManager distance;
    private final Method tickBody;
    private int id;

    public CubeMobTestWorld(Path directory) throws Exception {
        Misc.setupLevelHeight(level, -64, 384);
        when(((CanBeCubic) level).cc_isCubic()).thenReturn(true);
        setField(Level.class, level, "cc_isCubic", true);
        doCallRealMethod().when(level).isInsideBuildHeight(anyInt());
        doCallRealMethod().when(level).isOutsideBuildHeight(anyInt());
        setField(Level.class, level, "random", RandomSource.create(1));
        when(level.getRandom()).thenReturn(RandomSource.create(1));
        when(level.enabledFeatures()).thenReturn(FeatureFlags.DEFAULT_FLAGS);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(level.getNextEntityId()).thenAnswer(i -> ++id);
        when(level.getGameRules()).thenReturn(new GameRules(FeatureFlags.DEFAULT_FLAGS));
        when(level.getWorldBorder()).thenReturn(new WorldBorder());
        var source = level.getChunkSource();
        var map = source.chunkMap;
        // Mockito does not initialize native fields.
        if (map == null) {
            map = mock(org.mockito.Mockito.RETURNS_DEEP_STUBS);
            setField(net.minecraft.server.level.ServerChunkCache.class, source, "chunkMap", map);
        }
        distance = map.getDistanceManager();
        when(distance.inEntityTickingRange(org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);
        setField(ServerLevel.class, level, "chunkSource", source);
        setField(ServerLevel.class, level, "entityTickList", ticks);
        setField(ServerLevel.class, level, "navigatingMobs", new HashSet<>());
        setField(ServerLevel.class, level, "debugSynchronizers", level.debugSynchronizers());
        var callbackType = Class.forName("net.minecraft.server.level.ServerLevel$EntityCallbacks");
        var constructor = callbackType.getDeclaredConstructor(ServerLevel.class);
        constructor.setAccessible(true);
        LevelCallback<Entity> callbacks = (LevelCallback<Entity>) constructor.newInstance(level);
        manager = new PersistentEntitySectionManager<>(Entity.class, callbacks, mock(EntityPersistentStorage.class));
        setField(ServerLevel.class, level, "entityManager", manager);
        ((CubicEntityManager) manager).cc_configureEntityStorage(level, directory);
        doCallRealMethod().when((CubicServerLevel) level).cc_onCubeEntityStatus(any(), any());
        doCallRealMethod().when(level).isPositionEntityTicking(any());
        doCallRealMethod().when(level).tickNonPassenger(any());
        doCallRealMethod().when(level).guardEntityTick(any(), any());
        tickBody = ServerLevel.class.getDeclaredMethod("lambda$tick$0", TickRateManager.class, ProfilerFiller.class, Entity.class);
        tickBody.setAccessible(true);
        when(((CubeSource) source).cc_getCubeNow(anyInt(), anyInt(), anyInt()))
                .thenAnswer(i -> cubes.get(CubePos.of(i.getArgument(0), i.getArgument(1), i.getArgument(2))));
        when(((CubicLevelReader) level).cc_hasCube(anyInt(), anyInt(), anyInt()))
                .thenAnswer(i -> cubes.containsKey(CubePos.of(i.getArgument(0), i.getArgument(1), i.getArgument(2))));
        doCallRealMethod().when((CubicLevelReader) level).cc_hasCubeAt(any());
        doCallRealMethod().when((CubicLevelReader) level).cc_hasCubeAt(anyInt(), anyInt(), anyInt());
        doCallRealMethod().when((CubicLevelReader) level).cc_hasCubesAt(anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt());
        doCallRealMethod().when(level).hasChunkAt(any(BlockPos.class));
        doCallRealMethod().when(level).hasChunksAt(anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt());
        CubeMobBlockView blocks = new CubeMobBlockView(level);
        when(level.getBlockState(any())).thenAnswer(i -> blocks.getBlockState(i.getArgument(0)));
        when(level.getFluidState(any())).thenAnswer(i -> blocks.getFluidState(i.getArgument(0)));
        doCallRealMethod().when(level).getChunkForCollisions(anyInt(), anyInt());
        doCallRealMethod().when((CubicLevelReader) level).cc_getCubeForCollisions(anyInt(), anyInt(), anyInt());
        doCallRealMethod().when(level).getBlockCollisions(any(), any());
        doCallRealMethod().when(level).getBlockCollisionsFromContext(any(), any());
        doCallRealMethod().when(level).noCollision(any(Entity.class), any(AABB.class));
        doCallRealMethod().when(level).noCollision(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        doCallRealMethod().when(level).noBlockCollision(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        doCallRealMethod().when(level).noEntityCollision(any(), any());
        doCallRealMethod().when(level).noBorderCollision(any(), any());
        doCallRealMethod().when(level).findSupportingBlock(any(), any());
        when(level.getEntityCollisions(any(), any())).thenReturn(List.of());
        when(level.getEntities(any(Entity.class), any(AABB.class), any())).thenReturn(List.of());
    }

    public static void setField(Class<?> type, Object object, String name, Object value) throws Exception {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    public LevelCube cube(BlockPos pos) {
        return cubes.computeIfAbsent(CubePos.from(pos), key -> {
            LevelCube cube = new LevelCube(level, key);
            cube.setFullStatus(() -> FullChunkStatus.ENTITY_TICKING);
            return cube;
        });
    }

    public void block(BlockPos pos, BlockState state) {
        cube(pos).setBlockState(pos, state, 0);
    }

    public void floor(int y) {
        for (int x = -4; x <= 40; x++) {
            for (int z = -4; z <= 8; z++) {
                block(new BlockPos(x, y - 1, z), Blocks.STONE.defaultBlockState());
                cube(new BlockPos(x, y + 3, z));
            }
        }
    }

    public Mob pig(int y) {
        Mob mob = EntityTypes.PIG.create(level, EntitySpawnReason.LOAD);
        mob.snapTo(2.5, y, 2.5);
        mob.setOnGround(true);
        mob.setPersistenceRequired();
        mob.getGoalSelector().removeAllGoals(goal -> true);
        return mob;
    }

    public void status(Entity entity, FullChunkStatus status) {
        ((CubicServerLevel) level).cc_onCubeEntityStatus(CubePos.from(entity.blockPosition()), status);
    }

    public void tick() {
        ticks.forEach(entity -> {
            try {
                tickBody.invoke(level, rate, Profiler.get(), entity);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e.getCause());
            }
        });
    }

    @Override public void close() throws IOException {
        manager.close();
    }
}
