package io.github.opencubicchunks.cubicchunks.world.level.levelgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import io.github.opencubicchunks.cubicchunks.testutils.BaseTest;
import io.github.opencubicchunks.cubicchunks.testutils.VanillaRegistriesExtension;
import net.minecraft.core.Direction;
import net.minecraft.core.QuartPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CaveVines;
import net.minecraft.world.level.block.SculkShriekerBlock;
import net.minecraft.world.level.block.SpeleothemBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SpeleothemThickness;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import org.junit.jupiter.api.Test;

class TestDeepCaveDecoration extends BaseTest {
    @Test
    void allThreeCaveBiomesHaveTheirOwnSupportedFeaturesAcrossNegativeSectionFaces() {
        for (int floor : new int[] { -17, -161, -32000001 }) {
            checkFeatures(Biomes.LUSH_CAVES, floor, Set.of(Blocks.MOSS_BLOCK, Blocks.MOSS_CARPET, Blocks.AZALEA, Blocks.CAVE_VINES));
            checkFeatures(Biomes.DRIPSTONE_CAVES, floor, Set.of(Blocks.DRIPSTONE_BLOCK, Blocks.POINTED_DRIPSTONE));
            checkFeatures(Biomes.DEEP_DARK, floor, Set.of(Blocks.SCULK, Blocks.SCULK_SENSOR, Blocks.SCULK_SHRIEKER));
        }
    }

