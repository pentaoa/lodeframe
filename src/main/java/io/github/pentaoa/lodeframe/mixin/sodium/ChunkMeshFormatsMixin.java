package io.github.pentaoa.lodeframe.mixin.sodium;

import io.github.pentaoa.lodeframe.client.shader.LodeframeShaderPacks;
import io.github.pentaoa.lodeframe.render.sodium.ShaderPackTerrainVertexType;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkMeshFormats;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkMeshFormats.class)
public abstract class ChunkMeshFormatsMixin {
    @Inject(method = "getCurrent", at = @At("HEAD"), cancellable = true, remap = false)
    private static void lodeframe$selectShaderPackTerrainFormat(
            final CallbackInfoReturnable<ChunkVertexType> cir
    ) {
        if (LodeframeShaderPacks.getInstance().activeSource().isPresent()) {
            cir.setReturnValue(ShaderPackTerrainVertexType.INSTANCE);
        }
    }
}
