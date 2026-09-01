package io.github.pentaoa.lodeframe.render;

import io.github.pentaoa.lodeframe.shaders.pack.ShaderDirectives;
import io.github.pentaoa.lodeframe.shaders.pack.ShaderProgram;
import io.github.pentaoa.lodeframe.shaders.pack.ShaderProgramType;
import io.github.pentaoa.lodeframe.shaders.translate.LegacyFullscreenTransformer;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ShaderPackPipelineFactoryTest {
    @Test
    void mapsLogicalFragmentOutputsToPhysicalBufferFormats() {
        ShaderPackProgramLoader.PreparedProgram program = program(
                ShaderProgramType.COMPOSITE,
                List.of(0, 4, 5),
                Set.of(0, 2)
        );

        RenderPipeline pipeline = ShaderPackPipelineFactory.create(
                program,
                Map.of(4, GpuFormat.RGBA16_FLOAT, 5, GpuFormat.RG8_UNORM),
                GpuFormat.RGBA8_UNORM
        );

        assertEquals(GpuFormat.RGBA8_UNORM, pipeline.getColorTargetStates()[0].format());
        assertNull(pipeline.getColorTargetStates()[1]);
        assertEquals(GpuFormat.RG8_UNORM, pipeline.getColorTargetStates()[2].format());
        BindGroupLayout resources = pipeline.getBindGroupLayouts().getFirst();
        assertEquals(List.of("colortex0"), resources.getSamplers());
        assertEquals(2, resources.getUniforms().size());
    }

    @Test
    void usesPresentationFormatForFinalProgram() {
        RenderPipeline pipeline = ShaderPackPipelineFactory.create(
                program(ShaderProgramType.FINAL, List.of(), Set.of(0)),
                Map.of(),
                GpuFormat.RGBA16_FLOAT
        );

        assertEquals(GpuFormat.RGBA16_FLOAT, pipeline.getColorTargetState().format());
    }

    @Test
    void rejectsOutputsWithoutADeclaredPhysicalTarget() {
        ShaderPackProgramLoader.PreparedProgram program = program(
                ShaderProgramType.COMPOSITE,
                List.of(0),
                Set.of(1)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> ShaderPackPipelineFactory.create(program, Map.of(), GpuFormat.RGBA8_UNORM)
        );
    }

    private static ShaderPackProgramLoader.PreparedProgram program(
            final ShaderProgramType type,
            final List<Integer> targets,
            final Set<Integer> outputs
    ) {
        ShaderProgram shaderProgram = new ShaderProgram(
                "world0",
                type == ShaderProgramType.FINAL ? "final" : "composite",
                type,
                0,
                Map.of(),
                ShaderDirectives.empty()
        );
        LegacyFullscreenTransformer.TransformedShader vertex = new LegacyFullscreenTransformer.TransformedShader(
                "#version 330 core\nvoid main(){}",
                List.of(new LegacyFullscreenTransformer.UniformField("float", "frameTimeCounter")),
                List.of(),
                Set.of()
        );
        LegacyFullscreenTransformer.TransformedShader fragment = new LegacyFullscreenTransformer.TransformedShader(
                "#version 330 core\nvoid main(){}",
                List.of(new LegacyFullscreenTransformer.UniformField("float", "viewWidth")),
                List.of(new LegacyFullscreenTransformer.SamplerField("sampler2D", "colortex0")),
                outputs
        );
        Optional<ShaderDirectives.RenderTargets> renderTargets = targets.isEmpty()
                ? Optional.empty()
                : Optional.of(new ShaderDirectives.RenderTargets(
                        ShaderDirectives.RenderTargetsKind.RENDERTARGETS,
                        targets,
                        0
                ));
        Identifier id = Identifier.fromNamespaceAndPath("lodeframe", "test/" + shaderProgram.name());
        ShaderSource source = (requested, stage) -> null;
        return new ShaderPackProgramLoader.PreparedProgram(
                shaderProgram,
                id,
                source,
                vertex,
                fragment,
                renderTargets,
                "test.vsh",
                "test.fsh"
        );
    }
}
