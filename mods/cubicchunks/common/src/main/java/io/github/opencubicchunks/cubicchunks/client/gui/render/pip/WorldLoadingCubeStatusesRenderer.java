package io.github.opencubicchunks.cubicchunks.client.gui.render.pip;

import java.util.EnumSet;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import io.github.opencubicchunks.cc_core.api.CubicConstants;
import io.github.opencubicchunks.cubicchunks.client.gui.render.state.pip.WorldLoadingCubeStatusesRenderState;
import io.github.opencubicchunks.cubicchunks.client.gui.screens.CubicLevelLoadingScreen;
import io.github.opencubicchunks.cubicchunks.server.level.progress.CubeLoadingSnapshot;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.Direction;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.joml.Vector3f;

/**
 * Picture-in-Picture renderer used to render the 3d loading animation used when loading into a cubic world.
 */
public class WorldLoadingCubeStatusesRenderer extends PictureInPictureRenderer<WorldLoadingCubeStatusesRenderState> {
    public static final RenderType WORLD_LOADING_CUBE_STATUSES = RenderTypes.debugQuads();

    private static final float VIEWPORT_FRACTION = 0.6F;

    /** Rotation of the loading animation about the X axis (rotating "downwards" towards the camera) */
    private static final float X_ROTATION_DEGREES = 30F;

    private static final double Y_ROTATION_DEGREES_PER_SECOND = 40;

    /** Constant controlling the size at which cubes are rendered */
    private static final float CUBE_BASE_SCALE = 0.12F;

    private static final int CHUNK_ALPHA = 0x80;

    private static final int DARKEN_PERCENTAGE_CUBE_FRONT_BACK = 20;
    private static final int DARKEN_PERCENTAGE_CUBE_SIDES = 30;
    private static final int DARKEN_PERCENTAGE_CUBE_BOTTOM = 40;

    @Override public Class<WorldLoadingCubeStatusesRenderState> getRenderStateClass() {
        return WorldLoadingCubeStatusesRenderState.class;
    }

    @SuppressWarnings("checkstyle:MagicNumber") // millis to seconds; degrees modulo 360
    private static float yRotationAngleDegrees(long milliseconds) {
        return (float) ((milliseconds * (Y_ROTATION_DEGREES_PER_SECOND / 1000)) % 360);
    }

    @Override protected void renderToTexture(WorldLoadingCubeStatusesRenderState state, PoseStack poseStack, SubmitNodeCollector collector) {
        int width = state.x1() - state.x0();
        int height = state.y1() - state.y0();
        float extent = CUBE_BASE_SCALE * CubicConstants.DIAMETER_IN_SECTIONS * state.chunkProgressListener().cubeDiameter();
        float pixelsPerUnit = Math.min(width, height) * VIEWPORT_FRACTION / extent;
        // Use the PiP-owned projection and render pass in 26.3. Its pose is already centered
        // horizontally and scaled for GUI resolution; flip Y for the cubic world coordinates.
        poseStack.scale(pixelsPerUnit, -pixelsPerUnit, pixelsPerUnit);
        poseStack.rotateAround(Axis.XP.rotationDegrees(X_ROTATION_DEGREES), 0.0F, 0.0F, 0.0F);
        poseStack.rotateAround(Axis.YP.rotationDegrees(yRotationAngleDegrees(System.currentTimeMillis())), 0.0F, 0.0F, 0.0F);
        collector.submitCustomGeometry(poseStack, WORLD_LOADING_CUBE_STATUSES, (pose, vertexConsumer) -> renderCubesAndChunks(vertexConsumer, pose,
                state.chunkProgressListener(), CubicConstants.DIAMETER_IN_SECTIONS));
    }

    @Override protected float getTranslateY(int height, int guiScale) {
        return height / 2.0F;
    }

    @Override protected String getTextureLabel() {
        return "world loading cube statuses";
    }

