package io.github.opencubicchunks.cubicchunks.client.gui.screens;

import java.util.List;
import java.util.Locale;

import io.github.opencubicchunks.cc_core.CubicChunksBase;
import io.github.opencubicchunks.cubicchunks.client.gui.render.pip.WorldLoadingCubeStatusesRenderer;
import io.github.opencubicchunks.cubicchunks.client.gui.render.state.pip.WorldLoadingCubeStatusesRenderState;
import io.github.opencubicchunks.cubicchunks.figureoutwheretoputthislater.UserFunction;
import io.github.opencubicchunks.cubicchunks.server.level.progress.CubeLoadingSnapshot;
import it.unimi.dsi.fastutil.objects.Object2IntArrayMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.server.level.progress.ChunkLoadStatusView;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * When loading a cubic world, we replace the chunk visualization of the vanilla loading screen with our own 3d visualization,
 * and display a color key for the chunk status colors.
 * <br/>
 * <br/>
 * See also: {@link WorldLoadingCubeStatusesRenderer}, the PiP renderer used to render the cube loading visualization.
 */
public class CubicLevelLoadingScreen {
    private CubicLevelLoadingScreen() {}

    public static final Object2IntMap<ChunkStatus> STATUS_COLORS = Object2IntMaps.unmodifiable(getStatusColors());

    public static final int DEFAULT_STATUS_COLOR = 0xFF00FF;

    private static final int COLOR_KEY_SQUARE_SIZE = 11;
    private static final int COLOR_KEY_MARGIN = 3;

    // Alpha values are arbitrary; current values were chosen for visibility of sections while loading
    @SuppressWarnings("checkstyle:MagicNumber")
    public static final UserFunction STATUS_ALPHAS = UserFunction.builder().point(0, 0.13f).point(ChunkStatus.STRUCTURE_STARTS.getIndex(), 0.26f)
            .point(ChunkStatus.BIOMES.getIndex(), 0.37f).point(ChunkStatus.TERRAIN.getIndex(), 0.74f).point(ChunkStatus.FEATURES.getIndex(), 0.3f)
            .point(ChunkStatus.FULL.getIndex(), 1).build();

    @SuppressWarnings({ "unused", "checkstyle:MagicNumber" }) // This method is just here so intellij shows the status colors when viewing this file
    private static void unusedColors() {
        //@formatter:off
        /* MINECRAFT:EMPTY: */                  new java.awt.Color(0xFF00FF);
        /* MINECRAFT:STRUCTURE_STARTS: */       new java.awt.Color(0x444444);
        /* MINECRAFT:STRUCTURE_REFERENCES: */   new java.awt.Color(0xFF0000);
        /* MINECRAFT:BIOMES: */                 new java.awt.Color(0xFFAA00);
        /* MINECRAFT:NOISE: */                  new java.awt.Color(0xA9FF00);
        /* MINECRAFT:SURFACE: */                new java.awt.Color(0x00FF00);
        /* MINECRAFT:CARVERS: */                new java.awt.Color(0x00FFAA);
        /* MINECRAFT:FEATURES: */               new java.awt.Color(0x00A9FF);
        /* MINECRAFT:INITIALIZE_LIGHT: */       new java.awt.Color(0x0000FF);
        /* MINECRAFT:LIGHT: */                  new java.awt.Color(0xAA00FF);
        /* MINECRAFT:SPAWN: */                  new java.awt.Color(0xFF00A9);
        /* MINECRAFT:FULL: */                   new java.awt.Color(0xFFFFFF);
        //@formatter:on
    }

