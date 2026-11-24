package io.github.opencubicchunks.cubicchunks.world.level.levelgen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.Misc;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;

class TestStructureCaveTerrain extends BaseTest {
    @Test
    void nearbyTerrainIsAnOrdinarySmallerCaveGeneratorWithAirAndRockAtEveryDepth() {
        var palettes = Misc.mockLevelWithPalettes(Level.class).palettedContainerFactory();
        var settings = new DeepCaveSettings(true, 160, 100, 0);
        for (int y : new int[] { -320, -32000, -32000000 }) {
            var field = StructureCaveInfluence.Field.of(List.of(new BoundingBox(-64, y - 64, -64, 64, y + 64, 64)));
            var influence = influence(field);
            var baseline = new DeepCaveTerrain(42, settings, palettes, null, null, -64);
            var nearby = new DeepCaveTerrain(42, settings, palettes, null, null, -64, influence);
            var small = new DeepCaveTerrain(42, new DeepCaveSettings(true, 32, 35, 0), palettes, null, null, -64);
            int localAir = 0, baselineAir = 0, samples = 0;
            for (int sx = -2; sx < 2; sx++) {
                for (int sy = y / 16 - 2; sy < y / 16 + 2; sy++) {
                    for (int sz = -2; sz < 2; sz++) {
                        assertArrayEquals(small.densityGrid(sx * 16, sy * 16, sz * 16), nearby.densityGrid(sx * 16, sy * 16, sz * 16));
                        localAir += air(nearby.section(sx, sy, sz));
                        baselineAir += air(baseline.section(sx, sy, sz));
                        samples += 16 * 16 * 16;
                    }
                }
            }
            assertTrue(localAir > samples / 100, "Small natural caves must remain near structures at Y=" + y);
            assertTrue(localAir < samples * 3 / 4, "Small caves must retain surrounding rock at Y=" + y);
            assertTrue(localAir < baselineAir, "The nearby volume must be less open than giant caves at Y=" + y);
            assertSharedFaces(nearby, 112, y, 32);
            assertArrayEquals(baseline.densityGrid(512, y, 512), nearby.densityGrid(512, y, 512));
        }
    }

    @Test
    void alreadySmallParametersAndDistantTerrainRemainExactIncludingHeightCurves() {
        var palettes = Misc.mockLevelWithPalettes(Level.class).palettedContainerFactory();
        var field = mock(StructureCaveInfluence.Field.class);
        when(field.weight(anyInt(), anyInt(), anyInt())).thenReturn(1.0);
        var influence = influence(field);
        var settings = List.of(new DeepCaveSettings(true, 27, 20, 0),
                new DeepCaveSettings(true, 64, 45, 0, Map.of(), Map.of(CaveParameter.SCALE, HeightFormula.parse("27+2*sin(y/170)"),
                        CaveParameter.OPENNESS, HeightFormula.parse("20+10*cos(y/210)"))));
        for (var setting : settings) {
            var baseline = new DeepCaveTerrain(192, setting, palettes, null, null, -64);
            var nearby = new DeepCaveTerrain(192, setting, palettes, null, null, -64, influence);
            for (int y : new int[] { -320, -32000, -32000000 }) {
                assertArrayEquals(baseline.densityGrid(16000000, y, -24000000), nearby.densityGrid(16000000, y, -24000000));
            }
        }
        when(field.weight(anyInt(), anyInt(), anyInt())).thenReturn(0.0);
        for (var setting : List.of(new DeepCaveSettings(true, 79, 86, 0),
                new DeepCaveSettings(true, 64, 45, 0, Map.of(), Map.of(CaveParameter.SCALE, HeightFormula.parse("90+60*sin(y/170)"),
                        CaveParameter.OPENNESS, HeightFormula.parse("50+40*cos(y/210)"))))) {
            var baseline = new DeepCaveTerrain(192, setting, palettes, null, null, -64);
            var nearby = new DeepCaveTerrain(192, setting, palettes, null, null, -64, influence);
            for (int y : new int[] { -320, -32000, -32000000 }) {
                assertArrayEquals(baseline.densityGrid(16000000, y, -24000000), nearby.densityGrid(16000000, y, -24000000));
            }
        }
    }

    @Test
    void localScaleMeetsTheOriginalFrequencyContinuouslyFarFromTheOrigin() {
        var palettes = Misc.mockLevelWithPalettes(Level.class).palettedContainerFactory();
        var field = mock(StructureCaveInfluence.Field.class);
        var influence = influence(field);
        for (var setting : List.of(new DeepCaveSettings(true, 79, 86, 0),
                new DeepCaveSettings(true, 64, 45, 0, Map.of(), Map.of(CaveParameter.SCALE, HeightFormula.parse("90+60*sin(y/170)"))))) {
            var nearby = new DeepCaveTerrain(192, setting, palettes, null, null, -64, influence);
            when(field.weight(anyInt(), anyInt(), anyInt())).thenReturn(0.0);
            var original = nearby.densityGrid(16000000, -32000, -24000000);
            when(field.weight(anyInt(), anyInt(), anyInt())).thenReturn(1.0e-9);
            assertArrayEquals(original, nearby.densityGrid(16000000, -32000, -24000000), 1.0e-7);
        }
    }

    private static StructureCaveInfluence influence(StructureCaveInfluence.Field field) {
        var influence = mock(StructureCaveInfluence.class);
        when(influence.forArea(anyInt(), anyInt(), anyInt(), anyInt())).thenReturn(field);
        return influence;
    }

    private static int air(LevelChunkSection section) {
        int count = 0;
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    if (section.getBlockState(x, y, z).isAir()) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private static void assertSharedFaces(DeepCaveTerrain terrain, int x, int y, int z) {
        var center = terrain.densityGrid(x, y, z);
        var east = terrain.densityGrid(x + 16, y, z);
        var above = terrain.densityGrid(x, y + 16, z);
        var south = terrain.densityGrid(x, y, z + 16);
        for (int a = 0; a < 5; a++) {
            for (int b = 0; b < 5; b++) {
                assertEquals(center[4 + 5 * (a + 5 * b)], east[5 * (a + 5 * b)]);
                assertEquals(center[a + 5 * (b + 5 * 4)], above[a + 5 * b]);
                assertEquals(center[a + 5 * (4 + 5 * b)], south[a + 25 * b]);
            }
        }
    }
}
