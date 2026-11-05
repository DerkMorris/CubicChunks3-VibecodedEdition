package io.github.opencubicchunks.cubicchunks.world.level.levelgen.structure;

import java.util.function.BiConsumer;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Continuation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.blockscan.BlockMatcher;
import net.minecraft.world.level.blockscan.BlockStateConsumer;

/** Structure capture must include air as well as occupied sections, without column height clipping. */
public final class CubeTemplateBlockMatcher extends BlockMatcher {
    private final BlockPos min;
    private final BlockPos max;
    private final CubeTemplateArea area;

    public CubeTemplateBlockMatcher(Level level, BlockPos min, BlockPos max) {
        super(level);
        this.min = min.immutable();
        this.max = max.immutable();
        this.area = CubeTemplateArea.capture(level, min, max.subtract(min).offset(1, 1, 1))
                .orElseThrow(() -> new IllegalStateException("Structure capture requires a supported area of loaded cubes"));
    }

    @Override public boolean atLeastMatched(int n) {
        if (n <= 0) {
            return true;
        }
        int count = 0;
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (statePredicate.test(area.getBlockState(pos)) && ++count >= n) {
                return true;
            }
        }
        return false;
    }

    @Override protected boolean anyMatched(Predicate<BlockState> predicate) {
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (predicate.test(area.getBlockState(pos))) {
                return true;
            }
        }
        return false;
    }

    @Override public void forEach(BiConsumer<BlockPos, BlockState> consumer) {
        forEachUntil((pos, state) -> {
            consumer.accept(pos, state);
            return Continuation.CONTINUE;
        });
    }

    @Override public boolean forEachUntil(BlockStateConsumer consumer) {
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            BlockState state = area.getBlockState(pos);
            if (statePredicate.test(state) && consumer.apply(pos.immutable(), state).shouldAbort()) {
                return true;
            }
        }
        return false;
    }
}
