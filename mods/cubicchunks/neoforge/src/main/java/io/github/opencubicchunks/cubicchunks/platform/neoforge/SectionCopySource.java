package io.github.opencubicchunks.cubicchunks.platform.neoforge;

import net.minecraft.world.level.BlockGetter;

/** The block source retained by NeoForge's rendering snapshot. */
public interface SectionCopySource {
    BlockGetter cc_getSource();
}
