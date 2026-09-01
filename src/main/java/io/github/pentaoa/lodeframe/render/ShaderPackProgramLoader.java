package io.github.pentaoa.lodeframe.render;

import io.github.pentaoa.lodeframe.Lodeframe;
import io.github.pentaoa.lodeframe.shaders.pack.ResolvedShader;
import io.github.pentaoa.lodeframe.shaders.pack.ShaderDirectives;
import io.github.pentaoa.lodeframe.shaders.pack.ShaderEntry;
import io.github.pentaoa.lodeframe.shaders.pack.ShaderPack;
import io.github.pentaoa.lodeframe.shaders.pack.ShaderPackException;
import io.github.pentaoa.lodeframe.shaders.pack.ShaderProgram;
import io.github.pentaoa.lodeframe.shaders.pack.ShaderStage;
import io.github.pentaoa.lodeframe.shaders.translate.LegacyFullscreenTransformer;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.util.Optional;

@Environment(EnvType.CLIENT)
final class ShaderPackProgramLoader {
    private ShaderPackProgramLoader() {
    }

    static PreparedProgram loadFullscreen(
            final ShaderPack pack,
            final ShaderProgram program,
            final long revision
    ) throws IOException, ShaderPackException {
        if (!program.type().fullscreen()) {
            throw new IllegalArgumentException("Shader program is not a fullscreen pass: " + program.key());
        }
        ShaderEntry vertexEntry = program.stage(ShaderStage.VERTEX)
                .orElseThrow(() -> new IllegalArgumentException("Fullscreen program has no vertex stage: " + program.key()));
        ShaderEntry fragmentEntry = program.stage(ShaderStage.FRAGMENT)
                .orElseThrow(() -> new IllegalArgumentException("Fullscreen program has no fragment stage: " + program.key()));

        ResolvedShader vertex = pack.resolve(vertexEntry.path());
        ResolvedShader fragment = pack.resolve(fragmentEntry.path());
        String preprocessedVertex;
        ShaderPackGlslPreprocessor.FragmentSource preprocessedFragment;
        try {
            preprocessedVertex = ShaderPackGlslPreprocessor.preprocess(vertex.source(), ShaderType.VERTEX);
            preprocessedFragment = ShaderPackGlslPreprocessor.preprocessFragment(fragment.source());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unable to preprocess shader program " + program.key(), exception);
        }
        LegacyFullscreenTransformer.TransformedShader transformedVertex = LegacyFullscreenTransformer.transformDetailed(
                ShaderStage.VERTEX,
                preprocessedVertex
        );
        LegacyFullscreenTransformer.TransformedShader transformedFragment = LegacyFullscreenTransformer.transformDetailed(
                ShaderStage.FRAGMENT,
                preprocessedFragment.source()
        );
        Identifier id = Identifier.fromNamespaceAndPath(
                Lodeframe.MOD_ID,
                "shaderpack/" + Long.toUnsignedString(revision) + "/" + program.dimension() + "/" + program.name()
        );
        ShaderSource shaderSource = (requestedId, stage) -> {
            if (!id.equals(requestedId)) {
                return null;
            }
            return switch (stage) {
                case VERTEX -> transformedVertex.source();
                case FRAGMENT -> transformedFragment.source();
            };
        };
        return new PreparedProgram(
                program,
                id,
                shaderSource,
                transformedVertex,
                transformedFragment,
                preprocessedFragment.renderTargets(),
                vertexEntry.path(),
                fragmentEntry.path()
        );
    }

    record PreparedProgram(
            ShaderProgram program,
            Identifier id,
            ShaderSource shaderSource,
            LegacyFullscreenTransformer.TransformedShader vertex,
            LegacyFullscreenTransformer.TransformedShader fragment,
            Optional<ShaderDirectives.RenderTargets> renderTargets,
            String vertexPath,
            String fragmentPath
    ) {
    }
}
