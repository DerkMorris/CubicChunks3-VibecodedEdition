package io.github.opencubicchunks.smoke;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.CubeWeatherClient;
import io.github.opencubicchunks.cubicchunks.network.CCClientboundWeatherColumnPacket;
import io.github.opencubicchunks.cubicchunks.world.level.CubicLevelReader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;

/** Real packet receipt and native weather extraction around the live scene, including a roof outside client cube range. */
final class WeatherRenderingChecks {
    private final ServerLevel level;
    private final int y;
    private final WeatherEffectRenderer renderer = new WeatherEffectRenderer();
    private CompletableFuture<Void> action;
    private int stage;

    WeatherRenderingChecks(ServerLevel level, int y) {
        this.level = level;
        this.y = y;
    }

    boolean tick(Minecraft client) {
        var columns = ((CubeWeatherClient) client.level).cc_weatherColumns();
        int open = columns.height(70, 20);
        int covered = columns.height(88, 20);
        if (open == CCClientboundWeatherColumnPacket.UNKNOWN || covered == CCClientboundWeatherColumnPacket.UNKNOWN) {
            return false;
        }
        if (y < 0) {
            if (open <= 0 || !at(client, 70, 20).isEmpty()) {
                throw new IllegalStateException("Buried weather rendered through authoritative terrain");
            }
            System.out.println("CC_WEATHER_RENDER y=" + y + " buriedSuppressed=true metadataFullHeight=true");
            return true;
        }
        if (action != null) {
            if (!action.isDone()) {
                return false;
            }
            action.join();
        }
        if (stage == 0) {
            if (!localSurface(open) || covered < y + 5) {
                return false;
            }
            var openDraw = at(client, 70, 20);
            var coveredDraw = at(client, 88, 20);
            if (openDraw.isEmpty()) {
                return false;
            }
            if (openDraw.stream().anyMatch(column -> column.bottomY() < open)
                    || coveredDraw.stream().anyMatch(column -> column.bottomY() < covered)) {
                throw new IllegalStateException("Native weather geometry crossed the glass roof or ground");
            }
            stage = 1;
            action = CompletableFuture.runAsync(() -> roof(true), client.getSingleplayerServer());
            return false;
        }
        if (stage == 1) {
            if (open != roof().getY() + 1) {
                return false;
            }
            if (!at(client, 70, 20).isEmpty()) {
                throw new IllegalStateException("Distant glass roof did not hide native precipitation");
            }
            System.out.println(
                    "CC_WEATHER_RENDER_REMOTE y=" + y + " height=" + open + " glassRoof=true noLocalRoofCube=true nativeExtractBlocked=true");
            stage = 2;
            action = CompletableFuture.runAsync(() -> roof(false), client.getSingleplayerServer());
            return false;
        }
        if (!localSurface(open) || at(client, 70, 20).isEmpty()) {
            return false;
        }
        System.out.println("CC_WEATHER_RENDER y=" + y
                + " open=true glassClipped=true distantRoof=true recovered=true metadataFullHeight=true nativeExtract=true");
        return true;
    }

    private boolean localSurface(int height) {
        return height >= y && height <= y + 2;
    }

    private BlockPos roof() {
        return new BlockPos(70, y + 1000000, 20);
    }

    private void roof(boolean present) {
        CubePos cube = CubePos.from(roof());
        ((CubicLevelReader) level).cc_getCube(cube.getX(), cube.getY(), cube.getZ(), ChunkStatus.FULL, true);
        level.setBlock(roof(), (present ? Blocks.GLASS : Blocks.AIR).defaultBlockState(), 2);
    }

    private List<WeatherEffectRenderer.ColumnInstance> at(Minecraft client, int x, int z) {
        var state = new WeatherRenderState();
        renderer.extractRenderState(client.level, 1, new Vec3(x, y + 1, z), state);
        return Stream.concat(state.rainColumns.stream(), state.snowColumns.stream()).filter(column -> column.x() == x && column.z() == z).toList();
    }
}
