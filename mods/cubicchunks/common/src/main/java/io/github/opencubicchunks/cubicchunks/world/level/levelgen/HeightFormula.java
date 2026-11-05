package io.github.opencubicchunks.cubicchunks.world.level.levelgen;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.DoubleUnaryOperator;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;

/** Bounded arithmetic expressions over absolute block Y, compiled once on edit or load. */
public final class HeightFormula {
    public static final int MAX_LENGTH = 256;
    private static final Map<String, Integer> ARITIES = Map.of("abs", 1, "sin", 1, "cos", 1, "min", 2, "max", 2, "clamp", 3, "if", 3);
    private static final int MAX_DEPTH = 20;
    public static final Codec<HeightFormula> CODEC = Codec.STRING.comapFlatMap(text -> {
        try {
            return DataResult.success(parse(text));
        } catch (IllegalArgumentException e) {
            return DataResult.error(e::getMessage);
        }
    }, HeightFormula::text);
    private final String text;
    private final DoubleUnaryOperator expression;

    private HeightFormula(String text, DoubleUnaryOperator expression) {
        this.text = text;
        this.expression = expression;
    }

    public static HeightFormula parse(String text) {
        if (text.isBlank() || text.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("Formula must contain 1–" + MAX_LENGTH + " characters");
        }
        var reader = new Parser(text);
        var expression = reader.comparison();
        if (reader.peek() != '\0' || reader.at != text.length()) {
            throw reader.error("Unexpected character");
        }
        return new HeightFormula(text, expression);
    }

    public String text() {
        return text;
    }

    public double percent(int y) {
        return bounded(y, 0, DeepCaveSettings.PERCENT, 0);
    }

    public double bounded(int y, int min, int max, int fallback) {
        double value = expression.applyAsDouble(y);
        return Double.isFinite(value) ? Math.clamp(value, min, max) : fallback;
    }

    @Override public boolean equals(Object other) {
        return other instanceof HeightFormula formula && text.equals(formula.text);
    }

    @Override public int hashCode() {
        return text.hashCode();
    }

    private static final class Parser {
        private final String source;
        private int at;
        private int depth;

        private Parser(String source) {
            this.source = source;
        }

        private char peek() {
            while (at < source.length() && Character.isWhitespace(source.charAt(at))) {
                at++;
            }
            return at == source.length() ? '\0' : source.charAt(at);
        }

        private boolean take(String token) {
            peek();
            if (!source.startsWith(token, at)) {
                return false;
            }
            at += token.length();
            return true;
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " @" + (at + 1));
        }

        private DoubleUnaryOperator comparison() {
            var left = sum();
            for (var sign : List.of("<=", ">=", "==", "!=", "<", ">")) {
                if (take(sign)) {
                    var right = sum();
                    return y -> compare(sign, left.applyAsDouble(y), right.applyAsDouble(y)) ? 1 : 0;
                }
            }
            return left;
        }

        private static boolean compare(String sign, double a, double b) {
            return switch (sign) {
                case "<" -> a < b;
                case ">" -> a > b;
                case "<=" -> a <= b;
                case ">=" -> a >= b;
                case "==" -> a == b;
                default -> a != b;
            };
        }

        private DoubleUnaryOperator sum() {
            var result = product();
            while (peek() == '+' || peek() == '-') {
                char op = source.charAt(at++);
                var left = result;
                var right = product();
                result = op == '+' ? y -> left.applyAsDouble(y) + right.applyAsDouble(y) : y -> left.applyAsDouble(y) - right.applyAsDouble(y);
            }
            return result;
        }

        private DoubleUnaryOperator product() {
            var result = unary();
            while (peek() == '*' || peek() == '/') {
                char op = source.charAt(at++);
                var left = result;
                var right = unary();
                result = op == '*' ? y -> left.applyAsDouble(y) * right.applyAsDouble(y) : y -> left.applyAsDouble(y) / right.applyAsDouble(y);
            }
            return result;
        }

        private DoubleUnaryOperator unary() {
            if (++depth > MAX_DEPTH) {
                throw error("Nesting limit");
            }
            DoubleUnaryOperator result;
            if (take("-")) {
                var arg = unary();
                result = y -> -arg.applyAsDouble(y);
            } else if (take("+")) {
                result = unary();
            } else {
                var base = atom();
                if (take("^")) {
                    var exponent = unary();
                    result = y -> Math.pow(base.applyAsDouble(y), exponent.applyAsDouble(y));
                } else {
                    result = base;
                }
            }
            depth--;
            return result;
        }

