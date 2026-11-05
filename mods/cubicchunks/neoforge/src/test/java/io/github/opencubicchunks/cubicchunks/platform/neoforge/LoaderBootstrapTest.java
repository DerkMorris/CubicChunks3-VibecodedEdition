package io.github.opencubicchunks.cubicchunks.platform.neoforge;

import static org.junit.jupiter.api.Assertions.*;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.network.CubeEntityDataSerializers;
import io.github.opencubicchunks.cubicchunks.platform.Platform;
import io.github.opencubicchunks.cubicchunks.world.level.cube.ProtoCube;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.UpgradeData;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.neoforged.testframework.junit.EphemeralTestServerProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(EphemeralTestServerProvider.class)
class LoaderBootstrapTest {
    @Test
    void generatesAndSavesARealCubicWorldWithoutAClient(MinecraftServer server) throws Exception {
        server.submit(() -> {
            var data = (io.github.opencubicchunks.cubicchunks.world.level.CubicWorldData) server.getWorldData();
            data.cc_setCubicWorld(true);
            try {
                var create = MinecraftServer.class.getDeclaredMethod("createLevels");
                create.setAccessible(true);
                create.invoke(server);
                var level = server.overworld();
                assertTrue(((CanBeCubic) level).cc_isCubic());
                var cubic = (io.github.opencubicchunks.cubicchunks.world.level.CubicLevel) level;
                for (int y : new int[] { -32_000_000, 32_000_000 }) {
                    var pos = new BlockPos(1, y, 1);
                    var cube = cubic.cc_getCubeAt(pos);
                    assertNotNull(cube);
                    assertTrue(level.setBlockAndUpdate(pos, Blocks.GOLD_BLOCK.defaultBlockState()));
                    assertEquals(Blocks.GOLD_BLOCK.defaultBlockState(), level.getBlockState(pos));
                    var chest = pos.above();
                    level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
                    assertInstanceOf(net.minecraft.world.level.block.entity.ChestBlockEntity.class, level.getBlockEntity(chest));
                    level.setBlockAndUpdate(chest, Blocks.AIR.defaultBlockState());
                    assertNull(level.getBlockEntity(chest));
                }
                level.getChunkSource().save(true);
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError("Native world bootstrap failed", exception);
            } finally {
                // The ephemeral provider normally creates no levels; close ours before it deletes its temporary directory.
                var levels = server.getAllLevels().iterator();
                while (levels.hasNext()) {
                    var level = levels.next();
                    levels.remove();
                    try {
                        level.close();
                    } catch (java.io.IOException exception) {
                        throw new java.io.UncheckedIOException(exception);
                    }
                }
                data.cc_setCubicWorld(false);
            }
        }).get(180, java.util.concurrent.TimeUnit.SECONDS);
    }

    @Test
    void loadsTheModAndTransformsCubesOnNeoForge(MinecraftServer server) {
        assertTrue(ModList.get().isLoaded("cubicchunks"));
        assertTrue(CanBeCubic.class.isAssignableFrom(Level.class));
        assertInstanceOf(NeoForgePlatform.class, Platform.get());
        assertSame(CubeEntityDataSerializers.OPTIONAL_POSITION,
                NeoForgeRegistries.ENTITY_DATA_SERIALIZERS.getValue(Identifier.parse("cubicchunks:optional_cube_position")));
        for (int y : new int[] { -32_000_000, 32_000_000 }) {
            var pos = new BlockPos(1, y, 1);
            var cube = new ProtoCube(CubePos.from(pos), UpgradeData.EMPTY, LevelHeightAccessor.create(-64, 384),
                    PalettedContainerFactory.create(server.registryAccess()), null);
            cube.setBlockState(pos, Blocks.STONE.defaultBlockState());
            assertEquals(Blocks.STONE.defaultBlockState(), cube.getBlockState(pos));
            assertNotNull(cube.getBlockTicks());
            assertNotNull(cube.getFluidTicks());
        }
    }
}
