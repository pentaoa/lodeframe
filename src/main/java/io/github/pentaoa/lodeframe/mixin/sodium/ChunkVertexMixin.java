package io.github.pentaoa.lodeframe.mixin.sodium;

import io.github.pentaoa.lodeframe.render.sodium.ShaderPackBlockIds;
import io.github.pentaoa.lodeframe.render.sodium.ShaderPackChunkVertex;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkVertexEncoder.Vertex.class)
abstract class ChunkVertexMixin implements ShaderPackChunkVertex {
    @Unique
    private int lodeframe$blockId;

    @Override
    public int lodeframe$getBlockId() {
        return this.lodeframe$blockId;
    }

    @Override
    public void lodeframe$setBlockId(final int blockId) {
        this.lodeframe$blockId = blockId;
    }

    @Inject(method = "writeVertex", at = @At("RETURN"), remap = false)
    private static void lodeframe$attachBlockId(
            final ChunkVertexEncoder.Vertex vertex,
            final float x,
            final float y,
            final float z,
            final int color,
            final float ao,
            final float u,
            final float v,
            final int light,
            final CallbackInfo ci
    ) {
        ((ShaderPackChunkVertex) vertex).lodeframe$setBlockId(ShaderPackBlockIds.current());
    }

    @Inject(method = "copyVertexTo", at = @At("RETURN"), remap = false)
    private static void lodeframe$copyBlockId(
            final ChunkVertexEncoder.Vertex source,
            final ChunkVertexEncoder.Vertex destination,
            final CallbackInfo ci
    ) {
        ((ShaderPackChunkVertex) destination).lodeframe$setBlockId(
                ((ShaderPackChunkVertex) source).lodeframe$getBlockId()
        );
    }
}
