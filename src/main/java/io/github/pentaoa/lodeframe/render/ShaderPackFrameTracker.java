package io.github.pentaoa.lodeframe.render;

import java.util.function.LongSupplier;

final class ShaderPackFrameTracker {
    private static final int FRAME_COUNTER_PERIOD = 720720;
    private static final float TIME_COUNTER_PERIOD = 3600.0F;
    private final LongSupplier nanoTime;
    private int frameCounter;
    private long lastStartNanos = Long.MIN_VALUE;
    private float frameTime;
    private float frameTimeCounter;
    private float[] previousProjection;
    private float[] previousModelView;
    private float previousCameraX;
    private float previousCameraY;
    private float previousCameraZ;

    ShaderPackFrameTracker() {
        this(System::nanoTime);
    }

    ShaderPackFrameTracker(final LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
    }

    ShaderPackFrameValues begin(
            final int width,
            final int height,
            final ShaderPackFrameContext context,
            final float shadowDistance,
            final float sunPathRotation,
            final int shadowMapResolution
    ) {
        long now = this.nanoTime.getAsLong();
        this.frameTime = this.lastStartNanos == Long.MIN_VALUE
                ? 0.0F
                : (now - this.lastStartNanos) / 1_000_000_000.0F;
        this.frameTimeCounter += this.frameTime;
        if (this.frameTimeCounter >= TIME_COUNTER_PERIOD) {
            this.frameTimeCounter = 0.0F;
        }
        this.lastStartNanos = now;
        this.frameCounter = (this.frameCounter + 1) % FRAME_COUNTER_PERIOD;

        float[] priorProjection = this.previousProjection == null ? context.projection() : this.previousProjection;
        float[] priorModelView = this.previousModelView == null ? context.modelView() : this.previousModelView;
        return new ShaderPackFrameValues(
                width,
                height,
                this.frameCounter,
                this.frameTime,
                this.frameTimeCounter,
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
        this.previousProjection = context.projection().clone();
        this.previousModelView = context.modelView().clone();
        this.previousCameraX = context.cameraX();
        this.previousCameraY = context.cameraY();
        this.previousCameraZ = context.cameraZ();
    }

    void reset() {
        this.frameCounter = 0;
        this.lastStartNanos = Long.MIN_VALUE;
        this.frameTime = 0.0F;
        this.frameTimeCounter = 0.0F;
        this.previousProjection = null;
        this.previousModelView = null;
        this.previousCameraX = 0.0F;
        this.previousCameraY = 0.0F;
        this.previousCameraZ = 0.0F;
    }
}
