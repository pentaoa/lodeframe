package io.github.pentaoa.lodeframe.render;

import io.github.pentaoa.lodeframe.Lodeframe;
import io.github.pentaoa.lodeframe.mtl.*;
import io.github.pentaoa.lodeframe.objc.ObjC;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.PolygonMode;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Environment(EnvType.CLIENT)
final class MetalCompiledRenderPipeline implements CompiledRenderPipeline, AutoCloseable {
    enum ResourceKind {
        UNIFORM_BUFFER,
        SAMPLED_IMAGE,
        TEXEL_BUFFER
    }

    static final int STAGE_VERTEX = 1;
    static final int STAGE_FRAGMENT = 2;
    static final int STAGE_ALL = STAGE_VERTEX | STAGE_FRAGMENT;

    record ResourceBinding(ResourceKind kind, String name, int bindingIndex, int stageMask,
                           @Nullable GpuFormat texelBufferFormat) {
    }

    private final List<ResourceBinding> resources;
    private final Map<String, ResourceBinding> resourcesByName;
    private final long allResourceMask;
    private final int firstAvailableVertexBufferSlot;
    private final MTLCullMode cullMode;
    private final MTLTriangleFillMode fillMode;
    private final float depthBiasScaleFactor;
    private final float depthBiasConstant;
    private final MTLPrimitiveType topology;
    private final int vertexBufferCount;

    private final MetalDevice device;
    private final RenderPipeline info;
    private final MemorySegment depthStencilState;
    private final MemorySegment vertexFunction;
    private final MemorySegment fragmentFunction;
    private final String vertexMsl;
    private final String fragmentMsl;
    private final String vertexEntryPoint;
    private final String fragmentEntryPoint;
    private final MTLVertexDescriptor vertexDescriptor;
    private final ColorTargetState[] colorTargetStates;
    private final MTLPixelFormat[] colorAttachmentFormats;
    private final Map<DepthStencilFormats, MemorySegment> nativePipelines = new HashMap<>();
    private boolean closed;

    MetalCompiledRenderPipeline(
            final MetalDevice device,
            final RenderPipeline info,
            final String vertexMsl,
            final String fragmentMsl,
            final String vertexEntryPoint,
            final String fragmentEntryPoint,
            final List<ResourceBinding> resources
    ) {
        this.device = device;
        this.info = info;
        this.resources = resources;
        this.resourcesByName = resources.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(ResourceBinding::name, binding -> binding));

        int maxBindingIndex = -1;
        long resourceMask = 0L;
        for (ResourceBinding binding : resources) {
            maxBindingIndex = Math.max(maxBindingIndex, binding.bindingIndex());
            resourceMask |= 1L << binding.bindingIndex();
        }
        if (maxBindingIndex >= Long.SIZE) {
            throw new IllegalStateException("Pipeline " + info.getLocation() + " has binding index " + maxBindingIndex + ", limit is " + (Long.SIZE - 1));
        }
        this.allResourceMask = resourceMask;

        this.firstAvailableVertexBufferSlot = firstAvailableVertexBufferSlot(resources);
        this.cullMode = info.isCull() ? MTLCullMode.Back : MTLCullMode.None;
        this.fillMode = info.getPolygonMode() == PolygonMode.WIREFRAME ? MTLTriangleFillMode.Lines : MTLTriangleFillMode.Fill;
        this.topology = MTLPrimitiveType.from(info.getPrimitiveTopology());
        this.vertexBufferCount = info.getVertexFormatBindings().length;

        MTLCompareFunction depthCompareOp;
        int depthWrite;
        var depthStencilState = info.getDepthStencilState();
        if (depthStencilState == null) {
            depthCompareOp = MTLCompareFunction.Always;
            depthWrite = 0;
            this.depthBiasScaleFactor = 0.0f;
            this.depthBiasConstant = 0.0f;
        } else {
            depthCompareOp = MTLCompareFunction.from(depthStencilState.depthTest());
            depthWrite = depthStencilState.writeDepth() ? 1 : 0;
            this.depthBiasScaleFactor = depthStencilState.depthBiasScaleFactor();
            this.depthBiasConstant = depthStencilState.depthBiasConstant();
        }

        this.depthStencilState = device.depthStencilState(depthCompareOp, depthWrite != 0);

