package io.github.pentaoa.lodeframe.render;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.ToDoubleFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Scalar OptiFine/Iris custom uniforms declared in {@code shaders.properties}. */
final class ShaderPackCustomUniforms {
    private static final Pattern DEFINITION = Pattern.compile(
            "^(?:variable|uniform)\\.(?:float|int|bool)\\.([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(.+)$"
    );
    private static final ShaderPackCustomUniforms EMPTY = new ShaderPackCustomUniforms(Map.of());

    private final Map<String, Expression> definitions;
    private final Map<Integer, SmoothValue> smoothValues = new HashMap<>();

    private ShaderPackCustomUniforms(final Map<String, Expression> definitions) {
        this.definitions = Map.copyOf(definitions);
    }

    static ShaderPackCustomUniforms empty() {
        return EMPTY;
    }

    static ShaderPackCustomUniforms parse(final String properties, final int minecraftVersion) {
        Map<String, Expression> definitions = new LinkedHashMap<>();
        for (String line : activeLines(joinContinuations(properties), minecraftVersion)) {
            Matcher matcher = DEFINITION.matcher(line.trim());
            if (matcher.matches()) {
                definitions.put(matcher.group(1), new Parser(matcher.group(2)).parse());
            }
        }
        return definitions.isEmpty() ? EMPTY : new ShaderPackCustomUniforms(definitions);
    }

    Frame beginFrame(
            final float frameTime,
            final ShaderPackFrameContext context,
            final int frameCounter
    ) {
        return new Frame(frameTime, context, frameCounter);
    }

    final class Frame {
        private final float frameTime;
        private final ShaderPackFrameContext context;
        private final int frameCounter;
        private final Map<String, Double> values = new HashMap<>();
        private final Map<Integer, Double> frameSmoothValues = new HashMap<>();
        private final Set<String> evaluating = new java.util.HashSet<>();

        private Frame(
                final float frameTime,
                final ShaderPackFrameContext context,
                final int frameCounter
        ) {
            this.frameTime = Math.max(0.0F, frameTime);
            this.context = context;
            this.frameCounter = frameCounter;
        }

        Double value(final String name, final ToDoubleFunction<String> builtins) {
            Expression definition = definitions.get(name);
            if (definition == null) {
                return null;
            }
            Double cached = this.values.get(name);
            if (cached != null) {
                return cached;
            }
            if (!this.evaluating.add(name)) {
                throw new IllegalStateException("Cyclic shader-pack custom uniform: " + name);
            }
            try {
                double result = definition.evaluate(new Evaluation(this, builtins));
                this.values.put(name, result);
                return result;
            } finally {
                this.evaluating.remove(name);
            }
        }

        private double variable(final String name, final ToDoubleFunction<String> builtins) {
            Double custom = value(name, builtins);
            if (custom != null) {
                return custom;
            }
            return switch (name) {
                case "frameCounter" -> this.frameCounter;
                case "worldTime" -> this.context.worldTime();
                case "blindness" -> this.context.blindness();
                case "biome" -> biomeValue(this.context.biome());
                case "cameraPosition.x" -> this.context.cameraX();
                case "cameraPosition.y" -> this.context.cameraY();
                case "cameraPosition.z" -> this.context.cameraZ();
                case "true" -> 1.0;
                case "false" -> 0.0;
                case "pi", "PI" -> Math.PI;
                default -> name.startsWith("BIOME_")
                        ? biomeValue(name.substring("BIOME_".length()).toLowerCase(Locale.ROOT))
                        : builtins.applyAsDouble(name);
            };
        }

        private double smooth(final int id, final double target, final double halfLifeUp, final double halfLifeDown) {
            Double cached = this.frameSmoothValues.get(id);
            if (cached != null) {
                return cached;
            }
            SmoothValue smooth = smoothValues.computeIfAbsent(id, _ -> new SmoothValue());
            double result = smooth.update(target, halfLifeUp, halfLifeDown, this.frameTime);
            this.frameSmoothValues.put(id, result);
            return result;
        }
    }

    private record Evaluation(Frame frame, ToDoubleFunction<String> builtins) {
        double variable(final String name) {
            return this.frame.variable(name, this.builtins);
        }