    private static void renderCubesAndChunks(
            VertexConsumer vertexConsumer, PoseStack.Pose poseStack, CubeLoadingSnapshot progressListener, float scale
    ) {
        int sectionRenderRadius = progressListener.columnDiameter();
        drawChunks(vertexConsumer, poseStack, progressListener, scale, sectionRenderRadius);
        drawCubes(vertexConsumer, poseStack, progressListener, scale, progressListener.cubeDiameter());

    }

    private static void drawChunks(
            VertexConsumer vertexConsumer, PoseStack.Pose poseStack, CubeLoadingSnapshot progressListener, float scale, int sectionRenderRadius
    ) {
        for (int cdx = 0; cdx < sectionRenderRadius; cdx++) {
            for (int cdz = 0; cdz < sectionRenderRadius; cdz++) {
                ChunkStatus columnStatus = progressListener.get(cdx, cdz);
                if (columnStatus == null) {
                    continue;
                }
                int color = ARGB.color(CHUNK_ALPHA,
                        CubicLevelLoadingScreen.STATUS_COLORS.getOrDefault(columnStatus, CubicLevelLoadingScreen.DEFAULT_STATUS_COLOR));
                // We render the chunks underneath the cubes by rendering a smaller cube with only the top face visible
                float columnY = -(progressListener.cubeDiameter() / 2.0F + 1.0F) * CubicConstants.DIAMETER_IN_SECTIONS;
                drawCube(vertexConsumer, poseStack, cdx - (float) sectionRenderRadius / 2, columnY, cdz - (float) sectionRenderRadius / 2,
                        CUBE_BASE_SCALE * scale / CubicConstants.DIAMETER_IN_SECTIONS, color, EnumSet.of(Direction.UP));
            }
        }
    }

    private static void drawCubes(
            VertexConsumer vertexConsumer, PoseStack.Pose poseStack, CubeLoadingSnapshot progressListener, float scale, int cubeRenderRadius
    ) {
        EnumSet<Direction> renderFaces = EnumSet.noneOf(Direction.class);

        var tracker = progressListener;
        for (int dx = 0; dx < cubeRenderRadius; dx++) {
            for (int dz = 0; dz < cubeRenderRadius; dz++) {
                for (int dy = 0; dy < cubeRenderRadius; dy++) {
                    ChunkStatus status = tracker.cc_getStatus(dx, dy, dz);
                    if (status == null) {
                        continue;
                    }
                    renderFaces.clear();
                    float alpha = CubicLevelLoadingScreen.STATUS_ALPHAS.getValue(status.getIndex());
                    int color = ARGB.color(alpha,
                            CubicLevelLoadingScreen.STATUS_COLORS.getOrDefault(status, CubicLevelLoadingScreen.DEFAULT_STATUS_COLOR));
                    for (Direction value : Direction.values()) {
                        ChunkStatus cubeStatus = tracker.cc_getStatus(dx + value.getStepX(), dy + value.getStepY(), dz + value.getStepZ());
                        if (cubeStatus == null || !cubeStatus.isOrAfter(status)) {
                            renderFaces.add(value);
                        }
                    }
                    drawCube(vertexConsumer, poseStack, dx - (float) cubeRenderRadius / 2, dy - (float) cubeRenderRadius / 2,
                            dz - (float) cubeRenderRadius / 2, CUBE_BASE_SCALE * scale, color, renderFaces);
                }
            }
        }
    }

