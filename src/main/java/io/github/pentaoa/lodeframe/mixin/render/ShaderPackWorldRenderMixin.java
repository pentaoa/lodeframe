package io.github.pentaoa.lodeframe.mixin.render;

import io.github.pentaoa.lodeframe.render.ShaderPackRenderHooks;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
abstract class ShaderPackWorldRenderMixin {
    @Inject(method = "renderItemInHand", at = @At("HEAD"))
    private void lodeframe$beginShaderPackHand(
            final CameraRenderState cameraRenderState,
            final float partialTick,
            final Matrix4fc projection,
            final CallbackInfo ci
    ) {
        ShaderPackRenderHooks.beginHand();
    }

    @Inject(method = "renderItemInHand", at = @At("RETURN"))
    private void lodeframe$endShaderPackHand(
            final CameraRenderState cameraRenderState,
            final float partialTick,
            final Matrix4fc projection,
            final CallbackInfo ci
    ) {
        ShaderPackRenderHooks.endHand();
    }

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void lodeframe$beginShaderPackWorldFrame(
            final DeltaTracker deltaTracker,
            final CallbackInfo ci
    ) {
        ShaderPackRenderHooks.beginWorldFrame((GameRenderer) (Object) this, deltaTracker);
    }

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
            ShaderPackRenderHooks.processWorldFrame((GameRenderer) (Object) this, deltaTracker);
        }
    }
}
