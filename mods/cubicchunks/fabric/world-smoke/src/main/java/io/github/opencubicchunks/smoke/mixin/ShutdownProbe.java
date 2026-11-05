package io.github.opencubicchunks.smoke.mixin;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkMap.class)
public abstract class ShutdownProbe {
    @Shadow @Final ServerLevel level;
    @Unique private int smokeChecks;
    @Inject(method = "hasWork", at = @At("RETURN"))
    private void smokeWork(CallbackInfoReturnable<Boolean> cir) throws ReflectiveOperationException {
        if (level.getServer().isRunning() || ++smokeChecks % 10000 != 0 || !cir.getReturnValue()) return;
        StringBuilder result = new StringBuilder("CC_SHUTDOWN " + level.dimension());
        for (String name : new String[]{"pendingUnloads", "updatingChunkMap", "toDrop", "unloadQueue", "lightEngine", "poiManager", "worldgenTaskDispatcher", "lightTaskDispatcher", "distanceManager"}) {
            var field = ChunkMap.class.getDeclaredField(name);
            field.setAccessible(true);
            Object value = field.get(this);
            Object state = value;
            if (value instanceof java.util.Map<?, ?> map) {
                state = map.size();
                if (name.equals("updatingChunkMap")) {
                    java.util.Map<String, Integer> counts = new java.util.TreeMap<>();
                    map.values().forEach(v -> {
                        var holder = (net.minecraft.server.level.ChunkHolder) v;
                        String key = (((io.github.opencubicchunks.cubicchunks.server.level.CloHolder) holder).cc_getCloPos().isCube() ? "cube" : "column") + holder.getTicketLevel();
                        counts.merge(key, 1, Integer::sum);
                    });
                    state = counts;
                }
            }
            else if (value instanceof java.util.Collection<?> collection) state = collection.size();
            else {
                String method = name.equals("lightEngine") ? "hasLightWork" : name.equals("distanceManager") ? "hasTickets" : "hasWork";
                var query = value.getClass().getMethod(method);
                query.setAccessible(true);
                state = query.invoke(value);
            }
            result.append(" ").append(name).append("=").append(state);
        }
        System.out.println(result);
    }
}
