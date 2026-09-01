package io.github.pentaoa.lodeframe.render;

import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ShaderPackFrameContextTest {
    @Test
    void remapsMetalReversedDepthProjectionToLegacyOpenGlClipDepth() {
        float near = 0.05F;
        float far = 1024.0F;
        CameraRenderState camera = new CameraRenderState();
        camera.projectionMatrix = new Matrix4f().setPerspective(
                (float) Math.toRadians(70.0),
                16.0F / 9.0F,
                far,
                near,
                true
        );
        camera.viewRotationMatrix = new Matrix4f();
        camera.pos = Vec3.ZERO;
        camera.depthFar = far;

        ShaderPackFrameContext context = ShaderPackFrameContext.from(camera);
        Matrix4f projection = new Matrix4f().set(context.projection());
        Vector4f nearClip = projection.transform(new Vector4f(0.0F, 0.0F, -near, 1.0F));
        Vector4f farClip = projection.transform(new Vector4f(0.0F, 0.0F, -far, 1.0F));

        assertEquals(-1.0F, nearClip.z / nearClip.w, 0.0001F);
        assertEquals(1.0F, farClip.z / farClip.w, 0.0001F);
    }
}
