package com.mystersay.schoolbell;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal JSON parser used for the read-only NEPTUN API. No external dependencies. */
public final class SimpleJson {
    private final String text;
    private int pos;

    private SimpleJson(String text) {
        this.text = text == null ? "" : text;
    }

    public static Object parse(String text) {
        SimpleJson p = new SimpleJson(text);
        Object value = p.readValue();
        p.skipWs();
        if (p.pos != p.text.length()) throw p.error("Зайві дані після JSON");
        return value;
    }

    private Object readValue() {
        skipWs();
        if (pos >= text.length()) throw error("Неочікуваний кінець JSON");
        char c = text.charAt(pos);
        return switch (c) {
            case '{' -> readObject();
            case '[' -> readArray();
            case '"' -> readString();
            case 't' -> readLiteral("true", Boolean.TRUE);
            case 'f' -> readLiteral("false", Boolean.FALSE);
            case 'n' -> readLiteral("null", null);
            default -> {
                if (c == '-' || Character.isDigit(c)) yield readNumber();
                throw error("Невідомий JSON-символ: " + c);
            }
        };
    }

    private Map<String, Object> readObject() {
        expect('{');
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        skipWs();
        if (peek('}')) { pos++; return map; }
        while (true) {
            skipWs();
            String key = readString();
            skipWs();
            expect(':');
            Object value = readValue();
            map.put(key, value);
            skipWs();
            if (peek('}')) { pos++; break; }
            expect(',');
        }
        return map;
    }

    private List<Object> readArray() {
        expect('[');
        ArrayList<Object> list = new ArrayList<>();
        skipWs();
        if (peek(']')) { pos++; return list; }
        while (true) {
            list.add(readValue());
            skipWs();
            if (peek(']')) { pos++; break; }
            expect(',');
        }
        return list;
    }

    private String readString() {
        expect('"');
        StringBuilder out = new StringBuilder();
        while (pos < text.length()) {
            char c = text.charAt(pos++);
            if (c == '"') return out.toString();
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (pos >= text.length()) throw error("Незавершений escape у рядку");
            char e = text.charAt(pos++);
            switch (e) {
                case '"', '\\', '/' -> out.append(e);
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    if (pos + 4 > text.length()) throw error("Неповний unicode escape");
                    try {
                        out.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw error("Некоректний unicode escape");
                    }
                    pos += 4;
                }
                default -> throw error("Невідомий escape: \\" + e);
            }
        }
        throw error("Незавершений JSON-рядок");
    }

    private Object readNumber() {
        int start = pos;
        if (peek('-')) pos++;
        while (pos < text.length() && Character.isDigit(text.charAt(pos))) pos++;
        boolean floating = false;
        if (peek('.')) {
            floating = true;
            pos++;
            while (pos < text.length() && Character.isDigit(text.charAt(pos))) pos++;
        }
        if (pos < text.length() && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
            floating = true;
            pos++;
            if (pos < text.length() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) pos++;
            while (pos < text.length() && Character.isDigit(text.charAt(pos))) pos++;
        }
        String raw = text.substring(start, pos);
        try {
            return floating ? Double.parseDouble(raw) : Long.parseLong(raw);
        } catch (NumberFormatException ex) {
            throw error("Некоректне число: " + raw);
        }
    }

    private Object readLiteral(String literal, Object value) {
        if (!text.startsWith(literal, pos)) throw error("Очікувалось " + literal);
        pos += literal.length();
        return value;
    }

    private void skipWs() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++;
    }

    private boolean peek(char c) {
        return pos < text.length() && text.charAt(pos) == c;
    }

    private void expect(char c) {
        skipWs();
        if (!peek(c)) throw error("Очікувався символ '" + c + "'");
        pos++;
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException(message + " (позиція " + pos + ")");
    }
}
