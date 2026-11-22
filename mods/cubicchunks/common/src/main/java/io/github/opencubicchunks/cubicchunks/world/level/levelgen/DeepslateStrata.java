package io.github.opencubicchunks.cubicchunks.world.level.levelgen;

import java.util.IdentityHashMap;
import java.util.Map;

import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;

/** Changes natural rock before decorations; no saved blocks or structure palettes are rewritten. */
final class DeepslateStrata {
    static final int VANILLA_START = 8;
    static final int TRANSITION = 8;
    private static final long SALT = 0x6A09E667F3BCC909L;
    private static final Map<Block, OreKind> ORES = oreKinds();
    private final long seed;
    private final int start;

    DeepslateStrata(long seed, int start) {
        this.seed = seed;
        this.start = start;
    }

    private static Map<Block, OreKind> oreKinds() {
        Map<Block, OreKind> result = new IdentityHashMap<>();
        for (var ore : OreKind.values()) {
            result.put(ore.stone(), ore);
            result.put(ore.slate(), ore);
        }
        return result;
    }

    boolean custom() {
        return start != VANILLA_START;
    }

    Block rock(int x, int y, int z) {
        long depth = (long) start - y;
        if (depth <= 0) {
            return Blocks.STONE;
        }
        if (depth >= TRANSITION) {
            return Blocks.DEEPSLATE;
        }
        var random = new XoroshiroRandomSource(seed ^ SALT ^ Mth.getSeed(x, y, z));
        return random.nextFloat() < (float) depth / TRANSITION ? Blocks.DEEPSLATE : Blocks.STONE;
    }

    BlockState remap(BlockState original, int x, int y, int z) {
        var ore = ORES.get(original.getBlock());
        if (!original.is(Blocks.STONE) && !original.is(Blocks.DEEPSLATE) && ore == null) {
            return original;
        }
        var rock = rock(x, y, z);
        var target = ore == null ? rock : rock == Blocks.DEEPSLATE ? ore.slate() : ore.stone();
        if (original.is(target)) {
            return original;
        }
        var result = target.defaultBlockState();
        return original.hasProperty(BlockStateProperties.LIT)
                ? result.setValue(BlockStateProperties.LIT, original.getValue(BlockStateProperties.LIT)) : result;
    }

    void applyNative(ProtoChunk chunk) {
        // Retain the exact vanilla positional random transition when the requested height is unchanged.
        if (!custom()) {
            return;
        }
        for (int i = 0; i < chunk.getSections().length; i++) {
            apply(chunk.getSections()[i], chunk.getPos().getMinBlockX(), SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(i)),
                    chunk.getPos().getMinBlockZ());
        }
    }

    void apply(LevelChunkSection section, int x0, int y0, int z0) {
        if (!section.maybeHas(state -> state.is(Blocks.STONE) || state.is(Blocks.DEEPSLATE) || ORES.containsKey(state.getBlock()))) {
            return;
        }
        for (int y = 0; y < SectionPos.SECTION_SIZE; y++) {
            for (int z = 0; z < SectionPos.SECTION_SIZE; z++) {
                for (int x = 0; x < SectionPos.SECTION_SIZE; x++) {
                    var original = section.getBlockState(x, y, z);
                    var replacement = remap(original, x0 + x, y0 + y, z0 + z);
                    if (replacement != original) {
                        section.setBlockState(x, y, z, replacement, false);
                    }
                }
            }
        }
    }
}