    private static void drawCube(
            VertexConsumer vertexConsumer, PoseStack.Pose poseStack, float x, float y, float z, float scale, int color, EnumSet<Direction> renderFaces
    ) {
        float x0 = x * scale;
        float x1 = x0 + scale;
        float y0 = y * scale;
        float y1 = y0 + scale;
        float z0 = z * scale;
        float z1 = z0 + scale;
        if (renderFaces.contains(Direction.UP)) {
            // up face
            vertex(vertexConsumer, poseStack, x0, y1, z0, 0, 1, 0, color);
            vertex(vertexConsumer, poseStack, x0, y1, z1, 0, 1, 0, color);
            vertex(vertexConsumer, poseStack, x1, y1, z1, 0, 1, 0, color);
            vertex(vertexConsumer, poseStack, x1, y1, z0, 0, 1, 0, color);
        }
        if (renderFaces.contains(Direction.DOWN)) {
            int c = darken(color, DARKEN_PERCENTAGE_CUBE_BOTTOM);
            // down face
            vertex(vertexConsumer, poseStack, x1, y0, z0, 0, -1, 0, c);
            vertex(vertexConsumer, poseStack, x1, y0, z1, 0, -1, 0, c);
            vertex(vertexConsumer, poseStack, x0, y0, z1, 0, -1, 0, c);
            vertex(vertexConsumer, poseStack, x0, y0, z0, 0, -1, 0, c);
        }
        if (renderFaces.contains(Direction.EAST)) {
            int c = darken(color, DARKEN_PERCENTAGE_CUBE_SIDES);
            // right face
            vertex(vertexConsumer, poseStack, x1, y1, z0, 1, 0, 0, c);
            vertex(vertexConsumer, poseStack, x1, y1, z1, 1, 0, 0, c);
            vertex(vertexConsumer, poseStack, x1, y0, z1, 1, 0, 0, c);
            vertex(vertexConsumer, poseStack, x1, y0, z0, 1, 0, 0, c);
        }
        if (renderFaces.contains(Direction.WEST)) {
            int c = darken(color, DARKEN_PERCENTAGE_CUBE_SIDES);
            // left face
            vertex(vertexConsumer, poseStack, x0, y0, z0, -1, 0, 0, c);
            vertex(vertexConsumer, poseStack, x0, y0, z1, -1, 0, 0, c);
            vertex(vertexConsumer, poseStack, x0, y1, z1, -1, 0, 0, c);
            vertex(vertexConsumer, poseStack, x0, y1, z0, -1, 0, 0, c);
        }
        if (renderFaces.contains(Direction.NORTH)) {
            int c = darken(color, DARKEN_PERCENTAGE_CUBE_FRONT_BACK);
            // front face (facing camera)
            vertex(vertexConsumer, poseStack, x0, y1, z0, 0, 0, -1, c);
            vertex(vertexConsumer, poseStack, x1, y1, z0, 0, 0, -1, c);
            vertex(vertexConsumer, poseStack, x1, y0, z0, 0, 0, -1, c);
            vertex(vertexConsumer, poseStack, x0, y0, z0, 0, 0, -1, c);
        }
        if (renderFaces.contains(Direction.SOUTH)) {
            int c = darken(color, DARKEN_PERCENTAGE_CUBE_FRONT_BACK);
            // back face
            vertex(vertexConsumer, poseStack, x0, y0, z1, 0, 0, 1, c);
            vertex(vertexConsumer, poseStack, x1, y0, z1, 0, 0, 1, c);
            vertex(vertexConsumer, poseStack, x1, y1, z1, 0, 0, 1, c);
            vertex(vertexConsumer, poseStack, x0, y1, z1, 0, 0, 1, c);
        }
    }

    @SuppressWarnings("checkstyle:MagicNumber") // 100 for percentage
    private static int darken(int color, int percentage) {
        int r = ARGB.red(color);
        r -= (r * percentage) / 100;
        int g = ARGB.green(color);
        g -= (g * percentage) / 100;
        int b = ARGB.blue(color);
        b -= (b * percentage) / 100;
        return ARGB.color(ARGB.alpha(color), r, g, b);
    }

    // TODO do we have any use for the normal vector? it's currently unused
    private static void vertex(VertexConsumer vertexConsumer, PoseStack.Pose pose, float x, float y, float z, int nx, int ny, int nz, int color) {
        var vec = new Vector3f();
        pose.pose().transformPosition(x, y, z, vec);

        vertexConsumer.addVertex(vec.x, vec.y, vec.z).setColor(color);
    }
}
