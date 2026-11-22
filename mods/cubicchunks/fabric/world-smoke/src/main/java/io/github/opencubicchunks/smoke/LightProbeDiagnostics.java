package io.github.opencubicchunks.smoke;

import javax.annotation.Nullable;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.ClientCubeCache;
import io.github.opencubicchunks.cubicchunks.world.level.lighting.CubeLightView;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;

/** Failure evidence for the delayed authoritative-light probes; never changes light or packet state. */
final class LightProbeDiagnostics {
    private LightProbeDiagnostics() {}

    static String column(Minecraft client, BlockPos pos) {
        return "[above=" + sample(client, pos.above()) + "; at=" + sample(client, pos) + "; below=" + sample(client, pos.below()) + "]";
    }

    private static String sample(Minecraft client, BlockPos pos) {
        var cache = (ClientCubeCache) client.level.getChunkSource();
        CubePos cubePos = CubePos.from(pos);
        var cube = cache.cc_getCube(cubePos.getX(), cubePos.getY(), cubePos.getZ(), false);
        return pos + " block=" + client.level.getBlockState(pos)
                + " scalar=" + client.level.getBrightness(LightLayer.BLOCK, pos) + "/" + client.level.getBrightness(LightLayer.SKY, pos)
                + " cube=" + cubePos + " identity=" + (cube == null ? "missing" : System.identityHashCode(cube))
                + " revision=" + (cube == null ? -1 : cube.cc_getLightRevision()) + " ready=" + cache.cc_isCubeLightReady(cubePos)
                + " accepted=" + view(cache.cc_getCubeLightView(cubePos), pos)
                + " visible=" + view(cache.cc_getVisibleCubeLightView(cubePos), pos);
    }

    private static String view(@Nullable CubeLightView view, BlockPos pos) {
        return view == null ? "none" : "{identity=" + System.identityHashCode(view) + ",ready=" + view.isReady()
                + ",block=" + view.sample(LightLayer.BLOCK, pos).orElse(null) + ",sky=" + view.sample(LightLayer.SKY, pos).orElse(null) + "}";
    }
}
