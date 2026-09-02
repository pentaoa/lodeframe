package io.github.pentaoa.lodeframe.render;

import com.mojang.blaze3d.shaders.ShaderType;
import io.github.pentaoa.lodeframe.shaders.pack.ResolvedShader;
import io.github.pentaoa.lodeframe.shaders.pack.ShaderEntry;
import io.github.pentaoa.lodeframe.shaders.pack.ShaderPack;
import io.github.pentaoa.lodeframe.shaders.pack.ShaderPackException;
import io.github.pentaoa.lodeframe.shaders.pack.ShaderProgram;
import io.github.pentaoa.lodeframe.shaders.pack.ShaderStage;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ShaderPackProgramConditions {
    private static final Pattern DECLARATION = Pattern.compile(
            "(?m)^[ \\t]*program\\.([^=\\s]+)\\.enabled[ \\t]*=[ \\t]*([^#\\r\\n]+)"
    );
    private static final ShaderPackProgramConditions EMPTY = new ShaderPackProgramConditions(Map.of());

    private final Map<String, Expression> conditions;

    private ShaderPackProgramConditions(final Map<String, Expression> conditions) {
        this.conditions = Map.copyOf(conditions);
    }

    static ShaderPackProgramConditions parse(final String properties) {
        Map<String, Expression> conditions = new HashMap<>();
        Matcher matcher = DECLARATION.matcher(properties);
        while (matcher.find()) {
            conditions.put(matcher.group(1), new Parser(matcher.group(2)).parse());
        }
        return conditions.isEmpty() ? EMPTY : new ShaderPackProgramConditions(conditions);
    }

    boolean enabled(final ShaderPack pack, final ShaderProgram program) throws IOException, ShaderPackException {
        Expression condition = this.conditions.get(program.key());
        if (condition == null) {
            return true;
        }
        ShaderEntry entry = program.stage(ShaderStage.FRAGMENT)
                .or(() -> program.stage(ShaderStage.VERTEX))
                .orElseThrow(() -> new IllegalArgumentException("Conditional program has no shader stage: " + program.key()));
        ResolvedShader source = pack.resolve(entry.path());
        ShaderType stage = entry.stage() == ShaderStage.FRAGMENT ? ShaderType.FRAGMENT : ShaderType.VERTEX;
        Set<String> defined = ShaderPackGlslPreprocessor.definedMacros(source.source(), stage, condition.variables());
        return condition.evaluate(defined);
    }

    Expression condition(final String programKey) {
        return this.conditions.get(programKey);
    }

    interface Expression {
        boolean evaluate(Set<String> definedMacros);

        Set<String> variables();
    }

    private record Variable(String name) implements Expression {
        @Override
        public boolean evaluate(final Set<String> definedMacros) {
            return switch (this.name) {
                case "true" -> true;
                case "false" -> false;
                default -> definedMacros.contains(this.name);
            };
        }

        @Override
        public Set<String> variables() {
            return this.name.equals("true") || this.name.equals("false") ? Set.of() : Set.of(this.name);
        }
    }

    private record Not(Expression operand) implements Expression {
        @Override
        public boolean evaluate(final Set<String> definedMacros) {
            return !this.operand.evaluate(definedMacros);
        }

        @Override
        public Set<String> variables() {
            return this.operand.variables();
        }
    }

    private record Binary(Expression left, boolean and, Expression right) implements Expression {
        @Override
        public boolean evaluate(final Set<String> definedMacros) {
            return this.and
                    ? this.left.evaluate(definedMacros) && this.right.evaluate(definedMacros)
                    : this.left.evaluate(definedMacros) || this.right.evaluate(definedMacros);
        }

        @Override
        public Set<String> variables() {
            Set<String> result = new LinkedHashSet<>(this.left.variables());
            result.addAll(this.right.variables());
            return Set.copyOf(result);
        }
    }

    private static final class Parser {
        private final String source;
        private int position;

        private Parser(final String source) {
            this.source = source;
        }

        Expression parse() {
            Expression result = parseOr();
            skipWhitespace();
            if (this.position != this.source.length()) {
                throw error("Unexpected token");
            }
            return result;
        }

        private Expression parseOr() {
            Expression result = parseAnd();
            while (match("||")) {
                result = new Binary(result, false, parseAnd());
            }
            return result;
        }

        private Expression parseAnd() {
            Expression result = parseUnary();
            while (match("&&")) {
                result = new Binary(result, true, parseUnary());
            }
            return result;
        }

        private Expression parseUnary() {
            if (match("!")) {
                return new Not(parseUnary());
            }
            if (match("(")) {
                Expression result = parseOr();
                require(")");
                return result;
            }
            return new Variable(identifier());
        }

        private String identifier() {
            skipWhitespace();
            int start = this.position;
            while (this.position < this.source.length()) {
                char character = this.source.charAt(this.position);
                if (!Character.isLetterOrDigit(character) && character != '_') {
                    break;
                }
                this.position++;
            }
            if (start == this.position) {
                throw error("Expected option name");
            }
            return this.source.substring(start, this.position);
        }

        private boolean match(final String token) {
            skipWhitespace();
            if (!this.source.startsWith(token, this.position)) {
                return false;
            }
            this.position += token.length();
            return true;
        }

        private void require(final String token) {
            if (!match(token)) {
                throw error("Expected '" + token + "'");
            }
        }

        private void skipWhitespace() {
            while (this.position < this.source.length() && Character.isWhitespace(this.source.charAt(this.position))) {
                this.position++;
            }
        }

        private IllegalArgumentException error(final String message) {
            return new IllegalArgumentException(message + " at " + this.position + " in " + this.source);
        }
    }
}
