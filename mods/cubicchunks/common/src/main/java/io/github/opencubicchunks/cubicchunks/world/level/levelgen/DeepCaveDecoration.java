package io.github.opencubicchunks.cubicchunks.world.level.levelgen;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.core.Direction;
import net.minecraft.core.QuartPos;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CaveVines;
import net.minecraft.world.level.block.SculkShriekerBlock;
import net.minecraft.world.level.block.SpeleothemBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SpeleothemThickness;
import net.minecraft.world.level.chunk.LevelChunkSection;

/** Local cave features evaluated against immutable terrain, including supports outside this section. */
final class DeepCaveDecoration {
    private static final int SIDE = SectionPos.SECTION_SIZE;
    private static final int QUART_MASK = QuartPos.fromBlock(SIDE) - 1;
    private static final int PATCH_SIZE = 8;
    private static final long PATCH_SALT = 0xD1B54A32D192ED03L;

    private DeepCaveDecoration() {}

    static void decorate(long seed, SectionPos pos, LevelChunkSection section, BiomeResolver biomes, BlockReader raw) {
        var context = new Context(seed, pos, section, biomes, raw, new HashMap<>());
        for (int y = 0; y < SIDE; y++) {
            for (int z = 0; z < SIDE; z++) {
                for (int x = 0; x < SIDE; x++) {
                    var original = section.getBlockState(x, y, z);
                    var decorated = context.state(pos.minBlockX() + x, pos.minBlockY() + y, pos.minBlockZ() + z, original);
                    if (decorated != original) {
                        section.setBlockState(x, y, z, decorated, false);
                    }
                }
            }
        }
    }

    private static boolean naturalRock(BlockState state) {
        return state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(Blocks.CALCITE);
    }

    @SuppressWarnings("checkstyle:MagicNumber") // Full-coordinate seeded avalanche; never pack or truncate the vertical coordinate.
    private static long hash(long seed, int x, int y, int z) {
        long value = seed ^ x * 0x9E3779B97F4A7C15L ^ y * 0xD1B54A32D192ED03L ^ z * 0x94D049BB133111EBL;
        value = (value ^ value >>> 30) * 0xBF58476D1CE4E5B9L;
        value = (value ^ value >>> 27) * 0x94D049BB133111EBL;
        return value ^ value >>> 31;
    }

    private static BlockState pointed(Direction direction) {
        return Blocks.POINTED_DRIPSTONE.defaultBlockState().setValue(SpeleothemBlock.TIP_DIRECTION, direction)
                .setValue(SpeleothemBlock.THICKNESS, SpeleothemThickness.TIP);
    }

    @FunctionalInterface
    interface BlockReader {
        BlockState get(int x, int y, int z);
    }

    private enum Cave {
        OTHER, LUSH, DRIPSTONE, DARK
    }

    private record Quart(int x, int y, int z) {}

