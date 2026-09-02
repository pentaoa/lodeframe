package io.github.pentaoa.lodeframe.render;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.util.ARGB;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

record ShaderPackFrameContext(
        float[] projection,
        float[] projectionInverse,
        float[] modelView,
        float[] modelViewInverse,
        float cameraX,
        float cameraY,
        float cameraZ,
        float relativeEyeX,
        float relativeEyeY,
        float relativeEyeZ,
        float near,
        float far,
        int worldTime,
        int moonPhase,
        int isEyeInWater,
        int eyeBrightnessBlock,
        int eyeBrightnessSky,
        int bedrockLevel,
        int heightLimit,
        int heldItemId,
        int heldItemId2,
        int heldBlockLightValue,
        int heldBlockLightValue2,
        float rainStrength,
        float thunderStrength,
        float timeAngle,
        float sunAngle,
        float timeBrightness,
        float[] fogColor,
        float[] skyColor,
        float cloudHeight,
        float endFlashIntensity,
        int atlasWidth,
        int atlasHeight,
        float screenBrightness,
        String shaderDimension
) {
    static ShaderPackFrameContext from(
            final CameraRenderState camera,
            final LevelRenderState levelState,
            final ClientLevel level,
            final @Nullable Entity cameraEntity,
            final @Nullable LivingEntity player,
            final float partialTick,
            final int atlasWidth,
            final int atlasHeight,
            final float screenBrightness
    ) {
        long clockTime = level.getOverworldClockTime();
        int worldTime = (int) Math.floorMod(clockTime, 24000L);
        float timeAngle = linearTimeAngle(worldTime);
        float sunAngle = normalizedSunAngle(levelState.skyRenderState.sunAngle);
        float timeBrightness = Math.max(0.0F, (float) Math.sin(timeAngle * Math.PI * 2.0));
        int eyeMedium = switch (camera.fogType) {
            case WATER -> 1;
            case LAVA -> 2;
            default -> 0;
        };
        Vec3 eyePosition = cameraEntity == null ? camera.pos : cameraEntity.getEyePosition(partialTick);
        BlockPos eyeBlockPosition = BlockPos.containing(eyePosition);
        ItemStack mainHand = player == null ? ItemStack.EMPTY : player.getMainHandItem();
        ItemStack offHand = player == null ? ItemStack.EMPTY : player.getOffhandItem();
        return create(
                camera,
                (float) (camera.pos.x - eyePosition.x),
                (float) (camera.pos.y - eyePosition.y),
                (float) (camera.pos.z - eyePosition.z),
                worldTime,
                (int) Math.floorMod(Math.floorDiv(clockTime, 24000L), 8L),
                eyeMedium,
                level.getBrightness(LightLayer.BLOCK, eyeBlockPosition) * 16,
                level.getBrightness(LightLayer.SKY, eyeBlockPosition) * 16,
                level.dimensionType().minY(),
                level.dimensionType().height(),
                ShaderPackItemIds.id(mainHand),
                ShaderPackItemIds.id(offHand),
                ShaderPackItemIds.lightEmission(mainHand),
                ShaderPackItemIds.lightEmission(offHand),
                level.getRainLevel(partialTick),
                level.getThunderLevel(partialTick),
                timeAngle,
                sunAngle,
                timeBrightness,
                levelState,
                atlasWidth,
                atlasHeight,
                screenBrightness,
                level.dimension() == Level.NETHER ? "world-1" : level.dimension() == Level.END ? "world1" : "world0"
        );
    }

    static ShaderPackFrameContext from(final CameraRenderState camera) {
        return create(
                camera,
                0.0F,
                0.0F,
                0.0F,
                0,
                0,
                0,
                240,
                240,
                -64,
                384,
                -1,
                -1,
                0,
                0,
                0.0F,
                0.0F,
                0.0F,
                0.25F,
                0.0F,
                null,
                1,
                1,
                1.0F,
                "world0"
        );
    }

    private static ShaderPackFrameContext create(
            final CameraRenderState camera,
            final float relativeEyeX,
            final float relativeEyeY,
            final float relativeEyeZ,
            final int worldTime,
            final int moonPhase,
            final int eyeMedium,
            final int eyeBrightnessBlock,
            final int eyeBrightnessSky,
            final int bedrockLevel,
            final int heightLimit,
            final int heldItemId,
            final int heldItemId2,
            final int heldBlockLightValue,
            final int heldBlockLightValue2,
            final float rainStrength,
            final float thunderStrength,
            final float timeAngle,
            final float sunAngle,
            final float timeBrightness,
            final LevelRenderState levelState,
            final int atlasWidth,
            final int atlasHeight,
            final float screenBrightness,
            final String shaderDimension
    ) {
        Matrix4f clipDepthRemap = new Matrix4f().identity().m22(-2.0F).m32(1.0F);
        Matrix4f projection = clipDepthRemap.mul(camera.projectionMatrix, new Matrix4f());
        Matrix4f modelView = new Matrix4f(camera.viewRotationMatrix);
        Vector4f fog = camera.fogData == null ? new Vector4f() : camera.fogData.color;
        Vector3f sky = levelState == null
                ? new Vector3f()
                : ARGB.vector3fFromRGB24(levelState.skyRenderState.skyColor);
        return new ShaderPackFrameContext(
                projection.get(new float[16]),
                projection.invert(new Matrix4f()).get(new float[16]),
                modelView.get(new float[16]),
                modelView.invert(new Matrix4f()).get(new float[16]),
                (float) camera.pos.x,
                (float) camera.pos.y,
                (float) camera.pos.z,
                relativeEyeX,
                relativeEyeY,
                relativeEyeZ,
                0.05F,
                camera.depthFar,
                worldTime,
                moonPhase,
                eyeMedium,
                eyeBrightnessBlock,
                eyeBrightnessSky,
                bedrockLevel,
                heightLimit,
                heldItemId,
                heldItemId2,
                heldBlockLightValue,
                heldBlockLightValue2,
                rainStrength,
                thunderStrength,
                timeAngle,
                sunAngle,
                timeBrightness,
                new float[]{fog.x, fog.y, fog.z},
                new float[]{sky.x, sky.y, sky.z},
                levelState == null ? 192.0F : levelState.cloudHeight,
                levelState == null ? 0.0F : levelState.skyRenderState.endFlashIntensity,
                Math.max(1, atlasWidth),
                Math.max(1, atlasHeight),
                screenBrightness,
                shaderDimension
        );
    }

    static float linearTimeAngle(final int worldTime) {
        return worldTime / 24000.0F;
    }

    static float normalizedSunAngle(final float radians) {
        float result = radians / ((float) Math.PI * 2.0F) + 0.25F;
        return result - (float) Math.floor(result);
    }
}
