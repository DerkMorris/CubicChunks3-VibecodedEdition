package io.github.opencubicchunks.cubicchunks.client.gui.render.state.pip;

import javax.annotation.Nullable;

import io.github.opencubicchunks.cubicchunks.client.gui.render.pip.WorldLoadingCubeStatusesRenderer;
import io.github.opencubicchunks.cubicchunks.server.level.progress.CubeLoadingSnapshot;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;

/**
 * PiP render state for {@link WorldLoadingCubeStatusesRenderer}.
 * Stores an immutable {@link CubeLoadingSnapshot} of the world load progress for this frame.
 */
public record WorldLoadingCubeStatusesRenderState(
        CubeLoadingSnapshot chunkProgressListener, int x0, int y0, int x1, int y1, float scale, @Nullable ScreenRectangle scissorArea,
        @Nullable ScreenRectangle bounds
) implements PictureInPictureRenderState {
    public WorldLoadingCubeStatusesRenderState(
            CubeLoadingSnapshot chunkProgressListener, int x0, int y0, int x1, int y1, float scale, @Nullable ScreenRectangle scissorArea
    ) {
        this(chunkProgressListener, x0, y0, x1, y1, scale, scissorArea, PictureInPictureRenderState.getBounds(x0, y0, x1, y1, scissorArea));
    }
}
