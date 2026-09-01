package io.github.pentaoa.lodeframe.render;

import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.vulkan.glsl.GlslCompiler;
import com.mojang.blaze3d.vulkan.glsl.IntermediaryShaderModule;
import io.github.pentaoa.lodeframe.shaders.pack.ShaderPack;
import io.github.pentaoa.lodeframe.shaders.pack.ShaderPackReport;
import io.github.pentaoa.lodeframe.shaders.pack.ShaderPackScanner;
import net.minecraft.client.renderer.ShaderDefines;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

final class ShaderPackRealPackCompileTest {
    @Test
    void compilesEverySelectedProgramFromTheLocalConformancePack() throws Exception {
        String configured = System.getenv("LODEFRAME_SHADER_PACK");
        Assumptions.assumeTrue(configured != null && !configured.isBlank());
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

        try (GlslCompiler compiler = new GlslCompiler()) {
            for (ShaderPackProgramLoader.PreparedProgram program : programs) {
                compile(compiler, program, ShaderType.VERTEX);
                compile(compiler, program, ShaderType.FRAGMENT);
            }
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
            final GlslCompiler compiler,
            final ShaderPackProgramLoader.PreparedProgram program,
            final ShaderType stage
    ) throws Exception {
        String source = program.shaderSource().get(program.id(), stage);
        try {
            try (IntermediaryShaderModule ignored = compiler.createIntermediary(
                    program.id().toDebugFileName(),
                    MetalDevice.prepareShaderSource(source, ShaderDefines.EMPTY),
                    stage
            )) {
                // Creating the SPIR-V intermediary is the syntax and frontend compatibility check.
            }
        } catch (Exception exception) {
            throw new AssertionError(program.program().key() + " " + stage + " failed", exception);
        }
    }
}
