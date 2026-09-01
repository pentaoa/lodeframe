package io.github.pentaoa.lodeframe.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class MetalCrossShaderCompilerTest {
    @Test
    void renamesTheGlslIdentifierThatIsReservedByMsl() {
        assertEquals(
                "bool lodeframe_new = old_new; lodeframe_new = !lodeframe_new;",
                MetalCrossShaderCompiler.sanitizeMsl("bool new = old_new; new = !new;")
        );
    }
}
