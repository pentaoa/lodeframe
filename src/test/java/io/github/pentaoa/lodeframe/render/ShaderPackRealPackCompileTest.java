package io.github.pentaoa.lodeframe.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import io.github.pentaoa.lodeframe.shaders.pack.ShaderPack;
import io.github.pentaoa.lodeframe.shaders.pack.ShaderPackReport;
import io.github.pentaoa.lodeframe.shaders.pack.ShaderPackScanner;
import io.github.pentaoa.lodeframe.mtl.MTLDevice;
import io.github.pentaoa.lodeframe.objc.ObjC;
import io.github.pentaoa.lodeframe.render.sodium.ShaderPackTerrainVertexType;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

final class ShaderPackRealPackCompileTest {
    @Test
    void compilesEverySelectedProgramThroughNativeMetal() throws Exception {
        String configured = System.getenv("LODEFRAME_SHADER_PACK");
        Assumptions.assumeTrue(configured != null && !configured.isBlank());
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("mac"));
        Path source = Path.of(configured);

        ShaderPackReport report;
        try (ShaderPack pack = ShaderPack.open(source)) {
            report = new ShaderPackScanner().scan(pack);
        }
        ShaderPackProgramSet set = ShaderPackProgramSet.load(source, report, "world0", 99L);
        Set<ShaderPackProgramLoader.PreparedProgram> programs = Collections.newSetFromMap(new IdentityHashMap<>());
        programs.addAll(set.fullscreenPrograms());
        add(programs, set.terrainProgram());
        add(programs, set.waterProgram());
        add(programs, set.skyBasicProgram());
        add(programs, set.entitiesProgram());
        add(programs, set.entitiesGlowingProgram());
        add(programs, set.handProgram());
        add(programs, set.handWaterProgram());
        add(programs, set.texturedProgram());
        add(programs, set.weatherProgram());
        add(programs, set.shadowProgram());

        MTLDevice metal = MTLDevice.createSystemDefault();
        if (metal == null) {
            throw new AssertionError("MTLCreateSystemDefaultDevice returned null");
        }
        try {
            for (ShaderPackProgramLoader.PreparedProgram program : programs) {
                compile(metal, set, program);
            }
        } finally {
            ObjC.release(metal.handle());
        }
    }

    private static void add(
            final Set<ShaderPackProgramLoader.PreparedProgram> programs,
            final ShaderPackProgramLoader.PreparedProgram program
    ) {
        if (program != null) {
            programs.add(program);
        }
    }

    private static void compile(
            final MTLDevice metal,
            final ShaderPackProgramSet set,
            final ShaderPackProgramLoader.PreparedProgram program
    ) throws Exception {
        try {
            RenderPipeline pipeline = pipelineFor(set, program);
            MetalCrossShaderCompiler.TranslatedPipeline translated =
                    MetalCrossShaderCompiler.translateToMsl(pipeline, program.shaderSource());

            MemorySegment vertexFunction = metal.newFunction(
                    translated.vertexSource(),
                    translated.vertexEntryPoint()
            );
            try {
                if (ObjC.isNil(vertexFunction)) {
                    throw new AssertionError("native Metal rejected vertex stage");
                }
                MemorySegment fragmentFunction = metal.newFunction(
                        translated.fragmentSource(),
                        translated.fragmentEntryPoint()
                );
                try {
                    if (ObjC.isNil(fragmentFunction)) {
                        throw new AssertionError("native Metal rejected fragment stage");
                    }
                } finally {
                    if (!ObjC.isNil(fragmentFunction)) {
                        ObjC.release(fragmentFunction);
                    }
                }
            } finally {
                if (!ObjC.isNil(vertexFunction)) {
                    ObjC.release(vertexFunction);
                }
            }
        } catch (Exception exception) {
            throw new AssertionError(program.program().key() + " failed", exception);
        }
    }

    private static RenderPipeline pipelineFor(
            final ShaderPackProgramSet set,
            final ShaderPackProgramLoader.PreparedProgram program
    ) {
        if (set.fullscreenPrograms().contains(program)) {
            return ShaderPackPipelineFactory.create(program, set.bufferFormats(), GpuFormat.RGBA8_UNORM);
        }

        RenderPipeline base;
        if (program == set.terrainProgram() || program == set.waterProgram() || program == set.shadowProgram()) {
            base = sodiumTerrainBase(program.id());
        } else if (program == set.skyBasicProgram()) {
            base = RenderPipelines.SKY;
        } else if (program == set.texturedProgram()) {
            base = RenderPipelines.OPAQUE_PARTICLE;
        } else if (program == set.weatherProgram()) {
            base = RenderPipelines.WEATHER_DEPTH_WRITE;
        } else {
            base = RenderPipelines.ENTITY_SOLID;
        }
        return ShaderPackPipelineFactory.createGeometryOverride(program, base, set.bufferFormats());
    }

    private static RenderPipeline sodiumTerrainBase(final Identifier shaderId) {
        return RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("lodeframe", "test/sodium_terrain_base"))
                .withVertexShader(shaderId)
                .withFragmentShader(shaderId)
                .withBindGroupLayout(ShaderChunkRenderer.BIND_GROUP)
                .withCull(true)
                .withDepthStencilState(DepthStencilState.DEFAULT)
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                .withVertexBinding(0, ShaderPackTerrainVertexType.INSTANCE.getVertexFormat())
                .withColorTargetState(ColorTargetState.DEFAULT)
                .build();
    }
}
