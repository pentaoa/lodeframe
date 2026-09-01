package io.github.pentaoa.lodeframe.mixin.sodium;

import io.github.pentaoa.lodeframe.render.sodium.ShaderPackBlockIds;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.FluidRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildBuffers;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.TranslucentGeometryCollector;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ChunkBuilderMeshingTask.class)
abstract class ChunkBuilderMeshingTaskMixin {
    @Redirect(
            method = "execute",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/pipeline/BlockRenderer;renderModel(Lnet/minecraft/client/renderer/block/dispatch/BlockStateModel;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;)V"
            ),
            remap = false
    )
    private void lodeframe$renderModelWithShaderBlockId(
            final BlockRenderer renderer,
            final BlockStateModel model,
            final BlockState state,
            final BlockPos position,
            final BlockPos origin
    ) {
        ShaderPackBlockIds.begin(state);
        try {
            renderer.renderModel(model, state, position, origin);
        } finally {
            ShaderPackBlockIds.end();
        }
    }

    @Redirect(
            method = "execute",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/pipeline/FluidRenderer;render(Lnet/caffeinemc/mods/sodium/client/world/LevelSlice;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;Lnet/caffeinemc/mods/sodium/client/render/chunk/translucent_sorting/TranslucentGeometryCollector;Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildBuffers;)V"
            ),
            remap = false
    )
    private void lodeframe$renderFluidWithShaderBlockId(
            final FluidRenderer renderer,
            final LevelSlice level,
            final BlockState state,
            final FluidState fluid,
            final BlockPos position,
            final BlockPos origin,
            final TranslucentGeometryCollector collector,
            final ChunkBuildBuffers buffers
    ) {
        ShaderPackBlockIds.begin(state);
        try {
            renderer.render(level, state, fluid, position, origin, collector, buffers);
        } finally {
            ShaderPackBlockIds.end();
        }
    }
}
