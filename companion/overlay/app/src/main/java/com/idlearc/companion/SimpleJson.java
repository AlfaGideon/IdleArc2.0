package com.idlearc.companion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Компактный RFC-8259 JSON-парсер и сериализатор без внешних зависимостей
 * (одинаково работает в Android Runtime и в чистых JVM-автотестах).
 */
public final class SimpleJson {

    private SimpleJson() {
    }

    public static Object parse(String json) {
        if (json == null) return null;
        Parser p = new Parser(json);
        return p.parseValue();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String json) {
        Object v = parse(json);
        return (v instanceof Map) ? (Map<String, Object>) v : null;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object o) {
        return (o instanceof Map) ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object o) {
        return (o instanceof List) ? (List<Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    public static String str(Map<String, Object> m, String key, String defVal) {
        if (m == null) return defVal;
        Object v = m.get(key);
        if (v == null) return defVal;
        if (v instanceof Map) {
            Map<String, Object> sub = (Map<String, Object>) v;
            String[] nameKeys = {"display_name", "readable_bonus_name", "title", "name", "label", "slug"};
            for (String nk : nameKeys) {
                Object sv = sub.get(nk);
                if (sv instanceof String && !((String) sv).isEmpty()) {
                    return (String) sv;
                }
            }
            return defVal;
        }
        if (v instanceof List) {
            return defVal;
        }
        if (v instanceof Double) {
            double dv = (Double) v;
            if (Math.abs(dv - Math.rint(dv)) < 1e-6 && Math.abs(dv) < 1e15) {
                return String.valueOf(Math.round(dv));
            }
        }
        return String.valueOf(v);
    }

    public static double num(Map<String, Object> m, String key, double defVal) {
        if (m == null) return defVal;
        Object v = m.get(key);
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String) {
            try {
                return Double.parseDouble(((String) v).trim().replace(',', '.'));
            } catch (Exception ignored) {
            }
        }
        return defVal;
    }

    public static int intVal(Map<String, Object> m, String key, int defVal) {
        return (int) Math.round(num(m, key, defVal));
    }

    public static boolean bool(Map<String, Object> m, String key, boolean defVal) {
        if (m == null) return defVal;
        Object v = m.get(key);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).intValue() != 0;
        if (v instanceof String) return "true".equalsIgnoreCase((String) v) || "1".equals(v);
        return defVal;
    }

    public static String quote(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static final class Parser {
        private final String s;
        private final int len;
        private int pos;

        Parser(String s) {
            this.s = s;
            this.len = s.length();
            this.pos = 0;
        }

        private void skipWs() {
            while (pos < len) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == '\uFEFF') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Object parseValue() {
            skipWs();
            if (pos >= len) return null;
            char c = s.charAt(pos);
            if (c == '{') return parseObj();
            if (c == '[') return parseArr();
            if (c == '"') return parseStr();
            if (c == 't' || c == 'f') return parseBool();
            if (c == 'n') return parseNull();
            return parseNumber();
        }

        private Map<String, Object> parseObj() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++; // '{'
            skipWs();
            if (pos < len && s.charAt(pos) == '}') {
                pos++;
                return map;
            }
            while (pos < len) {
                skipWs();
                if (pos >= len || s.charAt(pos) == '}') {
                    pos++;
                    break;
                }
                String key = (s.charAt(pos) == '"') ? parseStr() : parseBareToken();
                skipWs();
                if (pos < len && s.charAt(pos) == ':') pos++;
                Object val = parseValue();
                map.put(key, val);
                skipWs();
                if (pos < len && s.charAt(pos) == ',') {
                    pos++;
                } else if (pos < len && s.charAt(pos) == '}') {
                    pos++;
                    break;
                } else {
                    break;
                }
            }
            return map;
        }

        private List<Object> parseArr() {
            List<Object> list = new ArrayList<>();
            pos++; // '['
            skipWs();
            if (pos < len && s.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (pos < len) {
                skipWs();
                if (pos < len && s.charAt(pos) == ']') {
                    pos++;
                    break;
                }
                list.add(parseValue());
                skipWs();
                if (pos < len && s.charAt(pos) == ',') {
                    pos++;
                } else if (pos < len && s.charAt(pos) == ']') {
                    pos++;
                    break;
                } else {
                    break;
                }
            }
            return list;
        }

        private String parseStr() {
            pos++; // '"'
            StringBuilder sb = new StringBuilder();
            while (pos < len) {
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\' && pos < len) {
                    char esc = s.charAt(pos++);
                    switch (esc) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            if (pos + 4 <= len) {
                                try {
                                    sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                                } catch (Exception ignored) {
                                }
                                pos += 4;
                            }
                            break;
                        default: sb.append(esc); break;
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        private Boolean parseBool() {
            if (s.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            pos++;
            return Boolean.FALSE;
        }

        private Object parseNull() {
            if (s.startsWith("null", pos)) pos += 4;
            else pos++;
            return null;
        }

        private Double parseNumber() {
            int start = pos;
            while (pos < len) {
                char c = s.charAt(pos);
                if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
                    pos++;
                } else {
                    break;
                }
            }
            String tok = s.substring(start, pos);
            try {
                return Double.parseDouble(tok);
            } catch (Exception e) {
                return 0.0;
            }
        }

        private String parseBareToken() {
            int start = pos;
            while (pos < len && s.charAt(pos) != ':' && s.charAt(pos) != '}' && !Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
            return s.substring(start, pos);
        }
    }
}
