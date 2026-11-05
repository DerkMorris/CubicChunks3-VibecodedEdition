package io.github.opencubicchunks.cubicchunks.mixin.core.common.world.level.pathfinder;

import java.util.HashMap;
import java.util.Map;

import io.github.opencubicchunks.cubicchunks.CanBeCubic;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Native node hashes discard coordinate bits. A search must not merge distinct full positions. */
@Mixin(NodeEvaluator.class)
public class MixinNodeEvaluator {
    @Shadow protected Mob mob;
    @Unique
    private final Map<BlockPos, Node> cc_nodes = new HashMap<>();

    @Inject(method = { "prepare", "done" }, at = @At("HEAD"))
    private void cc_clearNodes(CallbackInfo ci) {
        cc_nodes.clear();
    }

    @Inject(method = "getNode(III)Lnet/minecraft/world/level/pathfinder/Node;", at = @At("HEAD"), cancellable = true)
    private void cc_fullNode(int x, int y, int z, CallbackInfoReturnable<Node> cir) {
        if (mob != null && ((CanBeCubic) mob.level()).cc_isCubic()) {
            cir.setReturnValue(cc_nodes.computeIfAbsent(new BlockPos(x, y, z), pos -> new Node(x, y, z)));
        }
    }
}
