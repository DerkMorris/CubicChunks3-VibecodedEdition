package io.github.opencubicchunks.cubicchunks.test.client.multiplayer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.CubeWeatherClient;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundWeatherColumnPacket;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.CubeMobTestWorld;
import io.github.opencubicchunks.cubicchunks.testutils.Misc;
import io.github.opencubicchunks.cubicchunks.world.level.cube.CubeSource;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

/** Native extraction, splash placement and rain sound selection; no window or GPU is required. */
class TestClientWeatherEffects extends BaseTest {
    @ParameterizedTest
    @ValueSource(ints = { 96, 32000, -32000, 32000000, -32000000 })
    void nativeRendererHonorsFullHeightSurfaceUnknownAndDistantRoof(int y) throws Exception {
        try (var f = new Fixture(y)) {
            assertTrue(f.extract().rainColumns.isEmpty());
            f.apply(1, y);
            var open = f.extract();
            assertEquals(25, open.rainColumns.size());
            assertTrue(open.rainColumns.stream().allMatch(column -> column.bottomY() == y && column.topY() == y + 2));
            f.apply(2, y + 4);
            assertTrue(f.extract().rainColumns.isEmpty(), "A transparent glass roof above the camera clips rain geometry");
            f.apply(3, y + 1000000);
            assertTrue(f.extract().rainColumns.isEmpty(), "A distant roof must also block rain, without local cube or skylight guesses");
            ((CubeWeatherClient) f.level).cc_weatherColumns()
                    .apply(new CCClientboundWeatherColumnPacket(Level.OVERWORLD.identifier(), 0, 0, 4, null));
            assertTrue(f.extract().rainColumns.isEmpty());
            f.apply(5, y);
            assertEquals(25, f.extract().rainColumns.size());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 96, 32000, -32000, 32000000, -32000000 })
    void nativeSplashesAndRoofSoundUseCubicSurfaceAndMinimum(int y) throws Exception {
        try (var f = new Fixture(y)) {
            f.apply(1, y + 4);
            for (int i = 0; i < 5; i++) {
                f.level.tickWeatherEffects();
            }
            var heights = ArgumentCaptor.forClass(Double.class);
            verify(f.level, atLeastOnce()).addParticle(eq(ParticleTypes.RAIN), anyDouble(), heights.capture(), anyDouble(), anyDouble(), anyDouble(),
                    anyDouble());
            assertTrue(heights.getAllValues().stream().allMatch(value -> value == y + 4));
            verify(f.level, atLeastOnce()).playLocalSound(any(BlockPos.class), eq(SoundEvents.WEATHER_RAIN_ABOVE), eq(SoundSource.WEATHER),
                    anyFloat(), anyFloat(), anyBoolean());
            ((CubeWeatherClient) f.level).cc_weatherColumns()
                    .apply(new CCClientboundWeatherColumnPacket(Level.OVERWORLD.identifier(), 0, 0, 2, null));
            clearInvocations(f.level);
            f.level.tickWeatherEffects();
            verify(f.level, never()).addParticle(any(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble());
            verify(f.level, never()).playLocalSound(any(BlockPos.class), any(), any(), anyFloat(), anyFloat(), anyBoolean());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 96, 32000, -32000, 32000000, -32000000 })
    void nativePrecipitationBiomeUsesResidentCubeWithoutVanillaColumn(int y) throws Exception {
        try (var f = new Fixture(y)) {
            var biome = f.level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
            doReturn(biome).when(f.level).getBiome(any());
            var pos = new BlockPos(16, y, 16);
            var cube = new LevelCube(f.level, CubePos.from(pos));
            when(((CubeSource) f.level.getChunkSource()).cc_getCubeNow(anyInt(), anyInt(), anyInt())).thenReturn(cube);
            when(f.level.getChunkSource().hasChunk(anyInt(), anyInt())).thenReturn(false);
            doCallRealMethod().when(f.level).getPrecipitationAt(any());
            assertEquals(biome.value().getPrecipitationAt(pos, f.level.getSeaLevel()), f.level.getPrecipitationAt(pos));
            when(((CanBeCubic) f.level).cc_isCubic()).thenReturn(false);
            assertEquals(Biome.Precipitation.NONE, f.level.getPrecipitationAt(pos));
        }
    }

    @Test
    void vanillaRendererContinuesToUseItsOwnHeightmap() throws Exception {
        try (var f = new Fixture(96)) {
            when(((CanBeCubic) f.level).cc_isCubic()).thenReturn(false);
            f.apply(1, 32000);
            assertEquals(25, f.extract().rainColumns.size());
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final ClientLevel level = Misc.mockLevelWithPalettes(ClientLevel.class);
        private final Minecraft minecraft = mock();
        private final WeatherEffectRenderer renderer = mock();
        private final MockedStatic<Minecraft> globals;
        private final Vec3 camera;

        private Fixture(int y) throws Exception {
            camera = new Vec3(16, y, 16);
            when(level.dimension()).thenReturn(Level.OVERWORLD);
            when(((CanBeCubic) level).cc_isCubic()).thenReturn(true);
            when(level.getRainLevel(anyFloat())).thenReturn(1F);
            when(level.getPrecipitationAt(any())).thenReturn(Biome.Precipitation.RAIN);
            when(level.getHeight(any(), anyInt(), anyInt())).thenReturn(64);
            when(level.getMinY()).thenReturn(-64);
            when(level.getBlockState(any())).thenReturn(Blocks.STONE.defaultBlockState());
            when(level.getFluidState(any())).thenReturn(Fluids.EMPTY.defaultFluidState());
            when(level.getBrightness(any(), any())).thenReturn(15);
            var lightEngine = level.getChunkSource().getLightEngine();
            when(level.getLightEngine()).thenReturn(lightEngine);
            doCallRealMethod().when((CubeWeatherClient) level).cc_weatherColumns();
            doCallRealMethod().when(level).tickWeatherEffects();
            doCallRealMethod().when(renderer).extractRenderState(any(), anyFloat(), any(), any());
            Options options = mock();
            OptionInstance<Integer> radius = mock();
            OptionInstance<ParticleStatus> particles = mock();
            when(radius.get()).thenReturn(2);
            when(particles.get()).thenReturn(ParticleStatus.ALL);
            when(options.weatherRadius()).thenReturn(radius);
            when(options.particles()).thenReturn(particles);
            var gameRenderer = mock(GameRenderer.class);
            var view = mock(Camera.class);
            when(view.position()).thenReturn(camera);
            when(gameRenderer.mainCamera()).thenReturn(view);
            CubeMobTestWorld.setField(Minecraft.class, minecraft, "options", options);
            CubeMobTestWorld.setField(Minecraft.class, minecraft, "gameRenderer", gameRenderer);
            CubeMobTestWorld.setField(ClientLevel.class, level, "minecraft", minecraft);
            CubeMobTestWorld.setField(ClientLevel.class, level, "chunkSource", level.getChunkSource());
            globals = mockStatic(Minecraft.class);
            globals.when(Minecraft::getInstance).thenReturn(minecraft);
        }

        private void apply(long epoch, int y) {
            int[] heights = new int[CCClientboundWeatherColumnPacket.COUNT];
            Arrays.fill(heights, y);
            ((CubeWeatherClient) level).cc_weatherColumns()
                    .apply(new CCClientboundWeatherColumnPacket(Level.OVERWORLD.identifier(), 0, 0, epoch, heights));
        }

        private WeatherRenderState extract() {
            var state = new WeatherRenderState();
            renderer.extractRenderState(level, 0, camera, state);
            return state;
        }

        @Override public void close() {
            globals.close();
        }
    }
}
