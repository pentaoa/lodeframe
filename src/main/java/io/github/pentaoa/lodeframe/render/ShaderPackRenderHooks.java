package io.github.pentaoa.lodeframe.render;

import io.github.pentaoa.lodeframe.mixin.render.GpuDeviceAccessor;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.GameRenderer;

@Environment(EnvType.CLIENT)
public final class ShaderPackRenderHooks {
    private ShaderPackRenderHooks() {
    }

    public static void processWorldFrame(final GameRenderer gameRenderer) {
        GpuDeviceBackend backend = ((GpuDeviceAccessor) (Object) RenderSystem.getDevice()).lodeframe$getBackend();
        if (backend instanceof MetalDevice metalDevice) {
            metalDevice.shaderPackPostProcessor().processWorld(
                    gameRenderer.mainRenderTarget().getColorTextureView(),
                    ShaderPackFrameContext.from(
                            gameRenderer.gameRenderState().levelRenderState.cameraRenderState
                    )
            );
        }
    }

    public static void captureWorldDepth(final RenderTarget renderTarget) {
        GpuDeviceBackend backend = ((GpuDeviceAccessor) (Object) RenderSystem.getDevice()).lodeframe$getBackend();
        if (backend instanceof MetalDevice metalDevice) {
            metalDevice.shaderPackPostProcessor().captureWorldDepth(renderTarget.getDepthTextureView());
        }
    }
}
