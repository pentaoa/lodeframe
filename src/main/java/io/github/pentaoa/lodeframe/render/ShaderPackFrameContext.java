package io.github.pentaoa.lodeframe.render;

import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;

record ShaderPackFrameContext(
        float[] projection,
        float[] projectionInverse,
        float[] modelView,
        float[] modelViewInverse,
        float cameraX,
        float cameraY,
        float cameraZ,
        float near,
        float far
) {
    static ShaderPackFrameContext from(final CameraRenderState camera) {
        Matrix4f clipDepthRemap = new Matrix4f().identity().m22(-2.0F).m32(1.0F);
        Matrix4f projection = clipDepthRemap.mul(camera.projectionMatrix, new Matrix4f());
        Matrix4f modelView = new Matrix4f(camera.viewRotationMatrix);
        return new ShaderPackFrameContext(
                projection.get(new float[16]),
                projection.invert(new Matrix4f()).get(new float[16]),
                modelView.get(new float[16]),
                modelView.invert(new Matrix4f()).get(new float[16]),
                (float) camera.pos.x,
                (float) camera.pos.y,
                (float) camera.pos.z,
                0.05F,
                camera.depthFar
        );
    }
}
