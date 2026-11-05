package io.github.opencubicchunks.cubicchunks.testutils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.nio.file.Files;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.mockito.Answers;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

// TODO maybe split things off of this class instead of dumping everything in here?
public class Misc {
    private Misc() {}

    /** A mocked level must keep all derived height/section methods consistent with its build limits. */
    public static void setupLevelHeight(Level level, int minY, int height) {
        LevelHeightAccessor bounds = LevelHeightAccessor.create(minY, height);
        when(level.getHeight()).thenReturn(bounds.getHeight());
        when(level.getMinY()).thenReturn(bounds.getMinY());
        when(level.getMaxY()).thenReturn(bounds.getMaxY());
        when(level.getSectionsCount()).thenReturn(bounds.getSectionsCount());
        when(level.getMinSectionY()).thenReturn(bounds.getMinSectionY());
        when(level.getMaxSectionY()).thenReturn(bounds.getMaxSectionY());
        when(level.getSectionIndex(anyInt())).thenAnswer(i -> bounds.getSectionIndex(i.getArgument(0)));
        when(level.getSectionIndexFromSectionY(anyInt())).thenAnswer(i -> bounds.getSectionIndexFromSectionY(i.getArgument(0)));
        when(level.getSectionYFromSectionIndex(anyInt())).thenAnswer(i -> bounds.getSectionYFromSectionIndex(i.getArgument(0)));
        when(level.isInsideBuildHeight(anyInt())).thenAnswer(i -> bounds.isInsideBuildHeight((int) i.getArgument(0)));
        when(level.isOutsideBuildHeight(anyInt())).thenAnswer(i -> bounds.isOutsideBuildHeight((int) i.getArgument(0)));
        when(level.isInsideBuildHeight(any(BlockPos.class))).thenAnswer(i -> bounds.isInsideBuildHeight((BlockPos) i.getArgument(0)));
        when(level.isOutsideBuildHeight(any(BlockPos.class))).thenAnswer(i -> bounds.isOutsideBuildHeight((BlockPos) i.getArgument(0)));
    }

    public static int chebyshevDistance(Vec3i a, Vec3i b) {
        return Math.max(Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getY() - b.getY())), Math.abs(a.getZ() - b.getZ()));
    }

    public static int chebyshevDistance(ChunkPos a, ChunkPos b) {
        return Math.max(Math.abs(a.x() - b.x()), Math.abs(a.z() - b.z()));
    }

    /** Keep world services mocked, but use real registry-backed storage for cube block and biome assertions. */
    public static <T extends Level> T mockLevelWithPalettes(Class<T> type) {
        T level = mock(type, RETURNS_DEEP_STUBS);
        var registries = VanillaRegistriesExtension.shared();
        when(level.registryAccess()).thenReturn(registries);
        when(level.palettedContainerFactory()).thenReturn(PalettedContainerFactory.create(registries));
        return level;
    }

    public static CloseableReference<ServerLevel> setupServerLevel() {
        MockedStatic<RandomState> randomStateMockedStatic = Mockito.mockStatic(RandomState.class,
                withSettings().defaultAnswer(Answers.RETURNS_DEEP_STUBS));
        try {
            ChunkGenerator noiseBasedChunkGeneratorMock = mock(ChunkGenerator.class, withSettings().defaultAnswer(Answers.RETURNS_DEEP_STUBS));
            when(noiseBasedChunkGeneratorMock.createBiomes(any(), any(), any(), any()))
                    .thenAnswer(i -> CompletableFuture.completedFuture(i.getArguments()[3]));
            when(noiseBasedChunkGeneratorMock.buildTerrain(any(), any(), any(), any(), any(), any(), any()))
                    .thenAnswer(i -> CompletableFuture.completedFuture(i.getArguments()[0]));
            var registries = VanillaRegistriesExtension.shared();
            when(noiseBasedChunkGeneratorMock.getBiomeSource())
                    .thenReturn(new FixedBiomeSource(registries.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS)));
            var dimension = registries.lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(BuiltinDimensionTypes.OVERWORLD);
            var levelStem = new LevelStem(dimension, noiseBasedChunkGeneratorMock);
            MinecraftServer server = mock(RETURNS_DEEP_STUBS);
            when(server.registryAccess()).thenReturn(registries);
            LevelStorageSource.LevelStorageAccess levelStorageAccessMock = mock(Mockito.RETURNS_DEEP_STUBS);
            try {
                when(levelStorageAccessMock.getDimensionPath(any())).thenReturn(Files.createTempDirectory("cc_test"));
            } catch (Exception e) {
                e.printStackTrace();
            }
            return new CloseableReference<>(new ServerLevel(server,
                    // We run everything on the main thread as Mockito has race conditions when multiple threads call into it
                    // (which occurs when using RETURNS_DEEP_STUBS)
                    Runnable::run, levelStorageAccessMock, mock(RETURNS_DEEP_STUBS), Level.OVERWORLD, levelStem, false, 0, List.of(), false),
                    randomStateMockedStatic);
        } catch (Throwable failure) {
            randomStateMockedStatic.close();
            throw failure;
        }
    }

    /**
     * Generate a LevelCube filled with random stone and dirt blocks
     */
    public static LevelCube generateRandomLevelCube(Level level, CubePos pos, Random random) {
        var cube = new LevelCube(level, pos);
        for (int i = 0; i < CubicConstants.SECTION_COUNT; i++) {
            var section = cube.getSection(i);
            for (int x = 0; x < CubicConstants.SECTION_DIAMETER; x++) {
                for (int y = 0; y < CubicConstants.SECTION_DIAMETER; y++) {
                    for (int z = 0; z < CubicConstants.SECTION_DIAMETER; z++) {
                        if (random.nextBoolean()) {
                            section.setBlockState(x, y, z, random.nextBoolean() ? Blocks.STONE.defaultBlockState() : Blocks.DIRT.defaultBlockState(),
                                    false);
                        }
                    }
                }
            }
        }
        return cube;
    }

    public static <T> void assertDeepEquals(@Nullable T actual, @Nullable T expected) {
        assertThat(actual).usingRecursiveComparison().usingOverriddenEquals().isEqualTo(expected);
    }
}
