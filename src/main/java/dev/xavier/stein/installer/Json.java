/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.installer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON for the launcher files: objects become LinkedHashMap (key order survives a rewrite),
 * lists ArrayList, numbers BigDecimal (the value comes back unchanged). No dependencies: the
 * installer runs on its own, with the Java it is given.
 */
public final class Json {

    private Json() {}

    public static Object parse(String text) {
        Parser p = new Parser(text);
        Object v = p.value();
        p.space();
        if (p.i != text.length()) {
            throw p.error("trailing data after the value");
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object v) {
        if (!(v instanceof Map)) {
            throw new IllegalArgumentException("expected a JSON object");
        }
        return (Map<String, Object>) v;
    }

    /**
     * Follows a path of keys through an object, e.g. get(o, "downloads", "client", "sha1"); null if
     * any is missing.
     */
    public static Object get(Object v, String... keys) {
        for (String k : keys) {
            if (!(v instanceof Map)) {
                return null;
            }
            v = ((Map<?, ?>) v).get(k);
        }
        return v;
    }

    public static String write(Object v) {
        StringBuilder b = new StringBuilder();
        write(b, v, 0);
        return b.append('\n').toString();
    }

    private static void write(StringBuilder b, Object v, int indent) {
        if (v == null) {
            b.append("null");
        } else if (v instanceof String) {
            string(b, (String) v);
        } else if (v instanceof BigDecimal) {
            b.append(((BigDecimal) v).toPlainString());
        } else if (v instanceof Number || v instanceof Boolean) {
            b.append(v);
        } else if (v instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) v;
            if (m.isEmpty()) {
                b.append("{}");
                return;
            }
            b.append("{\n");
            int n = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                pad(b, indent + 1);
                string(b, String.valueOf(e.getKey()));
                b.append(": ");
                write(b, e.getValue(), indent + 1);
                b.append(++n < m.size() ? ",\n" : "\n");
            }
            pad(b, indent);
            b.append('}');
        } else if (v instanceof List) {
            List<?> l = (List<?>) v;
            if (l.isEmpty()) {
                b.append("[]");
                return;
            }
            b.append("[\n");
            for (int i = 0; i < l.size(); i++) {
                pad(b, indent + 1);
                write(b, l.get(i), indent + 1);
                b.append(i + 1 < l.size() ? ",\n" : "\n");
            }
            pad(b, indent);
            b.append(']');
        } else {
            throw new IllegalArgumentException("not a JSON type: " + v.getClass());
        }
    }

    private static void pad(StringBuilder b, int indent) {
        for (int i = 0; i < indent; i++) {
            b.append("  ");
        }
    }

    private static void string(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    b.append("\\\"");
                    break;
                case '\\':
                    b.append("\\\\");
                    break;
                case '\n':
                    b.append("\\n");
                    break;
                case '\r':
                    b.append("\\r");
                    break;
                case '\t':
                    b.append("\\t");
                    break;
                case '\b':
                    b.append("\\b");
                    break;
                case '\f':
                    b.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
            }
        }
        b.append('"');
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        IllegalArgumentException error(String what) {
            return new IllegalArgumentException("invalid JSON (" + what + ") at position " + i);
        }

        void space() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        Object value() {
            space();
            if (i >= s.length()) {
                throw error("unexpected end");
            }
            char c = s.charAt(i);
            switch (c) {
                case '{':
                    return obj();
                case '[':
                    return arr();
                case '"':
                    return str();
                case 't':
                    return word("true", Boolean.TRUE);
                case 'f':
                    return word("false", Boolean.FALSE);
                case 'n':
                    return word("null", null);
                default:
                    return num();
            }
        }

        private Object word(String w, Object v) {
            if (!s.startsWith(w, i)) {
                throw error("expected " + w);
            }
            i += w.length();
            return v;
        }

        private Map<String, Object> obj() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            space();
            if (s.charAt(i) == '}') {
                i++;
                return m;
            }
            while (true) {
                space();
                String k = str();
                space();
                if (s.charAt(i++) != ':') {
                    throw error("expected ':'");
                }
                m.put(k, value());
                space();
                char c = s.charAt(i++);
                if (c == '}') {
                    return m;
                }
                if (c != ',') {
                    throw error("expected ',' or '}'");
                }
            }
        }

        private List<Object> arr() {
            List<Object> l = new ArrayList<>();
            i++;
            space();
            if (s.charAt(i) == ']') {
                i++;
                return l;
            }
            while (true) {
                l.add(value());
                space();
                char c = s.charAt(i++);
                if (c == ']') {
                    return l;
                }
                if (c != ',') {
                    throw error("expected ',' or ']'");
                }
            }
        }

        private String str() {
            if (s.charAt(i) != '"') {
                throw error("expected string");
            }
            i++;
            StringBuilder b = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') {
                    return b.toString();
                }
                if (c != '\\') {
                    b.append(c);
                    continue;
                }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n':
                        b.append('\n');
                        break;
                    case 'r':
                        b.append('\r');
                        break;
                    case 't':
                        b.append('\t');
                        break;
                    case 'b':
                        b.append('\b');
                        break;
                    case 'f':
                        b.append('\f');
                        break;
                    case 'u':
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default:
                        b.append(e);
                }
            }
        }

        private BigDecimal num() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
            if (start == i) {
                throw error("unexpected value");
            }
            return new BigDecimal(s.substring(start, i));
        }
    }
}
