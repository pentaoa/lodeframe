package io.github.pentaoa.lodeframe.mixin.sodium;

import io.github.pentaoa.lodeframe.render.ShaderPackRenderHooks;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

@Mixin(ShaderChunkRenderer.class)
abstract class ShaderChunkRendererMixin {
    @Shadow
    @Final
    private static Map<TerrainRenderPass, RenderPipeline> programs;

    @Inject(method = "createShader", at = @At("RETURN"), cancellable = true, remap = false)
    private void lodeframe$useShaderPackTerrainProgram(
            final String path,
            final TerrainRenderPass pass,
            final CallbackInfoReturnable<RenderPipeline> cir
    ) {
        cir.setReturnValue(ShaderPackRenderHooks.overrideSodiumTerrainPipeline(cir.getReturnValue(), pass));
    }

    @Inject(method = "delete", at = @At("HEAD"), remap = false)
    private void lodeframe$releaseCachedTerrainPrograms(final CallbackInfo ci) {
        ShaderPackRenderHooks.clearSodiumTerrainPipelines();
        programs.clear();
    }
}
