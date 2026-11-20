package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.level.gameevent;

import io.github.opencubicchunks.cc_core.utils.Coords;
import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import io.github.opencubicchunks.cubicchunks.world.level.cube.LevelCube;
import io.github.opencubicchunks.cubicchunks.world.level.entity.CubeMobBlockView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.gameevent.DynamicGameEventListener;
import net.minecraft.world.level.gameevent.GameEventListener;
import net.minecraft.world.level.gameevent.GameEventListenerRegistry;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DynamicGameEventListener.class)
public class MixinDynamicGameEventListener {
    @Shadow @Final private GameEventListener listener;
    @Shadow private SectionPos lastSection;
    @Unique
    private LevelCube cc_cube;
    @Unique
    private GameEventListenerRegistry cc_registry;
    @Unique
    private int cc_section;

    @Inject(method = "move", at = @At("HEAD"), cancellable = true)
    private void cc_move(ServerLevel level, CallbackInfo ci) {
        if (!((CanBeCubic) level).cc_isCubic()) {
            cc_release();
            return;
        }
        lastSection = null;
        listener.getListenerSource().getPosition(level).ifPresent(position -> cc_moveTo(level, BlockPos.containing(position)));
        ci.cancel();
    }

    @Unique
    private void cc_moveTo(ServerLevel level, BlockPos position) {
        LevelCube next = CubeMobBlockView.loadedCube(level, position);
        int section = Coords.blockToIndex(position);
        if (next == cc_cube && section == cc_section) {
            return;
        }
        cc_release();
        if (next != null) {
            cc_cube = next;
            cc_section = section;
            cc_registry = next.cc_getListenerRegistry(position);
            cc_registry.register(listener);
        }
    }

    @Inject(method = "remove", at = @At("HEAD"), cancellable = true)
    private void cc_remove(ServerLevel level, CallbackInfo ci) {
        cc_release();
        if (((CanBeCubic) level).cc_isCubic()) {
            lastSection = null;
            ci.cancel();
        }
    }

    @Unique
    private void cc_release() {
        if (cc_registry != null) {
            cc_registry.unregister(listener);
            cc_registry = null;
            cc_cube = null;
        }
    }
}
