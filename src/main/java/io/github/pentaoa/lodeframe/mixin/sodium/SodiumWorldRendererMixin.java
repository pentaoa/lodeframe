package io.github.pentaoa.lodeframe.mixin.sodium;

import io.github.pentaoa.lodeframe.render.ShaderPackRenderHooks;
import com.mojang.blaze3d.textures.GpuSampler;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SodiumWorldRenderer.class)
abstract class SodiumWorldRendererMixin {
    @Inject(method = "drawChunkLayer", at = @At("HEAD"), remap = false)
    private void lodeframe$renderShaderPackShadows(
            final ChunkSectionLayerGroup group,
            final ChunkRenderMatrices matrices,
            final double cameraX,
            final double cameraY,
            final double cameraZ,
            final GpuSampler blockSampler,
            final CallbackInfo ci
    ) {
        if (group == ChunkSectionLayerGroup.TRANSLUCENT) {
            ShaderPackRenderHooks.capturePreTranslucentDepth(Minecraft.getInstance().gameRenderer.mainRenderTarget());
        }
        ShaderPackRenderHooks.renderShadows(
                (SodiumWorldRenderer) (Object) this,
                group,
                cameraX,
                cameraY,
                cameraZ,
                blockSampler
        );
    }
}