        this.colorTargetStates = info.getColorTargetStates();
        this.colorAttachmentFormats = colorTargetFormats(this.colorTargetStates);
        this.vertexMsl = vertexMsl;
        this.fragmentMsl = fragmentMsl;
        this.vertexEntryPoint = vertexEntryPoint;
        this.fragmentEntryPoint = fragmentEntryPoint;
        this.vertexFunction = device.getOrCompileFunction(vertexMsl, vertexEntryPoint);
        this.fragmentFunction = device.getOrCompileFunction(fragmentMsl, fragmentEntryPoint);
        this.vertexDescriptor = buildVertexDescriptor(info, this.firstAvailableVertexBufferSlot);

        getNativePipeline(MTLPixelFormat.Invalid, MTLPixelFormat.Invalid);
        getNativePipeline(MTLPixelFormat.Depth32Float, MTLPixelFormat.Invalid);
    }

    private MemorySegment createPipeline(final DepthStencilFormats depthStencilFormats) {
        if (ObjC.isNil(vertexFunction) || ObjC.isNil(fragmentFunction)) {
            return MemorySegment.NULL;
        }

        try (MTLRenderPipelineDescriptor pipelineDesc = new MTLRenderPipelineDescriptor()) {
            pipelineDesc.setCompiledFunctions(vertexFunction, fragmentFunction);
            pipelineDesc.setVertexDescriptor(vertexDescriptor);
            for (int index = 0; index < colorAttachmentFormats.length; index++) {
                MTLPixelFormat format = colorAttachmentFormats[index];
                pipelineDesc.setColorAttachmentFormat(index, format);
                ColorTargetState colorTarget = colorTargetStates.length > index ? colorTargetStates[index] : null;
                if (colorTarget == null) {
                    continue;
                }

                Optional<BlendFunction> blendFunction = colorTarget.blendFunction();
                long writeMask = MTLColorWriteMask.from(colorTarget.writeMask());
                if (blendFunction.isPresent()) {
                    var function = blendFunction.get();
                    pipelineDesc.setBlendState(
                            index,
                            MTLBlendFactor.from(function.color().sourceFactor()),
                            MTLBlendFactor.from(function.color().destFactor()),
                            MTLBlendOperation.from(function.color().op()),
                            MTLBlendFactor.from(function.alpha().sourceFactor()),
                            MTLBlendFactor.from(function.alpha().destFactor()),
                            MTLBlendOperation.from(function.alpha().op()),
                            writeMask
                    );
                } else {
                    pipelineDesc.disableBlending(index, writeMask);
                }
            }
            pipelineDesc.setDepthStencilFormats(depthStencilFormats.depth(), depthStencilFormats.stencil());

            MemorySegment pipeline = device.metalDevice().newRenderPipelineState(pipelineDesc);
            if (ObjC.isNil(pipeline)) {
                Lodeframe.LOGGER.error(
                        "[lodeframe] Pipeline {} failed to build with depth/stencil formats {}/{}",
                        info.getLocation(),
                        depthStencilFormats.depth(),
                        depthStencilFormats.stencil()
                );
            }
            return pipeline;
        }
    }

    @Override
    public boolean isValid() {
        return !ObjC.isNil(getNativePipeline(MTLPixelFormat.Depth32Float, MTLPixelFormat.Invalid));
    }

    List<ResourceBinding> resources() {
        return this.resources;
    }

    long allResourceMask() {
        return this.allResourceMask;
    }

    @Nullable
    ResourceBinding resource(final String name) {
        return this.resourcesByName.get(name);
    }

    int firstAvailableVertexBufferSlot() {
        return this.firstAvailableVertexBufferSlot;
    }

    float depthBiasScaleFactor() {
        return this.depthBiasScaleFactor;
    }

    float depthBiasConstant() {
        return this.depthBiasConstant;
    }

    MemorySegment getDepthStencilState() {
        return this.depthStencilState;
    }

    MemorySegment getNativePipeline(final MTLPixelFormat depthFormat, final MTLPixelFormat stencilFormat) {
        DepthStencilFormats formats = new DepthStencilFormats(depthFormat, stencilFormat);
        return nativePipelines.computeIfAbsent(formats, this::createPipeline);
    }

    void validateColorAttachmentFormats(final MTLPixelFormat[] actualFormats) {
        if (!Arrays.equals(colorAttachmentFormats, actualFormats)) {
            throw new IllegalStateException(
                    "Pipeline " + info.getLocation() + " expects color attachments "
                            + Arrays.toString(colorAttachmentFormats) + " but render pass uses " + Arrays.toString(actualFormats)
            );
        }
    }

    MTLCullMode cullMode() {
        return this.cullMode;
    }

    MTLTriangleFillMode fillMode() {
        return this.fillMode;
    }

    MTLPrimitiveType topology() {
        return this.topology;
    }

    int vertexBufferCount() {
        return this.vertexBufferCount;
    }

    private static MTLVertexDescriptor buildVertexDescriptor(
            final RenderPipeline pipeline,
            final int firstMetalVertexBufferSlot
    ) {
        VertexFormat[] bindings = pipeline.getVertexFormatBindings();
        MTLVertexDescriptor vertexDesc = new MTLVertexDescriptor();
        long attrIndex = 0;

        for (int i = 0; i < bindings.length; i++) {
            VertexFormat binding = bindings[i];
            if (binding == null || binding.getElements().isEmpty()) {
                continue;
            }

            int metalSlot = firstMetalVertexBufferSlot + i;

            long stride = binding.getVertexSize();
            long stepRate = binding.getStepRate();
            MTLVertexStepFunction stepFunction = stepRate > 0 ? MTLVertexStepFunction.PerInstance : MTLVertexStepFunction.PerVertex;
            vertexDesc.setLayout(metalSlot, stride, stepFunction, stepRate > 0 ? stepRate : 1);

            for (VertexFormatElement element : binding.getElements()) {
                MTLVertexFormat format = MTLVertexFormat.from(element.format());
                if (format == MTLVertexFormat.Invalid) {
                    throw new IllegalStateException("Unsupported vertex attribute format: " + element.format());
                }
                vertexDesc.setAttribute(attrIndex, format.value, element.offset(), metalSlot);
                attrIndex++;
            }
        }

        return vertexDesc;
    }

    private static int firstAvailableVertexBufferSlot(final List<ResourceBinding> resources) {
        int maxVertexBufferBinding = -1;
        for (ResourceBinding resource : resources) {
            if (resource.kind() == ResourceKind.UNIFORM_BUFFER && (resource.stageMask() & STAGE_VERTEX) != 0) {
                maxVertexBufferBinding = Math.max(maxVertexBufferBinding, resource.bindingIndex());
            }
        }
        return maxVertexBufferBinding + 1;
    }

    static MTLPixelFormat[] colorTargetFormats(final ColorTargetState[] colorTargetStates) {
        if (colorTargetStates.length > MTLRenderPipelineDescriptor.MAX_COLOR_ATTACHMENTS) {
            throw new IllegalArgumentException("Metal supports at most " + MTLRenderPipelineDescriptor.MAX_COLOR_ATTACHMENTS + " color targets");
        }

        MTLPixelFormat[] formats = new MTLPixelFormat[MTLRenderPipelineDescriptor.MAX_COLOR_ATTACHMENTS];
        Arrays.fill(formats, MTLPixelFormat.Invalid);
        for (int index = 0; index < colorTargetStates.length; index++) {
            ColorTargetState target = colorTargetStates[index];
            if (target != null) {
                formats[index] = MTLPixelFormat.from(target.format());
            }
        }
        return formats;
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        for (MemorySegment pipeline : nativePipelines.values()) {
            if (!ObjC.isNil(pipeline)) {
                ObjC.release(pipeline);
            }
        }
        nativePipelines.clear();
        vertexDescriptor.close();
        device.releaseFunction(this.vertexMsl, this.vertexEntryPoint);
        device.releaseFunction(this.fragmentMsl, this.fragmentEntryPoint);
        device.releaseShader(this.info.getVertexShader(), com.mojang.blaze3d.shaders.ShaderType.VERTEX, this.info.getShaderDefines());
        device.releaseShader(this.info.getFragmentShader(), com.mojang.blaze3d.shaders.ShaderType.FRAGMENT, this.info.getShaderDefines());
    }

    private record DepthStencilFormats(MTLPixelFormat depth, MTLPixelFormat stencil) {
    }
}
