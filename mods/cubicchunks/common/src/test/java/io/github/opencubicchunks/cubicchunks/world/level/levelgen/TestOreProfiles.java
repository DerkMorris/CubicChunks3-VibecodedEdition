package io.github.opencubicchunks.cubicchunks.world.level.levelgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import com.mojang.serialization.JsonOps;
import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.Misc;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

class TestOreProfiles extends BaseTest {
    @Test
    void formulaIsEvaluatedInAbsoluteYWithInclusiveCutoffsAndStableTails() {
        var expr = HeightFormula.parse(OreRule.GROWTH);
        var cutoff = new OreRule(-1000, 64, 6, expr);
        assertEquals(0, cutoff.percent(-1001));
        assertEquals(40, cutoff.percent(-1000));
        assertEquals(20, cutoff.percent(-532));
        assertEquals(0, cutoff.percent(-64));
        assertEquals(40, expr.percent(-32000000));
        var points = HeightFormula.parse("curve(-1000,40, -532,20, -64,0)");
        assertEquals(40, points.percent(-1001));
        assertEquals(30, points.percent(-766));
        assertEquals(0, points.percent(100));
        assertEquals(6.25, HeightFormula.parse("if(y<=0, 1.25e1/2, 100)").percent(-10));
        assertEquals(8, HeightFormula.parse("abs(-2^3)").percent(0));
        assertEquals(0, HeightFormula.parse("1/(y+1)").percent(-1));
        assertEquals(100, HeightFormula.parse("200").percent(0));
        assertEquals(0, HeightFormula.parse("-100").percent(0));
    }

    @Test
    void malformedAndUnboundedExpressionsAreRejectedBeforeGeneration() {
        for (var bad : java.util.List.of("1 +", "run(y)", "min(1)", "curve(0,10,0,20)", "y junk", "1.2.3", "1e999",
                "(".repeat(30) + "1" + ")".repeat(30))) {
            assertThrows(IllegalArgumentException.class, () -> HeightFormula.parse(bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> new OreRule(0, -1, 2, HeightFormula.parse("50")));
    }

    @Test
    void independentRulesRoundTripAndEarlierWorldsRetainTheirOriginalDistribution() {
        var old = new DeepCaveSettings(true, 64, 45, 35);
        var prior = DeepCaveSettings.CODEC.encodeStart(JsonOps.INSTANCE, old).getOrThrow().getAsJsonObject();
        prior.remove("ore_rules");
        assertEquals(old, DeepCaveSettings.CODEC.parse(JsonOps.INSTANCE, prior).getOrThrow());
        var rules = Map.of(OreKind.COAL, new OreRule(-100, 0, 2, HeightFormula.parse("32")), OreKind.GOLD, OreRule.DEFAULT);
        var custom = new DeepCaveSettings(false, 64, 45, 35, rules);
        var tag = DeepCaveSettings.CODEC.encodeStart(JsonOps.INSTANCE, custom).getOrThrow();
        assertEquals(custom, DeepCaveSettings.CODEC.parse(JsonOps.INSTANCE, tag).getOrThrow());
        assertTrue(old.oreRules().isEmpty());
    }

    @Test
    void aVeinCannotSpillPastACutoffOrIntoAZeroOfTheFormula() {
        var palettes = Misc.mockLevelWithPalettes(Level.class).palettedContainerFactory();
        var diamond = new OreRule(-1000, -978, 8, HeightFormula.parse("if(y==-990,0,100)"));
        var settings = new DeepCaveSettings(false, 64, 45, 0,
                Map.of(OreKind.DIAMOND, diamond, OreKind.GOLD, new OreRule(-1100, -950, 6, HeightFormula.parse("0"))));
        var first = new DeepCaveTerrain(37, settings, palettes);
        var second = new DeepCaveTerrain(37, settings, palettes);
        int diamonds = 0;
        for (int sx = -1; sx <= 1; sx++) {
            for (int sy = -64; sy <= -60; sy++) {
                var section = first.section(sx, sy, -1);
                second.section(sx + 1, sy, -1);
                var same = second.section(sx, sy, -1);
                for (int y = 0; y < 16; y++) {
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            var state = section.getBlockState(x, y, z);
                            assertEquals(state, same.getBlockState(x, y, z));
                            assertFalse(state.isAir());
                            if (state.is(Blocks.DIAMOND_ORE)) {
                                diamonds++;
                                assertTrue(diamond.percent(16 * sy + y) > 0);
                            } else {
                                assertTrue(state.is(Blocks.STONE));
                            }
                        }
                    }
                }
            }
        }
        assertTrue(diamonds > 0);
    }
}
