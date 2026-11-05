package io.github.opencubicchunks.cubicchunks.testutils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;

import io.github.opencubicchunks.cc_core.api.CubePos;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.level.block.state.BlockState;

/** Native villagers, brains, POI and cube blocks; controlled clock, schedule, and world write notifications. */
public final class CubeVillagerTestWorld implements AutoCloseable {
    public final CubeMobTestWorld world;
    public final ServerLevel level;
    public final PoiManager poi;

    public CubeVillagerTestWorld(Path path) throws Exception {
        world = new CubeMobTestWorld(path);
        level = world.level;
        poi = PoiTestSupport.attach(level, path.resolve("points"));
        when(level.getServer().getLevel(level.dimension())).thenReturn(level);
        when(level.getRandom()).thenReturn(mock(RandomSource.class));
        when(level.setBlock(any(), any(), anyInt())).thenAnswer(i -> write(i.getArgument(0), i.getArgument(1)));
        when(level.setBlockAndUpdate(any(), any())).thenAnswer(i -> write(i.getArgument(0), i.getArgument(1)));
        when(level.getBlockEntity(any())).thenAnswer(i -> {
            BlockPos pos = i.getArgument(0);
            var cube = world.cubes.get(CubePos.from(pos));
            return cube == null ? null : cube.getBlockEntity(pos);
        });
        doCallRealMethod().when(level).getBlockFloorHeight(any(), any());
        schedule(Activity.IDLE);
    }

    private boolean write(BlockPos pos, BlockState state) {
        var cube = world.cubes.get(CubePos.from(pos));
        return cube != null && cube.setBlockState(pos, state, 0) != null;
    }

    public Villager villager(int y) {
        Villager villager = EntityTypes.VILLAGER.create(level, EntitySpawnReason.LOAD);
        villager.snapTo(2.5, y, 2.5);
        villager.setOnGround(true);
        villager.setVillagerData(villager.getVillagerData().withProfession(level.registryAccess(), VillagerProfession.FARMER));
        villager.refreshBrain(level);
        return villager;
    }

    public void time(long time) {
        when(level.getGameTime()).thenReturn(time);
    }

    public void schedule(Activity activity) {
        when(level.environmentAttributes()).thenReturn(
                EnvironmentAttributeSystem.builder().addConstantLayer(EnvironmentAttributes.VILLAGER_ACTIVITY, previous -> activity).build());
    }

    @Override public void close() throws Exception {
        try (var closePoi = poi) {
            world.close();
        }
    }
}
