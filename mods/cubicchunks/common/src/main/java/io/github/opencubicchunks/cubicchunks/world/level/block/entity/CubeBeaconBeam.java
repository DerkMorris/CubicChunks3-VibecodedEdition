package io.github.opencubicchunks.cubicchunks.world.level.block.entity;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.mixin.core.common.world.level.blockentity.BeaconSectionAccess;
import io.github.opencubicchunks.cubicchunks.world.level.CubicBlockBounds;
import io.github.opencubicchunks.cubicchunks.world.level.levelgen.heightmap.CubeColumnSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.block.BeaconBeamBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BeaconBeamOwner;
import net.minecraft.world.level.block.state.BlockState;

/** Incremental native beam rules over authoritative sparse terrain; only proven AIR intervals may be skipped. */
public final class CubeBeaconBeam {
    private static final int CELLS_PER_TICK = 10;
    private static final int OPAQUE_DAMPENING = 15;
    private @Nullable CubeColumnSnapshot column;
    private List<BeaconBeamOwner.Section> sections = new ArrayList<>();
    private int nextY;
    private int lastNonAirY;

    public boolean needsColumn() {
        return column == null;
    }

    /** Null means an unfinished ten-cell scan; an empty result means blocked or unresolved terrain. */
    public @Nullable List<BeaconBeamOwner.Section> advance(@Nullable CubeColumnSnapshot snapshot, BlockPos beacon) {
        if (column == null) {
            if (snapshot == null) {
                return List.of();
            }
            column = snapshot;
            nextY = beacon.getY();
            lastNonAirY = nextY - 1;
            sections = new ArrayList<>();
        }
        for (int examined = 0; examined < CELLS_PER_TICK; examined++) {
            if (nextY > CubicBlockBounds.MAX || !skipKnownAir()) {
                return finish();
            }
            BlockState state = column.state(beacon.getX(), nextY, beacon.getZ());
            if (state == null || !consume(state)) {
                sections.clear();
                return finish();
            }
            nextY++;
        }
        return null;
    }

    private boolean skipKnownAir() {
        int cy = Coords.blockToCube(nextY);
        if (column.cubes().containsKey(cy) || column.unknown().contains(cy) || nextY <= column.maxGeneratedY()) {
            return true;
        }
        Integer nextCube = column.cubes().ceilingKey(cy);
        for (int unresolved : column.unknown()) {
            if (unresolved >= cy && (nextCube == null || unresolved < nextCube)) {
                nextCube = unresolved;
            }
        }
        if (nextCube == null) {
            return false;
        }
        int nextBlock = Coords.cubeToMinBlock(nextCube);
        grow(nextBlock - nextY);
        nextY = nextBlock;
        return true;
    }

    private boolean consume(BlockState state) {
        if (!state.isAir()) {
            lastNonAirY = nextY;
        }
        if (state.getBlock() instanceof BeaconBeamBlock tinted) {
            color(tinted.getColor().getTextureDiffuseColor());
        } else if (sections.isEmpty() || state.getLightDampening() >= OPAQUE_DAMPENING && !state.is(Blocks.BEDROCK)) {
            return false;
        } else {
            grow(1);
        }
        return true;
    }

    private void color(int color) {
        if (sections.size() <= 1) {
            sections.add(new BeaconBeamOwner.Section(color));
            return;
        }
        BeaconBeamOwner.Section last = sections.getLast();
        if (last.getColor() == color) {
            grow(1);
        } else {
            sections.add(new BeaconBeamOwner.Section(ARGB.average(last.getColor(), color)));
        }
    }

    private void grow(int height) {
        if (!sections.isEmpty()) {
            BeaconBeamOwner.Section last = sections.getLast();
            ((BeaconSectionAccess) last).cc_setHeight(last.getHeight() + height);
        }
    }

    private List<BeaconBeamOwner.Section> finish() {
        if (!sections.isEmpty()) {
            // WORLD_SURFACE's native endpoint includes one AIR cell above the highest occupied cell.
            // Paletted cubes contain trailing AIR, which must not lengthen the final colored section.
            grow(Math.min(CubicBlockBounds.MAX + 1, lastNonAirY + 2) - nextY);
        }
        List<BeaconBeamOwner.Section> result = List.copyOf(sections);
        column = null;
        return result;
    }

    public static int[] encode(List<BeaconBeamOwner.Section> sections) {
        int[] values = new int[sections.size() * 2];
        for (int i = 0; i < sections.size(); i++) {
            values[i * 2] = sections.get(i).getColor();
            values[i * 2 + 1] = sections.get(i).getHeight();
        }
        return values;
    }

    public static List<BeaconBeamOwner.Section> decode(int[] values) {
        if (values.length % 2 != 0) {
            return List.of();
        }
        List<BeaconBeamOwner.Section> sections = new ArrayList<>();
        long height = 0;
        for (int i = 0; i < values.length; i += 2) {
            height += values[i + 1];
            if (values[i + 1] <= 0 || height > (long) CubicBlockBounds.MAX - CubicBlockBounds.MIN + 1) {
                return List.of();
            }
            var section = new BeaconBeamOwner.Section(values[i]);
            ((BeaconSectionAccess) section).cc_setHeight(values[i + 1]);
            sections.add(section);
        }
        return List.copyOf(sections);
    }
}