        private DoubleUnaryOperator atom() {
            if (take("(")) {
                var expr = comparison();
                require(")");
                return expr;
            }
            int start = at;
            if (Character.isLetter(peek())) {
                while (at < source.length() && Character.isLetter(source.charAt(at))) {
                    at++;
                }
                var name = source.substring(start, at).toLowerCase(Locale.ROOT);
                if (name.equals("y")) {
                    return y -> y;
                }
                require("(");
                return name.equals("curve") ? curve() : call(name);
            }
            double number = number();
            return y -> number;
        }

        private DoubleUnaryOperator call(String name) {
            List<DoubleUnaryOperator> args = new ArrayList<>();
            do {
                args.add(comparison());
            } while (take(","));
            require(")");
            return function(name, args);
        }

        private double number() {
            peek();
            int start = at;
            while (at < source.length() && (Character.isDigit(source.charAt(at)) || source.charAt(at) == '.')) {
                at++;
            }
            if (takeExponent()) {
                while (at < source.length() && Character.isDigit(source.charAt(at))) {
                    at++;
                }
            }
            try {
                double number = Double.parseDouble(source.substring(start, at));
                if (!Double.isFinite(number)) {
                    throw error("Expected finite number");
                }
                return number;
            } catch (NumberFormatException e) {
                throw error("Expected number, y or function");
            }
        }

        private boolean takeExponent() {
            if (at == source.length() || Character.toLowerCase(source.charAt(at)) != 'e') {
                return false;
            }
            at++;
            if (at < source.length() && (source.charAt(at) == '-' || source.charAt(at) == '+')) {
                at++;
            }
            return true;
        }

        private double signedNumber() {
            boolean negative = take("-");
            return negative ? -number() : number();
        }

        /** Piecewise linear interpolation between absolute Y/chance pairs, constant beyond the two endpoints. */
        private DoubleUnaryOperator curve() {
            List<Double> heights = new ArrayList<>();
            List<Double> values = new ArrayList<>();
            do {
                double y = signedNumber();
                if (!heights.isEmpty() && y <= heights.getLast()) {
                    throw error("Curve heights must increase");
                }
                require(",");
                heights.add(y);
                values.add(signedNumber());
            } while (take(","));
            require(")");
            var h = heights.stream().mapToDouble(Double::doubleValue).toArray();
            var v = values.stream().mapToDouble(Double::doubleValue).toArray();
            return y -> sampleCurve(y, h, v);
        }

        private static double sampleCurve(double y, double[] h, double[] v) {
            int index = Arrays.binarySearch(h, y);
            if (index >= 0) {
                return v[index];
            }
            int next = -index - 1;
            if (next == 0 || next == h.length) {
                return v[next == 0 ? 0 : next - 1];
            }
            return v[next - 1] + (v[next] - v[next - 1]) * ((y - h[next - 1]) / (h[next] - h[next - 1]));
        }

        private void require(String token) {
            if (!take(token)) {
                throw error("Expected " + token);
            }
        }

        private DoubleUnaryOperator function(String name, List<DoubleUnaryOperator> args) {
            Integer count = ARITIES.get(name);
            if (count == null) {
                throw error("Unknown function " + name);
            }
            if (args.size() != count) {
                throw error(name + " expects " + count + " arguments");
            }
            var first = args.getFirst();
            return switch (name) {
                case "abs" -> y -> Math.abs(first.applyAsDouble(y));
                case "sin" -> y -> Math.sin(first.applyAsDouble(y));
                case "cos" -> y -> Math.cos(first.applyAsDouble(y));
                case "min" -> y -> Math.min(first.applyAsDouble(y), args.get(1).applyAsDouble(y));
                case "max" -> y -> Math.max(first.applyAsDouble(y), args.get(1).applyAsDouble(y));
                case "clamp" -> y -> Math.max(args.get(1).applyAsDouble(y), Math.min(args.get(2).applyAsDouble(y), first.applyAsDouble(y)));
                default -> y -> (first.applyAsDouble(y) != 0 ? args.get(1) : args.get(2)).applyAsDouble(y);
            };
        }
    }
}
