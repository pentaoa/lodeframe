package io.github.pentaoa.lodeframe.render;

import com.mojang.blaze3d.shaders.ShaderType;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ShaderPackProgramConditionsTest {
    @Test
    void parsesBslBooleanProgramConditions() {
        ShaderPackProgramConditions conditions = ShaderPackProgramConditions.parse("""
                program.world0/composite1.enabled=LIGHT_SHAFT
                program.world0/composite6.enabled=FXAA && !RETRO_FILTER
                program.world0/composite7.enabled=(TAA || FXAA) && !RETRO_FILTER
                """);

        ShaderPackProgramConditions.Expression composite6 = conditions.condition("world0/composite6");
        assertEquals(Set.of("FXAA", "RETRO_FILTER"), composite6.variables());
        assertTrue(composite6.evaluate(Set.of("FXAA")));
        assertFalse(composite6.evaluate(Set.of("FXAA", "RETRO_FILTER")));
        assertTrue(conditions.condition("world0/composite7").evaluate(Set.of("TAA")));
    }

    @Test
    void observesDefinesAfterShaderPreprocessorConditionalsAndUndefs() {
        Set<String> defined = ShaderPackGlslPreprocessor.definedMacros("""
                #version 120
                #define SHADOW
                #define LIGHT_SHAFT
                #ifndef SHADOW
                #undef LIGHT_SHAFT
                #endif
                //#define DOF
                void main() { gl_FragColor = vec4(1.0); }
                """, ShaderType.FRAGMENT, Set.of("SHADOW", "LIGHT_SHAFT", "DOF"));

        assertEquals(Set.of("SHADOW", "LIGHT_SHAFT"), defined);
    }
}