        double smooth(final int id, final double target, final double up, final double down) {
            return this.frame.smooth(id, target, up, down);
        }
    }

    private interface Expression {
        double evaluate(Evaluation evaluation);
    }

    private record Constant(double value) implements Expression {
        @Override
        public double evaluate(final Evaluation evaluation) {
            return this.value;
        }
    }

    private record Variable(String name) implements Expression {
        @Override
        public double evaluate(final Evaluation evaluation) {
            return evaluation.variable(this.name);
        }
    }

    private record Unary(String operator, Expression operand) implements Expression {
        @Override
        public double evaluate(final Evaluation evaluation) {
            double value = this.operand.evaluate(evaluation);
            return switch (this.operator) {
                case "+" -> value;
                case "-" -> -value;
                case "!" -> truth(value) ? 0.0 : 1.0;
                default -> throw new IllegalStateException("Unsupported unary operator " + this.operator);
            };
        }
    }

    private record Binary(Expression left, String operator, Expression right) implements Expression {
        @Override
        public double evaluate(final Evaluation evaluation) {
            double leftValue = this.left.evaluate(evaluation);
            if (this.operator.equals("&&") && !truth(leftValue)) {
                return 0.0;
            }
            if (this.operator.equals("||") && truth(leftValue)) {
                return 1.0;
            }
            double rightValue = this.right.evaluate(evaluation);
            return switch (this.operator) {
                case "+" -> leftValue + rightValue;
                case "-" -> leftValue - rightValue;
                case "*" -> leftValue * rightValue;
                case "/" -> leftValue / rightValue;
                case "%" -> leftValue % rightValue;
                case "<" -> bool(leftValue < rightValue);
                case "<=" -> bool(leftValue <= rightValue);
                case ">" -> bool(leftValue > rightValue);
                case ">=" -> bool(leftValue >= rightValue);
                case "==" -> bool(leftValue == rightValue);
                case "!=" -> bool(leftValue != rightValue);
                case "&&" -> bool(truth(rightValue));
                case "||" -> bool(truth(rightValue));
                default -> throw new IllegalStateException("Unsupported binary operator " + this.operator);
            };
        }
    }

    private record Call(String name, List<Expression> arguments) implements Expression {
        @Override
        public double evaluate(final Evaluation evaluation) {
            if (this.name.equals("if")) {
                requireArguments(3);
                return this.arguments.get(0).evaluate(evaluation) != 0.0
                        ? this.arguments.get(1).evaluate(evaluation)
                        : this.arguments.get(2).evaluate(evaluation);
            }
            if (this.name.equals("in")) {
                if (this.arguments.size() < 2) {
                    throw new IllegalArgumentException("in requires at least two arguments");
                }
                double value = this.arguments.get(0).evaluate(evaluation);
                for (int index = 1; index < this.arguments.size(); index++) {
                    if (value == this.arguments.get(index).evaluate(evaluation)) {
                        return 1.0;
                    }
                }
                return 0.0;
            }
            if (this.name.equals("smooth")) {
                requireArguments(4);
                return evaluation.smooth(
                        (int) this.arguments.get(0).evaluate(evaluation),
                        this.arguments.get(1).evaluate(evaluation),
                        this.arguments.get(2).evaluate(evaluation),
                        this.arguments.get(3).evaluate(evaluation)
                );
            }

            double first = argument(evaluation, 0);
            return switch (this.name) {
                case "sin" -> Math.sin(first);
                case "cos" -> Math.cos(first);
                case "tan" -> Math.tan(first);
                case "asin" -> Math.asin(first);
                case "acos" -> Math.acos(first);
                case "atan" -> this.arguments.size() == 1
                        ? Math.atan(first)
                        : Math.atan2(first, argument(evaluation, 1));
                case "abs" -> Math.abs(first);
                case "floor" -> Math.floor(first);
                case "ceil" -> Math.ceil(first);
                case "round" -> Math.round(first);
                case "sqrt" -> Math.sqrt(first);
                case "exp" -> Math.exp(first);
                case "log" -> Math.log(first);
                case "sign" -> Math.signum(first);
                case "frac" -> first - Math.floor(first);
                case "min" -> Math.min(first, argument(evaluation, 1));
                case "max" -> Math.max(first, argument(evaluation, 1));
                case "pow" -> Math.pow(first, argument(evaluation, 1));
                case "clamp" -> Math.max(argument(evaluation, 1), Math.min(argument(evaluation, 2), first));
                case "mix" -> {
                    double right = argument(evaluation, 1);
                    double factor = argument(evaluation, 2);
                    yield first * (1.0 - factor) + right * factor;
                }
                default -> throw new IllegalArgumentException("Unsupported shader-pack expression function: " + this.name);
            };
        }

