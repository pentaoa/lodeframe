package io.github.pentaoa.lodeframe.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;

record ShaderPackShadowMatrices(
        float[] projection,
        float[] projectionInverse,
        float[] modelView,
        float[] modelViewInverse,
        float[] renderProjection,
        float[] sunPosition
) {
    static ShaderPackShadowMatrices create(
            final ShaderPackFrameContext context,
            final float distance,
            final float sunPathRotationDegrees,
            final int resolution
    ) {
        float angle = context.timeAngle() - 0.25F;
        angle -= (float) Math.floor(angle);
        angle = (angle + ((float) Math.cos(angle * Math.PI) * -0.5F + 0.5F - angle) / 3.0F)
                * ((float) Math.PI * 2.0F);
        float path = (float) Math.toRadians(sunPathRotationDegrees);
        Vector3f towardSun = new Vector3f(
                -(float) Math.sin(angle),
                (float) Math.cos(angle) * (float) Math.cos(path),
                (float) Math.cos(angle) * -(float) Math.sin(path)
        ).normalize();
        Vector3f lightDirection = towardSun.negate(new Vector3f());
        Vector3f up = Math.abs(lightDirection.y) > 0.99F
                ? new Vector3f(0.0F, 0.0F, 1.0F)
                : new Vector3f(0.0F, 1.0F, 0.0F);
        Matrix4f rotation = new Matrix4f().lookAt(
                0.0F,
                0.0F,
                0.0F,
                lightDirection.x,
                lightDirection.y,
                lightDirection.z,
                up.x,
                up.y,
                up.z
        );

        Vector3f cameraInLightSpace = rotation.transformPosition(
                new Vector3f(context.cameraX(), context.cameraY(), context.cameraZ())
        );
        float texel = distance * 2.0F / Math.max(1, resolution);
        float snapX = Math.round(cameraInLightSpace.x / texel) * texel - cameraInLightSpace.x;
        float snapY = Math.round(cameraInLightSpace.y / texel) * texel - cameraInLightSpace.y;
        Matrix4f modelView = new Matrix4f().translation(snapX, snapY, 0.0F).mul(rotation);
        Matrix4f metalProjection = new Matrix4f().setOrtho(
                -distance,
                distance,
                -distance,
                distance,
                distance * 4.0F,
                -distance * 4.0F,
                true
        );
        Matrix4f clipDepthRemap = new Matrix4f().identity().m22(-2.0F).m32(1.0F);
        Matrix4f projection = clipDepthRemap.mul(metalProjection, new Matrix4f());
        Vector3f sunPosition = new Matrix4f().set(context.modelView())
                .transformDirection(towardSun, new Vector3f())
                .mul(100.0F);
        return new ShaderPackShadowMatrices(
                projection.get(new float[16]),
                projection.invert(new Matrix4f()).get(new float[16]),
                modelView.get(new float[16]),
                modelView.invert(new Matrix4f()).get(new float[16]),
                metalProjection.get(new float[16]),
                new float[]{sunPosition.x, sunPosition.y, sunPosition.z}
        );
    }
}
