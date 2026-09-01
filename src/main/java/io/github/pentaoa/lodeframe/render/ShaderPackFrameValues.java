package io.github.pentaoa.lodeframe.render;

record ShaderPackFrameValues(
        int width,
        int height,
        int frame,
        float time,
        ShaderPackFrameContext context,
        float[] previousProjection,
        float[] previousModelView,
        float previousCameraX,
        float previousCameraY,
        float previousCameraZ,
        ShaderPackShadowMatrices shadowMatrices
) implements ShaderPackUniformLayout.FrameValues {
    @Override
    public int integer(final String name) {
        return switch (name) {
            case "frameCounter" -> this.frame;
            case "worldTime" -> this.context.worldTime();
            case "moonPhase" -> this.context.moonPhase();
            case "isEyeInWater" -> this.context.isEyeInWater();
            case "eyeBrightness", "eyeBrightnessSmooth" -> 240;
            case "atlasSize" -> 0;
            case "isRightHanded" -> 1;
            default -> 0;
        };
    }

    @Override
    public int integerComponent(final String name, final int component) {
        if (name.equals("atlasSize")) {
            return component == 0 ? this.context.atlasWidth() : component == 1 ? this.context.atlasHeight() : 0;
        }
        return name.equals("eyeBrightness") || name.equals("eyeBrightnessSmooth") ? 240 : 0;
    }

    @Override
    public float floatComponent(final String name, final int component) {
        return switch (name) {
            case "viewWidth" -> component == 0 ? this.width : 0.0F;
            case "viewHeight" -> component == 0 ? this.height : 0.0F;
            case "aspectRatio" -> component == 0 ? (float) this.width / this.height : 0.0F;
            case "frameTimeCounter" -> component == 0 ? this.time : 0.0F;
            case "frameTime" -> component == 0 ? 1.0F / 60.0F : 0.0F;
            case "near" -> component == 0 ? this.context.near() : 0.0F;
            case "far" -> component == 0 ? this.context.far() : 0.0F;
            case "cameraPosition" -> switch (component) {
                case 0 -> this.context.cameraX();
                case 1 -> this.context.cameraY();
                case 2 -> this.context.cameraZ();
                default -> 0.0F;
            };
            case "previousCameraPosition" -> switch (component) {
                case 0 -> this.previousCameraX;
                case 1 -> this.previousCameraY;
                case 2 -> this.previousCameraZ;
                default -> 0.0F;
            };
            case "rainStrength", "wetness" -> component == 0 ? this.context.rainStrength() : 0.0F;
            case "thunderStrength" -> component == 0 ? this.context.thunderStrength() : 0.0F;
            case "timeAngle", "sunAngle" -> component == 0 ? this.context.timeAngle() : 0.0F;
            case "timeBrightness" -> component == 0 ? this.context.timeBrightness() : 0.0F;
            case "shadowFade" -> component == 0 ? 1.0F : 0.0F;
            case "screenBrightness" -> component == 0 ? this.context.screenBrightness() : 0.0F;
            case "cloudHeight" -> component == 0 ? this.context.cloudHeight() : 0.0F;
            case "endFlashIntensity" -> component == 0 ? this.context.endFlashIntensity() : 0.0F;
            case "cameraPositionFract" -> switch (component) {
                case 0 -> this.context.cameraX() - (float) Math.floor(this.context.cameraX());
                case 1 -> this.context.cameraY() - (float) Math.floor(this.context.cameraY());
                case 2 -> this.context.cameraZ() - (float) Math.floor(this.context.cameraZ());
                default -> 0.0F;
            };
            case "fogColor" -> component < 3 ? this.context.fogColor()[component] : 0.0F;
            case "skyColor" -> component < 3 ? this.context.skyColor()[component] : 0.0F;
            case "sunPosition" -> component < 3 ? this.shadowMatrices.sunPosition()[component] : 0.0F;
            case "upVec" -> component == 1 ? 1.0F : 0.0F;
            case "sunVec" -> switch (component) {
                case 0 -> 0.3F;
                case 1 -> 0.9F;
                case 2 -> 0.2F;
                default -> 0.0F;
            };
            default -> 0.0F;
        };
    }

    @Override
    public float matrixComponent(final String name, final int columns, final int column, final int row) {
        if (columns != 4) {
            return column == row ? 1.0F : 0.0F;
        }
        float[] matrix = switch (name) {
            case "gbufferProjection" -> this.context.projection();
            case "gbufferProjectionInverse" -> this.context.projectionInverse();
            case "gbufferModelView" -> this.context.modelView();
            case "gbufferModelViewInverse" -> this.context.modelViewInverse();
            case "gbufferPreviousProjection" -> this.previousProjection;
            case "gbufferPreviousModelView" -> this.previousModelView;
            case "shadowProjection" -> this.shadowMatrices.projection();
            case "shadowProjectionInverse" -> this.shadowMatrices.projectionInverse();
            case "shadowModelView" -> this.shadowMatrices.modelView();
            case "shadowModelViewInverse" -> this.shadowMatrices.modelViewInverse();
            default -> null;
        };
        return matrix == null ? (column == row ? 1.0F : 0.0F) : matrix[column * columns + row];
    }
}