    @Test
    void requestOrderAndEvictionRegenerationDoNotChangeCaveFeatures() {
        for (var biome : java.util.List.of(Biomes.LUSH_CAVES, Biomes.DRIPSTONE_CAVES, Biomes.DEEP_DARK)) {
            var first = generate(biome, -161, false);
            var reversed = generate(biome, -161, true);
            first.forEach((pos, section) -> {
                var other = reversed.get(pos);
                for (int x = 0; x < 16; x++) {
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            assertSame(section.getBlockState(x, y, z), other.getBlockState(x, y, z));
                        }
                    }
                }
            });
        }
    }

    @Test
    void fullVerticalCoordinateAndWorldSeedAffectFeaturePlacement() {
        var pos = SectionPos.of(-1, -10, 0);
        var far = SectionPos.of(pos.x(), pos.y() - (1 << 20), pos.z());
        var first = airAboveFloor(pos, 37);
        var otherSeed = airAboveFloor(pos, 38);
        var otherHeight = airAboveFloor(far, 37);
        int seedDifferences = 0, heightDifferences = 0;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                if (first.getBlockState(x, 0, z) != otherSeed.getBlockState(x, 0, z)) {
                    seedDifferences++;
                }
                if (first.getBlockState(x, 0, z) != otherHeight.getBlockState(x, 0, z)) {
                    heightDifferences++;
                }
            }
        }
        assertTrue(seedDifferences > 0);
        assertTrue(heightDifferences > 0, "Decoration must not alias packed SectionPos vertical coordinates");
    }

    private static LevelChunkSection airAboveFloor(SectionPos pos, long seed) {
        var raw = cavity(pos.minBlockY() - 1);
        var section = section(pos, raw);
        var biomes = resolver(Biomes.LUSH_CAVES);
        section.fillBiomesFromNoise(biomes, QuartPos.fromBlock(pos.minBlockX()), QuartPos.fromBlock(pos.minBlockY()),
                QuartPos.fromBlock(pos.minBlockZ()));
        DeepCaveDecoration.decorate(seed, pos, section, biomes, raw);
        return section;
    }

    @Test
    void ordinaryBiomesAndNonNaturalBlocksAreUntouched() {
        var pos = SectionPos.of(-1, -10, 0);
        for (var biome : java.util.List.of(Biomes.PLAINS, Biomes.LUSH_CAVES, Biomes.DRIPSTONE_CAVES, Biomes.DEEP_DARK)) {
            for (var protectedBlock : new Block[] { Blocks.DEEPSLATE_DIAMOND_ORE, Blocks.WATER, Blocks.LAVA, Blocks.CHEST,
                    Blocks.DEEPSLATE_BRICKS, Blocks.BEDROCK }) {
                DeepCaveDecoration.BlockReader raw = (x, y, z) -> y == pos.minBlockY() ? protectedBlock.defaultBlockState()
                        : Blocks.AIR.defaultBlockState();
                var section = section(pos, raw);
                section.fillBiomesFromNoise(resolver(biome), QuartPos.fromBlock(pos.minBlockX()), QuartPos.fromBlock(pos.minBlockY()),
                        QuartPos.fromBlock(pos.minBlockZ()));
                var before = section.copy();
                DeepCaveDecoration.decorate(37, pos, section, resolver(biome), raw);
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            assertSame(before.getBlockState(x, y, z), section.getBlockState(x, y, z));
                        }
                    }
                }
            }
        }
        var ordinary = generate(Biomes.PLAINS, -161, false);
        ordinary.forEach((sectionPos, section) -> {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        assertSame(cavity(-161).get(sectionPos.minBlockX() + x, sectionPos.minBlockY() + y, sectionPos.minBlockZ() + z),
                                section.getBlockState(x, y, z));
                    }
                }
            }
        });
    }

    private static void checkFeatures(ResourceKey<Biome> biome, int floor, Set<Block> required) {
        var sections = generate(biome, floor, false);
        Set<Block> found = new HashSet<>();
        Set<Direction> directions = new HashSet<>();
        sections.forEach((pos, section) -> {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int wx = pos.minBlockX() + x, wy = pos.minBlockY() + y, wz = pos.minBlockZ() + z;
                        var state = section.getBlockState(x, y, z);
                        found.add(state.getBlock());
                        if (state.is(Blocks.POINTED_DRIPSTONE)) {
                            var direction = state.getValue(SpeleothemBlock.TIP_DIRECTION);
                            directions.add(direction);
                            assertEquals(SpeleothemThickness.TIP, state.getValue(SpeleothemBlock.THICKNESS));
                            assertTrue(block(sections, wx, wy - direction.getStepY(), wz).is(Blocks.DRIPSTONE_BLOCK));
                        } else if (state.is(Blocks.CAVE_VINES)) {
                            assertTrue(state.getValue(CaveVines.BERRIES));
                            assertTrue(block(sections, wx, wy + 1, wz).is(Blocks.MOSS_BLOCK));
                        } else if (state.is(Blocks.MOSS_CARPET) || state.is(Blocks.AZALEA) || state.is(Blocks.FLOWERING_AZALEA)) {
                            assertTrue(block(sections, wx, wy - 1, wz).is(Blocks.MOSS_BLOCK));
                        } else if (state.is(Blocks.SCULK_SENSOR) || state.is(Blocks.SCULK_SHRIEKER)) {
                            assertTrue(block(sections, wx, wy - 1, wz).is(Blocks.SCULK));
                            if (state.is(Blocks.SCULK_SHRIEKER)) {
                                assertTrue(state.getValue(SculkShriekerBlock.CAN_SUMMON));
                            }
                        }
                    }
                }
            }
        });
        assertTrue(found.containsAll(required), "Missing features for " + biome + " at floor=" + floor + ": " + found);
        if (biome.equals(Biomes.DRIPSTONE_CAVES)) {
            assertEquals(Set.of(Direction.UP, Direction.DOWN), directions);
        }
    }

    private static Map<SectionPos, LevelChunkSection> generate(ResourceKey<Biome> biome, int floor, boolean reverse) {
        var positions = new ArrayList<SectionPos>();
        int center = SectionPos.blockToSectionCoord(floor + 1);
        for (int x = -2; x <= 1; x++) {
            for (int z = -1; z <= 0; z++) {
                for (int y = center - 1; y <= center + 1; y++) {
                    positions.add(SectionPos.of(x, y, z));
                }
            }
        }
        if (reverse) {
            Collections.reverse(positions);
        }
        var raw = cavity(floor);
        var resolver = resolver(biome);
        Map<SectionPos, LevelChunkSection> sections = new HashMap<>();
        for (var pos : positions) {
            var section = section(pos, raw);
            section.fillBiomesFromNoise(resolver, QuartPos.fromBlock(pos.minBlockX()), QuartPos.fromBlock(pos.minBlockY()),
                    QuartPos.fromBlock(pos.minBlockZ()));
            DeepCaveDecoration.decorate(37, pos, section, resolver, raw);
            sections.put(pos, section);
        }
        return sections;
    }

    private static DeepCaveDecoration.BlockReader cavity(int floor) {
        return (x, y, z) -> (y > floor && y < floor + 17 ? Blocks.AIR : Blocks.DEEPSLATE).defaultBlockState();
    }

    private static LevelChunkSection section(SectionPos pos, DeepCaveDecoration.BlockReader raw) {
        var section = new LevelChunkSection(PalettedContainerFactory.create(VanillaRegistriesExtension.shared()));
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    section.setBlockState(x, y, z, raw.get(pos.minBlockX() + x, pos.minBlockY() + y, pos.minBlockZ() + z), false);
                }
            }
        }
        return section;
    }

    private static BiomeResolver resolver(ResourceKey<Biome> key) {
        var biome = VanillaRegistriesExtension.shared().lookupOrThrow(Registries.BIOME).getOrThrow(key);
        return (x, y, z) -> biome;
    }

    private static BlockState block(Map<SectionPos, LevelChunkSection> sections, int x, int y, int z) {
        return sections.get(SectionPos.of(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(y), SectionPos.blockToSectionCoord(z)))
                .getBlockState(Math.floorMod(x, 16), Math.floorMod(y, 16), Math.floorMod(z, 16));
    }
}
