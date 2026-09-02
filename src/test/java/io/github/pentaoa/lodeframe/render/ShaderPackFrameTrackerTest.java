package io.github.pentaoa.lodeframe.render;

import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ShaderPackFrameTrackerTest {
    @Test
    void suppliesIrisFrameTimingAndTemporalSequenceUniforms() {
        AtomicLong now = new AtomicLong(10_000_000_000L);
        ShaderPackFrameTracker tracker = new ShaderPackFrameTracker(now::get);
        ShaderPackFrameContext context = context();

        ShaderPackFrameValues first = tracker.begin(1920, 1080, context, 128.0F, 0.0F, 1024);
        assertEquals(1, first.integer("frameCounter"));
        assertEquals(0.0F, first.floatComponent("frameTime", 0));
        assertEquals(1.0F, first.floatComponent("framemod2", 0));
        assertEquals(1.0F, first.floatComponent("framemod8", 0));

        tracker.commit(context);
        now.addAndGet(50_000_000L);
        ShaderPackFrameValues second = tracker.begin(1920, 1080, context, 128.0F, 0.0F, 1024);
        assertEquals(2, second.integer("frameCounter"));
        assertEquals(0.05F, second.floatComponent("frameTime", 0), 0.00001F);
        assertEquals(0.05F, second.floatComponent("frameTimeCounter", 0), 0.00001F);
        assertEquals(0.0F, second.floatComponent("framemod2", 0));
        assertEquals(2.0F, second.floatComponent("framemod8", 0));

        tracker.reset();
        ShaderPackFrameValues reset = tracker.begin(1920, 1080, context, 128.0F, 0.0F, 1024);
        assertEquals(1, reset.integer("frameCounter"));
        assertEquals(0.0F, reset.floatComponent("frameTime", 0));
        assertEquals(0.0F, reset.floatComponent("frameTimeCounter", 0));
    }

    @Test
    void exposesWorldAndCameraValuesUsedByBsl() {
        AtomicLong now = new AtomicLong();
        ShaderPackFrameValues values = new ShaderPackFrameTracker(now::get)
                .begin(1920, 1080, context(), 128.0F, 0.0F, 1024);

        assertEquals(-64, values.integer("bedrockLevel"));
        assertEquals(384, values.integer("heightLimit"));
        assertEquals(240, values.integerComponent("eyeBrightness", 0));
        assertEquals(240, values.integerComponent("eyeBrightness", 1));
        assertEquals(1.0F, values.floatComponent("centerDepthSmooth", 0));
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
