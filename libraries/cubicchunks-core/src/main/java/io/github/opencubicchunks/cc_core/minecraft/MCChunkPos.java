package io.github.opencubicchunks.cc_core.minecraft;

import io.github.opencubicchunks.javaheaders.api.Header;

@Header
public class MCChunkPos {
    public MCChunkPos(int x, int z) {
        throw new IllegalStateException("Per-version doesn't overwrite method");
    }

    public native int x();
    public native int z();

    public native long pack();
    public native static long pack(int x, int z);

    public native static MCChunkPos unpack(long packedPos);

    public native static int getX(long chunkAsLong);
    public native static int getZ(long chunkAsLong);
}