        private double argument(final Evaluation evaluation, final int index) {
            if (index >= this.arguments.size()) {
                throw new IllegalArgumentException(this.name + " is missing argument " + (index + 1));
            }
            return this.arguments.get(index).evaluate(evaluation);
        }

        private void requireArguments(final int count) {
            if (this.arguments.size() != count) {
                throw new IllegalArgumentException(this.name + " requires " + count + " arguments");
            }
        }
    }

    private static final class SmoothValue {
        private boolean initialized;
        private double value;

        double update(final double target, final double halfLifeUp, final double halfLifeDown, final double frameTime) {
            if (!this.initialized) {
                this.initialized = true;
                this.value = target;
                return target;
            }
            double halfLife = target > this.value ? halfLifeUp : halfLifeDown;
            if (halfLife == 0.0) {
                this.value = target;
                return target;
            }
            double decay = Math.log(2.0) / (halfLife * 0.1);
            double factor = 1.0 - Math.exp(-decay * frameTime);
            this.value = this.value * (1.0 - factor) + target * factor;
            return this.value;
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
                result = new Binary(result, "||", parseAnd());
            }
            return result;
        }

        private Expression parseAnd() {
            Expression result = parseEquality();
            while (match("&&")) {
                result = new Binary(result, "&&", parseEquality());
            }
            return result;
        }

        private Expression parseEquality() {
            Expression result = parseComparison();
            while (true) {
                if (match("==")) {
                    result = new Binary(result, "==", parseComparison());
                } else if (match("!=")) {
                    result = new Binary(result, "!=", parseComparison());
                } else {
                    return result;
                }
            }
        }

        private Expression parseComparison() {
            Expression result = parseAdditive();
            while (true) {
                if (match("<=")) {
                    result = new Binary(result, "<=", parseAdditive());
                } else if (match(">=")) {
                    result = new Binary(result, ">=", parseAdditive());
                } else if (match("<")) {
                    result = new Binary(result, "<", parseAdditive());
                } else if (match(">")) {
                    result = new Binary(result, ">", parseAdditive());
                } else {
                    return result;
                }
            }
        }

        private Expression parseAdditive() {
            Expression result = parseMultiplicative();
            while (true) {
                if (match("+")) {
                    result = new Binary(result, "+", parseMultiplicative());
                } else if (match("-")) {
                    result = new Binary(result, "-", parseMultiplicative());
                } else {
                    return result;
                }
            }
        }

        private Expression parseMultiplicative() {
            Expression result = parseUnary();
            while (true) {
                if (match("*")) {
                    result = new Binary(result, "*", parseUnary());
                } else if (match("/")) {
                    result = new Binary(result, "/", parseUnary());
                } else if (match("%")) {
                    result = new Binary(result, "%", parseUnary());
                } else {
                    return result;
                }
            }
        }

        private Expression parseUnary() {
            if (match("+")) return new Unary("+", parseUnary());
            if (match("-")) return new Unary("-", parseUnary());
            if (match("!")) return new Unary("!", parseUnary());
            return parsePrimary();
        }

        private Expression parsePrimary() {
            skipWhitespace();
            if (match("(")) {
                Expression result = parseOr();
                require(")");
                return result;
            }
            if (this.position < this.source.length()
                    && (Character.isDigit(this.source.charAt(this.position)) || this.source.charAt(this.position) == '.')) {
                return parseNumber();
            }
            String name = parseIdentifier();
            if (match("(")) {
                List<Expression> arguments = new ArrayList<>();
                if (!peek(")")) {
                    do {
                        arguments.add(parseOr());
                    } while (match(","));
                }
                require(")");
                return new Call(name, List.copyOf(arguments));
            }
            return new Variable(name);
        }

