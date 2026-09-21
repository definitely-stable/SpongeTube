package io.github.definitelystable.spongetube.medialab;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class MiniJsonParser {

    private final String input;
    private int index;

    private MiniJsonParser(String input) {
        this.input = input;
    }

    static Object parse(String input) {
        MiniJsonParser parser = new MiniJsonParser(input);
        Object value = parser.parseValue();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw parser.error("Unexpected trailing JSON content");
        }
        return value;
    }

    private Object parseValue() {
        skipWhitespace();
        if (atEnd()) {
            throw error("Unexpected end of JSON");
        }

        return switch (input.charAt(index)) {
            case '{' -> parseObject();
            case '[' -> parseArray();
            case '"' -> parseString();
            case 't' -> parseLiteral("true", Boolean.TRUE);
            case 'f' -> parseLiteral("false", Boolean.FALSE);
            case 'n' -> parseLiteral("null", null);
            default -> parseNumber();
        };
    }

    private Map<String, Object> parseObject() {
        expect('{');
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        skipWhitespace();
        if (peek('}')) {
            index++;
            return result;
        }

        while (true) {
            skipWhitespace();
            String key = parseString();
            skipWhitespace();
            expect(':');
            Object value = parseValue();

            if (result.putIfAbsent(key, value) != null) {
                throw error("Duplicate JSON object key: " + key);
            }

            skipWhitespace();
            if (peek('}')) {
                index++;
                return result;
            }
            expect(',');
        }
    }

    private List<Object> parseArray() {
        expect('[');
        ArrayList<Object> result = new ArrayList<>();
        skipWhitespace();
        if (peek(']')) {
            index++;
            return result;
        }

        while (true) {
            result.add(parseValue());
            skipWhitespace();
            if (peek(']')) {
                index++;
                return result;
            }
            expect(',');
        }
    }

    private String parseString() {
        expect('"');
        StringBuilder out = new StringBuilder();

        while (!atEnd()) {
            char c = input.charAt(index++);
            if (c == '"') {
                return out.toString();
            }
            if (c != '\\') {
                if (c < 0x20) {
                    throw error("Control character in JSON string");
                }
                out.append(c);
                continue;
            }

            if (atEnd()) {
                throw error("Unterminated JSON escape");
            }

            char escaped = input.charAt(index++);
            switch (escaped) {
                case '"', '\\', '/' -> out.append(escaped);
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> out.append(parseUnicodeEscape());
                default -> throw error("Unsupported JSON escape: \\" + escaped);
            }
        }

        throw error("Unterminated JSON string");
    }

    private char parseUnicodeEscape() {
        if (index + 4 > input.length()) {
            throw error("Incomplete Unicode escape");
        }
        String hex = input.substring(index, index + 4);
        index += 4;
        try {
            return (char) Integer.parseInt(hex, 16);
        } catch (NumberFormatException exception) {
            throw error("Invalid Unicode escape: " + hex);
        }
    }

    private Object parseNumber() {
        int start = index;

        if (peek('-')) {
            index++;
        }
        readDigits();

        boolean decimal = false;
        if (peek('.')) {
            decimal = true;
            index++;
            readDigits();
        }

        if (peek('e') || peek('E')) {
            decimal = true;
            index++;
            if (peek('+') || peek('-')) {
                index++;
            }
            readDigits();
        }

        if (start == index) {
            throw error("Expected JSON value");
        }

        String value = input.substring(start, index);
        try {
            return decimal ? Double.parseDouble(value) : Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw error("Invalid JSON number: " + value);
        }
    }

    private Object parseLiteral(String expected, Object value) {
        if (!input.startsWith(expected, index)) {
            throw error("Expected " + expected);
        }
        index += expected.length();
        return value;
    }

    private void readDigits() {
        int start = index;
        while (!atEnd() && Character.isDigit(input.charAt(index))) {
            index++;
        }
        if (start == index) {
            throw error("Expected digit");
        }
    }

    private void expect(char expected) {
        skipWhitespace();
        if (atEnd() || input.charAt(index) != expected) {
            throw error("Expected '" + expected + "'");
        }
        index++;
    }

    private boolean peek(char expected) {
        return !atEnd() && input.charAt(index) == expected;
    }

    private void skipWhitespace() {
        while (!atEnd() && Character.isWhitespace(input.charAt(index))) {
            index++;
        }
    }

    private boolean atEnd() {
        return index >= input.length();
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException(message + " at JSON offset " + index);
    }
}
