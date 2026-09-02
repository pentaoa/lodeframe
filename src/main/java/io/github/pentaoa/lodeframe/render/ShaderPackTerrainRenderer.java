package io.github.pentaoa.lodeframe.render;

import io.github.pentaoa.lodeframe.shaders.pack.ShaderStage;
import io.github.pentaoa.lodeframe.shaders.translate.LegacyFullscreenTransformer;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

final class ShaderPackTerrainRenderer implements AutoCloseable {
    private final MetalDevice device;
    private final MetalCommandEncoder commandEncoder;
    private final ShaderPackProgramLoader.PreparedProgram program;
    private final ShaderPackTextureResources packTextures;
    private final Map<Integer, GpuFormat> bufferFormats;
    private final Map<RenderPipeline, RenderPipeline> overrides = new IdentityHashMap<>();
    private final Set<RenderPipeline> terrainPipelines = Collections.newSetFromMap(new IdentityHashMap<>());
    private final @Nullable ShaderPackUniformState vertexUniforms;
    private final @Nullable ShaderPackUniformState fragmentUniforms;

    ShaderPackTerrainRenderer(
            final MetalDevice device,
            final MetalCommandEncoder commandEncoder,
            final ShaderPackProgramLoader.PreparedProgram program,
            final ShaderPackTextureResources packTextures,
            final Map<Integer, GpuFormat> bufferFormats
    ) {
        this.device = device;
        this.commandEncoder = commandEncoder;
        this.program = program;
        this.packTextures = packTextures;
        this.bufferFormats = bufferFormats;
        this.vertexUniforms = program.vertex().uniforms().isEmpty()
                ? null
                : new ShaderPackUniformState(device, program.vertex().uniforms(), "shader pack terrain vertex uniforms");
        this.fragmentUniforms = program.fragment().uniforms().isEmpty()
                ? null
                : new ShaderPackUniformState(device, program.fragment().uniforms(), "shader pack terrain fragment uniforms");
    }

    RenderPipeline override(final RenderPipeline base) {
        return this.overrides.computeIfAbsent(base, pipeline -> {
            RenderPipeline result = ShaderPackPipelineFactory.createGeometryOverride(
                    this.program,
                    pipeline,
                    this.bufferFormats
            );
            CompiledRenderPipeline compiled = this.device.precompilePipeline(result, this.program.shaderSource());
            if (!compiled.isValid()) {
                this.device.releasePipeline(result);
                throw new IllegalStateException("Metal rejected shader-pack terrain program " + this.program.program().key());
            }
            this.terrainPipelines.add(result);
            return result;
        });
    }

    boolean owns(final RenderPipeline pipeline) {
        return this.terrainPipelines.contains(pipeline);
    }

    ShaderPackProgramLoader.PreparedProgram program() {
        return this.program;
    }

    void update(final ShaderPackUniformLayout.FrameValues values) {
        if (this.vertexUniforms != null) {
            this.vertexUniforms.update(this.commandEncoder, values);
        }
        if (this.fragmentUniforms != null) {
            this.fragmentUniforms.update(this.commandEncoder, values);
        }
    }

    void bindResources(final MetalRenderPass pass) {
        if (this.vertexUniforms != null) {
            this.vertexUniforms.bind(
                    pass,
                    LegacyFullscreenTransformer.uniformBlockName(ShaderStage.VERTEX)
            );
        }
        if (this.fragmentUniforms != null) {
            this.fragmentUniforms.bind(
                    pass,
                    LegacyFullscreenTransformer.uniformBlockName(ShaderStage.FRAGMENT)
            );
        }
        this.program.vertex().samplers().forEach(sampler -> this.packTextures.bind(pass, this.program, sampler));
        this.program.fragment().samplers().forEach(sampler -> this.packTextures.bind(pass, this.program, sampler));
    }

    @Override
    public void close() {
        for (RenderPipeline pipeline : this.terrainPipelines) {
            this.device.releasePipeline(pipeline);
        }
        if (this.vertexUniforms != null) {
            this.vertexUniforms.close();
        }
        if (this.fragmentUniforms != null) {
            this.fragmentUniforms.close();
        }
    }
}
