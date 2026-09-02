package io.github.pentaoa.lodeframe.render;

import io.github.pentaoa.lodeframe.shaders.pack.ShaderPack;
import io.github.pentaoa.lodeframe.shaders.pack.ShaderProgramType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

record ShaderPackCustomTexture(
        String stage,
        String sampler,
        String path,
        byte[] source,
        boolean blur,
        boolean clamp
) {
    private static final Pattern DECLARATION = Pattern.compile(
            "(?m)^[ \\t]*texture\\.([A-Za-z0-9_]+)\\.([A-Za-z_][A-Za-z0-9_]*)[ \\t]*=[ \\t]*([^#\\s]+)"
    );
    private static final Pattern BLUR = Pattern.compile("\\\"blur\\\"\\s*:\\s*(true|false)");
    private static final Pattern CLAMP = Pattern.compile("\\\"clamp\\\"\\s*:\\s*(true|false)");

    static List<ShaderPackCustomTexture> load(final ShaderPack pack, final String properties) throws IOException {
        List<ShaderPackCustomTexture> result = new ArrayList<>();
        Matcher declarations = DECLARATION.matcher(properties);
        while (declarations.find()) {
            String stage = declarations.group(1);
            String sampler = declarations.group(2);
            String path = declarations.group(3);
            byte[] source = pack.readOptionalBytes(path);
            if (source.length == 0) {
                throw new IllegalArgumentException("Missing custom shader-pack texture " + path);
            }
            String metadata = pack.readOptional(path + ".mcmeta");
            result.add(new ShaderPackCustomTexture(
                    stage,
                    sampler,
                    path,
                    source,
                    booleanProperty(BLUR, metadata, false),
                    booleanProperty(CLAMP, metadata, false)
            ));
        }
        return List.copyOf(result);
    }

    boolean appliesTo(final ShaderProgramType type) {
        return this.stage.equals(stageName(type));
    }

    static String stageName(final ShaderProgramType type) {
        return switch (type) {
            case SETUP -> "setup";
            case BEGIN -> "begin";
            case SHADOW -> "shadow";
            case SHADOW_COMPOSITE -> "shadowcomp";
            case PREPARE -> "prepare";
            case GBUFFERS_OPAQUE, GBUFFERS_TRANSLUCENT -> "gbuffers";
            case DEFERRED -> "deferred";
            case COMPOSITE -> "composite";
            case FINAL -> "final";
            case UNKNOWN -> "unknown";
        };
    }

    private static boolean booleanProperty(
            final Pattern pattern,
            final String metadata,
            final boolean fallback
    ) {
        Matcher matcher = pattern.matcher(metadata);
        return matcher.find() ? Boolean.parseBoolean(matcher.group(1)) : fallback;
    }
}
