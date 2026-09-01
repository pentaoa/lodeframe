package io.github.pentaoa.lodeframe.mixin.sodium;

import io.github.pentaoa.lodeframe.render.ShaderPackRenderHooks;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(TerrainRenderPass.class)
abstract class TerrainRenderPassMixin {
    @Inject(method = "getTarget", at = @At("HEAD"), cancellable = true, remap = false)
    private void lodeframe$useShaderPackShadowTarget(final CallbackInfoReturnable<RenderTarget> cir) {
        RenderTarget target = ShaderPackRenderHooks.activeShadowTarget();
        if (target != null) {
            cir.setReturnValue(target);
        }
    }
}