    @SuppressWarnings("checkstyle:MagicNumber") // colors in here are somewhat arbitrary
    public static Object2IntArrayMap<ChunkStatus> getStatusColors() {
        Object2IntArrayMap<ChunkStatus> map = new Object2IntArrayMap<>();
        List<ChunkStatus> statusList = ChunkStatus.getStatusList();

        map.put(statusList.get(0), 0xFF00FF);
        CubicChunksBase.LOGGER.debug(statusList.get(0) + ": 0x" + Integer.toHexString(0xFF00FF).toUpperCase(Locale.ROOT));
        map.put(statusList.get(1), 0x444444);
        CubicChunksBase.LOGGER.debug(statusList.get(1) + ": 0x" + Integer.toHexString(0x444444).toUpperCase(Locale.ROOT));

        for (int i = 2; i < statusList.size() - 1; i++) {
            ChunkStatus chunkStatus = statusList.get(i);
            int v = Mth.hsvToRgb((float) (i - 2) / (statusList.size() - 3), 1, 1);
            map.put(chunkStatus, v);
            CubicChunksBase.LOGGER.debug(chunkStatus + ": 0x" + Integer.toHexString(v).toUpperCase(Locale.ROOT));
        }

        map.put(statusList.get(statusList.size() - 1), 0xFFFFFF);
        CubicChunksBase.LOGGER.debug(statusList.get(statusList.size() - 1) + ": 0x" + Integer.toHexString(0xFFFFFF).toUpperCase(Locale.ROOT));

        return map;
    }

    // Called from MixinLevelLoadingScreen when the chunk diagram would be rendered
    public static void doRender(GuiGraphicsExtractor guiGraphics, ChunkLoadStatusView progressListener) {
        CubeLoadingSnapshot snapshot = CubeLoadingSnapshot.capture(progressListener);
        int statusCount = ChunkStatus.getStatusList().size();
        int[] chunkStatusCounts = new int[statusCount];
        int[] cubeStatusCounts = new int[statusCount];

        int sectionRenderRadius = snapshot.columnDiameter();
        countChunks(snapshot, chunkStatusCounts, sectionRenderRadius);
        countCubes(snapshot, cubeStatusCounts, snapshot.cubeDiameter());

        renderColorKey(guiGraphics, chunkStatusCounts, cubeStatusCounts);
        guiGraphics.guiRenderState.addPicturesInPictureState(
                new WorldLoadingCubeStatusesRenderState(snapshot, 0, 0, guiGraphics.guiWidth(), guiGraphics.guiHeight(), 1, null));
    }

    private static void countChunks(CubeLoadingSnapshot progressListener, int[] chunkStatusCounts, int sectionRenderRadius) {
        for (int cdx = 0; cdx < sectionRenderRadius; cdx++) {
            for (int cdz = 0; cdz < sectionRenderRadius; cdz++) {
                ChunkStatus status = progressListener.get(cdx, cdz);
                if (status == null) {
                    continue;
                }
                chunkStatusCounts[status.getIndex()]++;
            }
        }
    }

    private static void countCubes(CubeLoadingSnapshot progressListener, int[] cubeStatusCounts, int cubeRenderRadius) {
        var tracker = progressListener;
        for (int dx = 0; dx < cubeRenderRadius; dx++) {
            for (int dz = 0; dz < cubeRenderRadius; dz++) {
                for (int dy = 0; dy < cubeRenderRadius; dy++) {
                    ChunkStatus status = tracker.cc_getStatus(dx, dy, dz);
                    if (status == null) {
                        continue;
                    }
                    cubeStatusCounts[status.getIndex()]++;
                }
            }
        }
    }

    private static void renderColorKey(GuiGraphicsExtractor guiGraphics, int[] chunkStatusCounts, int[] cubeStatusCounts) {
        var font = Minecraft.getInstance().font;

        int x = 1;
        int y = 1;

        int radius = COLOR_KEY_SQUARE_SIZE;
        int margin = COLOR_KEY_MARGIN;

        for (ChunkStatus status : ChunkStatus.getStatusList()) {
            if (status == ChunkStatus.EMPTY) {
                continue;
            }
            // TODO maybe don't show counts outside of dev? it's a lot of clutter and users probably don't need it
            // - or just make it configurable. Maybe have an option to disable the color key entirely
            var statusLabel = status.getName().substring("minecraft:".length()) + " (" + chunkStatusCounts[status.getIndex()] + ", "
                    + cubeStatusCounts[status.getIndex()] + ")";
            guiGraphics.text(font, statusLabel, x + radius + margin, y + 2, ARGB.white(1));
            int color = ARGB.opaque(STATUS_COLORS.getOrDefault(status, DEFAULT_STATUS_COLOR));
            int colorWithAlpha = ARGB.color(STATUS_ALPHAS.getValue(status.getIndex()), color);
            guiGraphics.fill(x, y, x + radius, y + radius, colorWithAlpha);
            guiGraphics.outline(x, y, radius, radius, color);

            y += radius + margin;
        }
    }
}
