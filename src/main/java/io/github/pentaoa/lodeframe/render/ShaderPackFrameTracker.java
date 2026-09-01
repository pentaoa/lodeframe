package io.github.pentaoa.lodeframe.render;

final class ShaderPackFrameTracker {
    private final long startNanos = System.nanoTime();
    private int frameCounter;
    private float[] previousProjection;
    private float[] previousModelView;
    private float previousCameraX;
    private float previousCameraY;
    private float previousCameraZ;

    ShaderPackFrameValues begin(
            final int width,
            final int height,
            final ShaderPackFrameContext context,
            final float shadowDistance,
            final float sunPathRotation,
            final int shadowMapResolution
    ) {
        float[] priorProjection = this.previousProjection == null ? context.projection() : this.previousProjection;
        float[] priorModelView = this.previousModelView == null ? context.modelView() : this.previousModelView;
        return new ShaderPackFrameValues(
                width,
                height,
                this.frameCounter,
                (System.nanoTime() - this.startNanos) / 1_000_000_000.0F,
                context,
                priorProjection,
                priorModelView,
                this.previousProjection == null ? context.cameraX() : this.previousCameraX,
                this.previousProjection == null ? context.cameraY() : this.previousCameraY,
                this.previousProjection == null ? context.cameraZ() : this.previousCameraZ,
                ShaderPackShadowMatrices.create(
                        context,
                        shadowDistance,
                        sunPathRotation,
                        shadowMapResolution
                )
        );
    }

    void commit(final ShaderPackFrameContext context) {
        this.frameCounter++;
        this.previousProjection = context.projection().clone();
        this.previousModelView = context.modelView().clone();
        this.previousCameraX = context.cameraX();
        this.previousCameraY = context.cameraY();
        this.previousCameraZ = context.cameraZ();
    }

    void reset() {
        this.previousProjection = null;
        this.previousModelView = null;
        this.previousCameraX = 0.0F;
        this.previousCameraY = 0.0F;
        this.previousCameraZ = 0.0F;
    }
}
