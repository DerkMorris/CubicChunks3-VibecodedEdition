package io.github.opencubicchunks.cubicchunks.world.level.levelgen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import com.mojang.serialization.JsonOps;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.Misc;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class TestDeepCaveTerrain extends BaseTest {
    @Test
    void caveCurvesKeepUnitsAndPersistIndependentlyOfOresAndOldWorlds() {
        var curve = HeightFormula.parse("curve(-1024,150,-512,110,-64,24)");
        assertEquals(130.0, CaveParameter.SCALE.sample(curve, 64, -768));
        assertEquals(100.0, CaveParameter.OPENNESS.sample(curve, 45, -768));
        assertEquals(64.0, CaveParameter.SCALE.sample(HeightFormula.parse("1/(y+100)"), 64, -100));
        assertEquals(24.0, CaveParameter.SCALE.sample(HeightFormula.parse("-1"), 64, 0));
        var ores = Map.of(OreKind.DIAMOND, OreRule.DEFAULT);
        var settings = new DeepCaveSettings(true, 64, 45, 35, ores, Map.of(CaveParameter.SCALE, curve));
        var json = DeepCaveSettings.CODEC.encodeStart(JsonOps.INSTANCE, settings).getOrThrow().getAsJsonObject();
        assertEquals(settings, DeepCaveSettings.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
        json.remove("cave_curves");
        assertEquals(new DeepCaveSettings(true, 64, 45, 35, ores), DeepCaveSettings.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
    }

    @Test
    void plateausMatchTheCorrespondingConstantGeneratorAndAdjacentGridsShareEveryFace() {
        var palettes = Misc.mockLevelWithPalettes(Level.class).palettedContainerFactory();
        var curves = Map.of(CaveParameter.SCALE, HeightFormula.parse("curve(-1600,142,-320,43)"), CaveParameter.OPENNESS,
                HeightFormula.parse("curve(-1600,79,-320,17)"));
        var cave = new DeepCaveTerrain(192, new DeepCaveSettings(true, 64, 45, 0, Map.of(), curves), palettes);
        int x = 16000000, z = -24000000;
        var deep = new DeepCaveTerrain(192, new DeepCaveSettings(true, 142, 79, 0), palettes);
        var shallow = new DeepCaveTerrain(192, new DeepCaveSettings(true, 43, 17, 0), palettes);
        assertArrayEquals(deep.densityGrid(x, -30000000, z), cave.densityGrid(x, -30000000, z));
        assertArrayEquals(shallow.densityGrid(x, -256, z), cave.densityGrid(x, -256, z));
        int y = -1024;
        var center = cave.densityGrid(x, y, z);
        var east = cave.densityGrid(x + 16, y, z);
        var above = cave.densityGrid(x, y + 16, z);
        var south = cave.densityGrid(x, y, z + 16);
        for (int a = 0; a < 5; a++) {
            for (int b = 0; b < 5; b++) {
                assertEquals(center[4 + 5 * (a + 5 * b)], east[5 * (a + 5 * b)]);
                assertEquals(center[a + 5 * (b + 5 * 4)], above[a + 5 * b]);
                assertEquals(center[a + 5 * (4 + 5 * b)], south[a + 25 * b]);
            }
        }
    }

    @Test
    void opennessAndOreFrequencyChangeDifferentPartsOfTheSameWorld() {
        var palettes = Misc.mockLevelWithPalettes(Level.class).palettedContainerFactory();
        var tight = new DeepCaveTerrain(42, new DeepCaveSettings(true, 64, 0, 0), palettes);
        var open = new DeepCaveTerrain(42, new DeepCaveSettings(true, 64, 100, 0), palettes);
        var rich = new DeepCaveTerrain(42, new DeepCaveSettings(true, 64, 100, 100), palettes);
        int cavities = 0;
        int veins = 0;
        for (int sy : new int[] { -5, -120, -62291, -2000011 }) {
            var small = tight.section(-14, sy, 21);
            var large = open.section(-14, sy, 21);
            var ores = rich.section(-14, sy, 21);
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        var sparse = small.getBlockState(x, y, z);
                        var spacious = large.getBlockState(x, y, z);
                        var mineral = ores.getBlockState(x, y, z);
                        if (sparse.isAir()) {
                            assertTrue(spacious.isAir());
                        }
                        if (spacious.isAir()) {
                            assertTrue(mineral.isAir());
                        }
                        if (!sparse.isAir() && spacious.isAir()) {
                            cavities++;
                        }
                        if (mineral != spacious) {
                            assertFalse(spacious.isAir());
                            veins++;
                        }
                    }
                }
            }
        }
        assertTrue(cavities > 0);
        assertTrue(veins > 0);
    }

    @Test
    void negativeSectionBordersAndFullYAreIndependentOfRequestOrder() {
        var palettes = Misc.mockLevelWithPalettes(Level.class).palettedContainerFactory();
        var settings = new DeepCaveSettings(true, 78, 52, 55);
        var first = new DeepCaveTerrain(37, settings, palettes);
        var second = new DeepCaveTerrain(37, settings, palettes);
        var otherSeed = new DeepCaveTerrain(38, settings, palettes);
        int differences = 0, seedDifferences = 0;
        int sy = -20;
        var a = first.section(-1, sy, 0);
        var b = first.section(0, sy, 0);
        var far = first.section(-1, sy - (1 << 20), 0);
        var b2 = second.section(0, sy, 0);
        var a2 = second.section(-1, sy, 0);
        var other = otherSeed.section(-1, sy, 0);
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                assertSame(a.getBlockState(15, y, z), a2.getBlockState(15, y, z));
                assertSame(b.getBlockState(0, y, z), b2.getBlockState(0, y, z));
                if (a.getBlockState(15, y, z) != far.getBlockState(15, y, z)) {
                    differences++;
                }
                if (a.getBlockState(15, y, z) != other.getBlockState(15, y, z)) {
                    seedDifferences++;
                }
            }
        }
        assertTrue(differences > 0);
        assertTrue(seedDifferences > 0);
    }
}
