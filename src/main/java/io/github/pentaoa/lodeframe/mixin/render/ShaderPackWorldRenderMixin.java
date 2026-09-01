package io.github.pentaoa.lodeframe.mixin.render;

import io.github.pentaoa.lodeframe.render.ShaderPackRenderHooks;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
abstract class ShaderPackWorldRenderMixin {
    @Inject(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/CommandEncoder;clearDepthTexture(Lcom/mojang/blaze3d/textures/GpuTexture;D)V"
            )
    )
    private void lodeframe$captureWorldDepthBeforeHand(
            final DeltaTracker deltaTracker,
            final CallbackInfo ci
    ) {
        ShaderPackRenderHooks.captureWorldDepth(((GameRenderer) (Object) this).mainRenderTarget());
    }

    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/fog/FogRenderer;endFrame()V"
            )
    )
    private void lodeframe$processWorldBeforeGui(
            final DeltaTracker deltaTracker,
            final boolean renderLevel,
            final CallbackInfo ci
    ) {
        if (renderLevel && Minecraft.getInstance().level != null) {
            ShaderPackRenderHooks.processWorldFrame((GameRenderer) (Object) this);
        }
    }
}