    private record Context(
            long seed, SectionPos pos, LevelChunkSection section, BiomeResolver biomes, BlockReader raw, Map<Quart, Cave> caves
    ) {
        BlockState state(int x, int y, int z, BlockState original) {
            if (naturalRock(original)) {
                return surface(x, y, z, original);
            }
            if (!original.isAir()) {
                return original;
            }
            var cave = cave(x, y, z);
            if (cave == Cave.OTHER) {
                return original;
            }
            long choice = hash(seed, x, y, z);
            if (support(x, y - 1, z, cave)) {
                return onFloor(cave, choice, original);
            }
            if (support(x, y + 1, z, cave)) {
                return onCeiling(cave, choice, original);
            }
            return original;
        }

        private BlockState surface(int x, int y, int z, BlockState original) {
            boolean floor = raw.get(x, y + 1, z).isAir();
            boolean ceiling = raw.get(x, y - 1, z).isAir();
            if ((!floor && !ceiling) || !patch(x, y, z)) {
                return original;
            }
            return switch (cave(x, y, z)) {
                case LUSH -> Blocks.MOSS_BLOCK.defaultBlockState();
                case DRIPSTONE -> Blocks.DRIPSTONE_BLOCK.defaultBlockState();
                case DARK -> floor ? Blocks.SCULK.defaultBlockState() : original;
                default -> original;
            };
        }

        private boolean support(int x, int y, int z, Cave cave) {
            return naturalRock(raw.get(x, y, z)) && patch(x, y, z) && cave(x, y, z) == cave;
        }

        @SuppressWarnings("checkstyle:MagicNumber") // Sparse local features share the same world-aligned surface patch predicates.
        private static BlockState onFloor(Cave cave, long choice, BlockState original) {
            return switch (cave) {
                case LUSH -> lushFeature(choice, original);
                case DRIPSTONE -> Math.floorMod(choice, 4) == 0 ? pointed(Direction.UP) : original;
                case DARK -> sculkFeature(choice, original);
                default -> original;
            };
        }

        @SuppressWarnings("checkstyle:MagicNumber") // Relative vegetation weights within each moss patch.
        private static BlockState lushFeature(long choice, BlockState original) {
            return switch (Math.floorMod(choice, 12)) {
                case 0 -> Blocks.FLOWERING_AZALEA.defaultBlockState();
                case 1, 2 -> Blocks.AZALEA.defaultBlockState();
                case 3, 4, 5, 6 -> Blocks.MOSS_CARPET.defaultBlockState();
                default -> original;
            };
        }

        @SuppressWarnings("checkstyle:MagicNumber") // Natural shriekers can summon; their block entities are installed by the cube fill path.
        private static BlockState sculkFeature(long choice, BlockState original) {
            if (Math.floorMod(choice, 128) == 0) {
                return Blocks.SCULK_SHRIEKER.defaultBlockState().setValue(SculkShriekerBlock.CAN_SUMMON, true);
            }
            return Math.floorMod(choice, 24) == 0 ? Blocks.SCULK_SENSOR.defaultBlockState() : original;
        }

        @SuppressWarnings("checkstyle:MagicNumber") // One-block hanging tips have stable support even across a section boundary.
        private static BlockState onCeiling(Cave cave, long choice, BlockState original) {
            return switch (cave) {
                case LUSH -> Math.floorMod(choice, 5) == 0 ? Blocks.CAVE_VINES.defaultBlockState().setValue(CaveVines.BERRIES, true) : original;
                case DRIPSTONE -> Math.floorMod(choice, 4) == 0 ? pointed(Direction.DOWN) : original;
                default -> original;
            };
        }

        @SuppressWarnings("checkstyle:MagicNumber") // Leave some exposed rock between the eight-block surface patches.
        private boolean patch(int x, int y, int z) {
            return Math.floorMod(hash(seed ^ PATCH_SALT, Math.floorDiv(x, PATCH_SIZE), Math.floorDiv(y, PATCH_SIZE), Math.floorDiv(z, PATCH_SIZE)),
                    4) != 0;
        }

        private Cave cave(int x, int y, int z) {
            var key = new Quart(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z));
            return caves.computeIfAbsent(key, quart -> {
                boolean inside = SectionPos.blockToSectionCoord(QuartPos.toBlock(quart.x())) == pos.x()
                        && SectionPos.blockToSectionCoord(QuartPos.toBlock(quart.y())) == pos.y()
                        && SectionPos.blockToSectionCoord(QuartPos.toBlock(quart.z())) == pos.z();
                var biome = inside ? section.getNoiseBiome(quart.x() & QUART_MASK, quart.y() & QUART_MASK, quart.z() & QUART_MASK)
                        : biomes.getNoiseBiome(quart.x(), quart.y(), quart.z());
                if (biome.is(Biomes.LUSH_CAVES)) {
                    return Cave.LUSH;
                }
                if (biome.is(Biomes.DRIPSTONE_CAVES)) {
                    return Cave.DRIPSTONE;
                }
                return biome.is(Biomes.DEEP_DARK) ? Cave.DARK : Cave.OTHER;
            });
        }
    }
}
