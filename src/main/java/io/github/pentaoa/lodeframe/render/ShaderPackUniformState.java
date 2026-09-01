package io.github.pentaoa.lodeframe.render;

import io.github.pentaoa.lodeframe.shaders.translate.LegacyFullscreenTransformer;
import com.mojang.blaze3d.buffers.GpuBuffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

final class ShaderPackUniformState implements AutoCloseable {
    private final ShaderPackUniformLayout layout;
    private final ByteBuffer data;
    private final GpuBuffer buffer;

    ShaderPackUniformState(
            final MetalDevice device,
            final List<LegacyFullscreenTransformer.UniformField> fields,
            final String label
    ) {
        this.layout = ShaderPackUniformLayout.of(fields);
        this.data = ByteBuffer.allocateDirect(this.layout.size()).order(ByteOrder.nativeOrder());
        this.buffer = device.createBuffer(
                () -> label,
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                this.layout.size()
        );
    }

    void update(
            final MetalCommandEncoder commandEncoder,
            final ShaderPackUniformLayout.FrameValues values
    ) {
        this.layout.write(this.data, values);
        commandEncoder.writeToBuffer(this.buffer.slice(), this.data);
    }

    void bind(final MetalRenderPass pass, final String blockName) {
        pass.setUniform(blockName, this.buffer);
    }

    @Override
    public void close() {
        this.buffer.close();
    }
}
