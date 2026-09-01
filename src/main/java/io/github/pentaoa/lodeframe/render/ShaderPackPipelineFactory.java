package io.github.pentaoa.lodeframe.render;

import io.github.pentaoa.lodeframe.shaders.pack.ShaderDirectives;
import io.github.pentaoa.lodeframe.shaders.pack.ShaderProgramType;
import io.github.pentaoa.lodeframe.shaders.pack.ShaderStage;
import io.github.pentaoa.lodeframe.shaders.translate.LegacyFullscreenTransformer;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Environment(EnvType.CLIENT)
final class ShaderPackPipelineFactory {
    private static final GpuFormat DEFAULT_COLORTEX_FORMAT = GpuFormat.RGBA8_UNORM;

    private ShaderPackPipelineFactory() {
    }

    static RenderPipeline create(
            final ShaderPackProgramLoader.PreparedProgram program,
            final Map<Integer, GpuFormat> bufferFormats,
            final GpuFormat finalFormat
    ) {
        BindGroupLayout.Builder resources = BindGroupLayout.builder();
        if (!program.vertex().uniforms().isEmpty()) {
            resources.withUniform(
                    LegacyFullscreenTransformer.uniformBlockName(ShaderStage.VERTEX),
                    UniformType.UNIFORM_BUFFER
            );
        }
        if (!program.fragment().uniforms().isEmpty()) {
            resources.withUniform(
                    LegacyFullscreenTransformer.uniformBlockName(ShaderStage.FRAGMENT),
                    UniformType.UNIFORM_BUFFER
            );
        }

        Set<String> samplerNames = new LinkedHashSet<>();
        program.vertex().samplers().forEach(sampler -> samplerNames.add(sampler.name()));
        program.fragment().samplers().forEach(sampler -> samplerNames.add(sampler.name()));
        samplerNames.forEach(resources::withSampler);

        RenderPipeline.Builder pipeline = RenderPipeline.builder()
                .withLocation(program.id())
                .withVertexShader(program.id())
                .withFragmentShader(program.id())
                .withBindGroupLayout(resources.build())
                .withCull(false)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withDepthStencilState(Optional.empty());

        if (program.program().type() == ShaderProgramType.FINAL) {
            pipeline.withColorTargetState(targetState(finalFormat));
            return pipeline.build();
        }

        List<Integer> targets = program.renderTargets()
                .map(ShaderDirectives.RenderTargets::buffers)
                .orElse(List.of(0));
        Set<Integer> outputs = program.fragment().fragmentOutputLocations();
        int highestOutput = outputs.stream().mapToInt(Integer::intValue).max().orElse(0);
        if (highestOutput >= targets.size()) {
            throw new IllegalArgumentException(
                    "Shader " + program.program().key() + " writes fragment output " + highestOutput
                            + " but only declares " + targets.size() + " render targets"
            );
        }
        for (int location = 0; location <= highestOutput; location++) {
            if (!outputs.contains(location)) {
                pipeline.withUnusedColorTargetState(location);
                continue;
            }
            int buffer = targets.get(location);
            pipeline.withColorTargetState(
                    location,
                    targetState(bufferFormats.getOrDefault(buffer, DEFAULT_COLORTEX_FORMAT))
            );
        }
        return pipeline.build();
    }

    private static ColorTargetState targetState(final GpuFormat format) {
        return new ColorTargetState(Optional.empty(), format, ColorTargetState.WRITE_ALL);
    }
}
