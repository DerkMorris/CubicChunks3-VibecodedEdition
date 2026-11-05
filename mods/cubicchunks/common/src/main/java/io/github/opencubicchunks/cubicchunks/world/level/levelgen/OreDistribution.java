package io.github.opencubicchunks.cubicchunks.world.level.levelgen;

import java.util.IdentityHashMap;
import java.util.Map;

import net.minecraft.core.SectionPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;

/** One independent seeded attempt per ore per world-aligned cell. Sections replay only the overlapping cells. */
final class OreDistribution {
    static final int CELL = 12;
    private static final long ORE_SALT = 0x9E3779B97F4A7C15L;
    private final long seed;
    private final Map<OreKind, OreRule> rules;
    private final Map<Block, Block> replaced = new IdentityHashMap<>();

    OreDistribution(long seed, Map<OreKind, OreRule> rules) {
        this.seed = seed;
        this.rules = rules;
        for (var ore : rules.keySet()) {
            replaced.put(ore.stone(), Blocks.STONE);
            replaced.put(ore.slate(), Blocks.DEEPSLATE);
        }
        if (rules.containsKey(OreKind.IRON)) {
            replaced.put(Blocks.RAW_IRON_BLOCK, Blocks.DEEPSLATE);
        }
        if (rules.containsKey(OreKind.COPPER)) {
            replaced.put(Blocks.RAW_COPPER_BLOCK, Blocks.STONE);
        }
    }

    boolean hasRules() {
        return !rules.isEmpty();
    }

    boolean replaces(Block block) {
        return replaced.containsKey(block);
    }

    void applyToNativeTerrain(ProtoChunk chunk) {
        if (rules.isEmpty()) {
            return;
        }
        for (int i = 0; i < chunk.getSections().length; i++) {
            var section = chunk.getSections()[i];
            if (!section.hasOnlyAir()) {
                removeNoiseVeins(section);
                place(section, chunk.getPos().getMinBlockX(), SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(i)),
                        chunk.getPos().getMinBlockZ());
            }
        }
    }

    private void removeNoiseVeins(LevelChunkSection section) {
        if (!section.maybeHas(state -> replaces(state.getBlock()))) {
            return;
        }
        for (int y = 0; y < SectionPos.SECTION_SIZE; y++) {
            for (int z = 0; z < SectionPos.SECTION_SIZE; z++) {
                for (int x = 0; x < SectionPos.SECTION_SIZE; x++) {
                    var base = replaced.get(section.getBlockState(x, y, z).getBlock());
                    if (base != null) {
                        section.setBlockState(x, y, z, base.defaultBlockState(), false);
                    }
                }
            }
        }
    }

    void place(LevelChunkSection section, int x0, int y0, int z0) {
        for (var entry : rules.entrySet()) {
            var rule = entry.getValue();
            int last = SectionPos.SECTION_SIZE - 1;
            if (rule.maxY() < y0 || rule.minY() > y0 + last) {
                continue;
            }
            int r = rule.radius();
            long salt = seed ^ (ORE_SALT * entry.getKey().getSerializedName().hashCode());
            for (int y = Math.floorDiv(y0 - r, CELL); y <= Math.floorDiv(y0 + last + r, CELL); y++) {
                for (int z = Math.floorDiv(z0 - r, CELL); z <= Math.floorDiv(z0 + last + r, CELL); z++) {
                    for (int x = Math.floorDiv(x0 - r, CELL); x <= Math.floorDiv(x0 + last + r, CELL); x++) {
                        var random = new XoroshiroRandomSource(salt ^ Mth.getSeed(x, y, z));
                        int cy = y * CELL + random.nextInt(CELL);
                        if (random.nextDouble() * DeepCaveSettings.PERCENT < rule.percent(cy)) {
                            vein(section, entry.getKey(), rule, x * CELL + random.nextInt(CELL) - x0, cy - y0, z * CELL + random.nextInt(CELL) - z0,
                                    y0, random);
                        }
                    }
                }
            }
        }
    }

    private static void vein(LevelChunkSection section, OreKind ore, OreRule rule, int cx, int cy, int cz, int y0, XoroshiroRandomSource random) {
        int rx = rule.radius(), ry = 1 + random.nextInt(rx), rz = 1 + random.nextInt(rx);
        int last = SectionPos.SECTION_SIZE - 1;
        for (int y = Math.max(0, cy - ry); y <= Math.min(last, cy + ry); y++) {
            if (rule.percent(y0 + y) <= 0) {
                continue;
            }
            for (int z = Math.max(0, cz - rz); z <= Math.min(last, cz + rz); z++) {
                for (int x = Math.max(0, cx - rx); x <= Math.min(last, cx + rx); x++) {
                    if (Mth.square((double) (x - cx) / rx) + Mth.square((double) (y - cy) / ry) + Mth.square((double) (z - cz) / rz) <= 1) {
                        var host = section.getBlockState(x, y, z);
                        if (host.is(BlockTags.DEEPSLATE_ORE_REPLACEABLES)) {
                            section.setBlockState(x, y, z, ore.slate().defaultBlockState(), false);
                        } else if (host.is(BlockTags.STONE_ORE_REPLACEABLES) || host.is(Blocks.CALCITE)) {
                            section.setBlockState(x, y, z, ore.stone().defaultBlockState(), false);
                        }
                    }
                }
            }
        }
    }
}
