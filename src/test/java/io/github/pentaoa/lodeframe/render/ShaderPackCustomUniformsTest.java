package io.github.pentaoa.lodeframe.render;

import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ShaderPackCustomUniformsTest {
    @Test
    void evaluatesBslScalarExpressionsAndModernVersionBranch() {
        ShaderPackCustomUniforms custom = ShaderPackCustomUniforms.parse("""
                uniform.float.timeAngle=worldTime / 24000
                variable.float.fadeOut=clamp((worldTime - 12330) / 230, 0.0, 1.0)
                uniform.float.shadowFade=1.0 - fadeOut
                uniform.float.timeBrightness=max(sin(timeAngle * 6.28318530718), 0.0)
                uniform.float.isPlains=if(in(biome, BIOME_PLAINS, BIOME_GROVE), 1, 0)
                #if MC_VERSION >= 11800
                uniform.float.versionBranch=1
                #else
                uniform.float.versionBranch=0
                #endif
                uniform.float.continued=1 + \\
                    2 + \\
                    3
                """, 260200);

        ShaderPackFrameValues values = values(custom, new AtomicLong());
        assertEquals(0.0F, values.floatComponent("timeAngle", 0), 0.00001F);
        assertEquals(1.0F, values.floatComponent("shadowFade", 0), 0.00001F);
        assertEquals(0.0F, values.floatComponent("timeBrightness", 0), 0.00001F);
        assertEquals(1.0F, values.floatComponent("isPlains", 0), 0.00001F);
        assertEquals(1.0F, values.floatComponent("versionBranch", 0), 0.00001F);
        assertEquals(6.0F, values.floatComponent("continued", 0), 0.00001F);
    }

    @Test
    void smoothUsesIrisHalfLifeTimingAndUpdatesOnlyOncePerFrame() {
        ShaderPackCustomUniforms custom = ShaderPackCustomUniforms.parse(
                "uniform.float.pulse=smooth(7, frameCounter % 2, 10, 10)",
                260200
        );
        AtomicLong now = new AtomicLong();
        ShaderPackFrameTracker tracker = new ShaderPackFrameTracker(now::get);
        ShaderPackFrameContext context = context();

        ShaderPackFrameValues first = tracker.begin(1, 1, context, 128.0F, 0.0F, 1024, custom);
        assertEquals(1.0F, first.floatComponent("pulse", 0), 0.00001F);
        assertEquals(1.0F, first.floatComponent("pulse", 0), 0.00001F);

        now.addAndGet(1_000_000_000L);
        ShaderPackFrameValues second = tracker.begin(1, 1, context, 128.0F, 0.0F, 1024, custom);
        assertEquals(0.5F, second.floatComponent("pulse", 0), 0.00001F);
    }

    private static ShaderPackFrameValues values(
            final ShaderPackCustomUniforms custom,
            final AtomicLong now
    ) {
        return new ShaderPackFrameTracker(now::get)
                .begin(1920, 1080, context(), 128.0F, 0.0F, 1024, custom);
    }

    private static ShaderPackFrameContext context() {
        CameraRenderState camera = new CameraRenderState();
        camera.projectionMatrix = new Matrix4f().identity();
        camera.viewRotationMatrix = new Matrix4f().identity();
        camera.pos = Vec3.ZERO;
        camera.depthFar = 1024.0F;
        return ShaderPackFrameContext.from(camera);
    }
}
