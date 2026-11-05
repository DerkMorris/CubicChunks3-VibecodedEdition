package io.github.opencubicchunks.smoke;

import java.util.HashMap;
import java.util.Map;

import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cubicchunks.client.multiplayer.ClientCubeCache;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.util.Mth;

/** Look around at the saved player position, then fly across a nearby section boundary in the same loaded neighborhood. */
final class CameraTurnProbe {
    private final Map<Long, SectionRenderDispatcher.RenderSection> anchors = new HashMap<>();
    private final Map<CubePos, LevelCube> resident = new HashMap<>();
    private final Phase stationary = new Phase();
    private final Phase crossing = new Phase();
    private double originX, originY, originZ, boundary;
    private boolean initialized;
    private int headings, warmup, ticks, heading, boundaryChanges, previousSectionY;
    private boolean recording;

    void register() {
        LevelRenderEvents.AFTER_OPAQUE_TERRAIN.register(context -> {
            if (!initialized) {
                return;
            }
            int drawable = 0;
            int local = 0;
            for (var section : context.levelRenderer().visibleSections()) {
                if (section.getSectionMesh().hasRenderableLayers()) {
                    drawable++;
                    var pos = section.getRenderOrigin();
                    if (Math.abs(pos.getX() - originX) < 40 && Math.abs(pos.getY() - originY) < 42 && Math.abs(pos.getZ() - originZ) < 40) {
                        local++;
                        if (!recording) {
                            anchors.put(section.getSectionNode(), section);
                        }
                    }
                }
            }
            if (!recording) {
                if (local >= 4) {
                    headings |= 1 << heading;
                }
                return;
            }
            Phase phase = ticks <= 120 ? stationary : crossing;
            phase.frames++;
            phase.minDrawable = Math.min(phase.minDrawable, drawable);
            if (drawable == 0) {
                phase.blank++;
            }
            if (anchors.entrySet().stream().anyMatch(entry -> entry.getValue().getSectionNode() != entry.getKey()
                    || !entry.getValue().getSectionMesh().hasRenderableLayers())) {
                phase.lostGeometry++;
            }
        });
    }

    void tick(Minecraft client) {
        var player = client.player;
        if (!initialized) {
            originX = player.getX();
            originY = player.getEyeY();
            originZ = player.getZ();
            boundary = Math.ceil(originY / 16) * 16;
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
            initialized = true;
        }
        var cache = (ClientCubeCache) client.level.getChunkSource();
        player.setYRot(((recording ? ticks : warmup) * 8) % 360);
        player.setXRot(29);
        heading = ((int) player.getYRot()) / 45;
        player.setDeltaMovement(0, 0, 0);
        if (!recording) {
            warmup++;
            if (warmup < 200 || headings != 255 || !allResident(cache, CubePos.from(player.blockPosition()))) {
                return;
            }
            anchors.entrySet().removeIf(entry -> entry.getValue().getSectionNode() != entry.getKey()
                    || !entry.getValue().getSectionMesh().hasRenderableLayers());
            if (anchors.isEmpty()) {
                return;
            }
            recording = true;
            previousSectionY = Mth.floor(player.getEyeY()) >> 4;
            System.out.println("CC_CAMERA_READY savedPosition=" + player.position() + " loaded=" + resident.size() + " headings=8 anchors=" + anchors.size());
        }
        for (var entry : resident.entrySet()) {
            var pos = entry.getKey();
            if (cache.cc_getCube(pos.getX(), pos.getY(), pos.getZ(), false) != entry.getValue()) {
                throw new IllegalStateException("A measured cube left the cache at " + pos);
            }
        }
        ticks++;
        if (ticks > 120) {
            double target = boundary + (ticks / 16 % 2 == 0 ? .6 : -.6);
            player.setDeltaMovement(0, Mth.clamp(target - player.getEyeY(), -.22, .22), 0);
            int sectionY = Mth.floor(player.getEyeY()) >> 4;
            if (sectionY != previousSectionY) {
                previousSectionY = sectionY;
                boundaryChanges++;
            }
        }
        if (ticks == 400) {
            if (boundaryChanges < 4 || stationary.frames < 10 || crossing.frames < 10) {
                throw new IllegalStateException("Camera scenario incomplete");
            }
            System.out.println("CC_CAMERA_RESULT loaded=" + resident.size() + " anchors=" + anchors.size() + " crossings=" + boundaryChanges
                    + " stationary=" + stationary + " moving=" + crossing);
            client.stop();
        }
    }

    private boolean allResident(ClientCubeCache cache, CubePos center) {
        resident.clear();
        for (int x = center.getX() - 1; x <= center.getX() + 1; x++) {
            for (int y = center.getY() - 1; y <= center.getY() + 1; y++) {
                for (int z = center.getZ() - 1; z <= center.getZ() + 1; z++) {
                    var cube = cache.cc_getCube(x, y, z, false);
                    if (cube == null) {
                        return false;
                    }
                    resident.put(CubePos.of(x, y, z), cube);
                }
            }
        }
        return true;
    }

    private static final class Phase {
        private int frames, blank, lostGeometry;
        private int minDrawable = Integer.MAX_VALUE;

        @Override public String toString() {
            return "frames/" + frames + ",blank/" + blank + ",lostGeometry/" + lostGeometry + ",minDrawable/" + minDrawable;
        }
    }
}