        private Expression parseNumber() {
            int start = this.position;
            while (this.position < this.source.length()) {
                char character = this.source.charAt(this.position);
                if (!Character.isDigit(character) && character != '.' && character != 'e' && character != 'E'
                        && character != '+' && character != '-') {
                    break;
                }
                if ((character == '+' || character == '-')
                        && this.position > start
                        && this.source.charAt(this.position - 1) != 'e'
                        && this.source.charAt(this.position - 1) != 'E') {
                    break;
                }
                this.position++;
            }
            if (this.position < this.source.length()
                    && (this.source.charAt(this.position) == 'f' || this.source.charAt(this.position) == 'F')) {
                this.position++;
            }
            String number = this.source.substring(start, this.position).replace("f", "").replace("F", "");
            return new Constant(Double.parseDouble(number));
        }

        private String parseIdentifier() {
            skipWhitespace();
            int start = this.position;
            while (this.position < this.source.length()) {
                char character = this.source.charAt(this.position);
                if (!Character.isLetterOrDigit(character) && character != '_' && character != '.') {
                    break;
                }
                this.position++;
            }
            if (start == this.position) {
                throw error("Expected expression");
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

        private boolean peek(final String token) {
            skipWhitespace();
            return this.source.startsWith(token, this.position);
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

    private static List<String> activeLines(final String source, final int minecraftVersion) {
        List<String> result = new ArrayList<>();
        Deque<Conditional> conditionals = new ArrayDeque<>();
        boolean active = true;
        for (String rawLine : source.lines().toList()) {
            String line = rawLine.trim();
            if (line.startsWith("#if ")) {
                boolean condition = evaluateCondition(line.substring(4).trim(), minecraftVersion);
                conditionals.push(new Conditional(active, condition));
                active = active && condition;
            } else if (line.startsWith("#ifdef ")) {
                conditionals.push(new Conditional(active, false));
                active = false;
            } else if (line.startsWith("#ifndef ")) {
                conditionals.push(new Conditional(active, true));
            } else if (line.equals("#else")) {
                Conditional conditional = conditionals.peek();
                if (conditional == null) {
                    throw new IllegalArgumentException("Unexpected #else in shaders.properties");
                }
                active = conditional.parentActive() && !conditional.condition();
            } else if (line.equals("#endif")) {
                Conditional conditional = conditionals.poll();
                if (conditional == null) {
                    throw new IllegalArgumentException("Unexpected #endif in shaders.properties");
                }
                active = conditional.parentActive();
            } else if (active) {
                result.add(rawLine);
            }
        }
        if (!conditionals.isEmpty()) {
            throw new IllegalArgumentException("Unterminated conditional in shaders.properties");
        }
        return result;
    }

    private static boolean evaluateCondition(final String expression, final int minecraftVersion) {
        Matcher matcher = Pattern.compile("MC_VERSION\\s*(>=|<=|==|!=|>|<)\\s*(\\d+)").matcher(expression);
        if (!matcher.matches()) {
            return false;
        }
        int right = Integer.parseInt(matcher.group(2));
        return switch (matcher.group(1)) {
            case ">=" -> minecraftVersion >= right;
            case "<=" -> minecraftVersion <= right;
            case "==" -> minecraftVersion == right;
            case "!=" -> minecraftVersion != right;
            case ">" -> minecraftVersion > right;
            case "<" -> minecraftVersion < right;
            default -> false;
        };
    }

    private static String joinContinuations(final String properties) {
        return properties.replaceAll("\\\\[ \\t]*\\R[ \\t]*", " ");
    }

    private static double biomeValue(final String biome) {
        return biome == null ? 0.0 : biome.toLowerCase(Locale.ROOT).hashCode();
    }

    private static boolean truth(final double value) {
        return value != 0.0;
    }

    private static double bool(final boolean value) {
        return value ? 1.0 : 0.0;
    }

    private record Conditional(boolean parentActive, boolean condition) {
    }
}
