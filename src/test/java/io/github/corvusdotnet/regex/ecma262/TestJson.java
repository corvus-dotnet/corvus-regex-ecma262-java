package io.github.corvusdotnet.regex.ecma262;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader for the test data, whose strings keep lone surrogates, as Java strings can. Objects are maps,
 * arrays lists, numbers doubles.
 */
final class TestJson {
    /** The JSON of a resource of the tests. */
    static Object resource(String name) {
        try (InputStream in = TestJson.class.getResourceAsStream("/" + name)) {
            return new TestJson(new String(in.readAllBytes(), StandardCharsets.UTF_8)).parse();
        } catch (IOException e) {
            throw new IllegalStateException(name, e);
        }
    }

    @SuppressWarnings("unchecked")
    static Object get(Object o, String name) {
        return o instanceof Map ? ((Map<String, Object>) o).get(name) : null;
    }

    @SuppressWarnings("unchecked")
    static List<Object> array(Object o) {
        return o instanceof List ? (List<Object>) o : null;
    }

    private final String s;
    private int i;

    TestJson(String s) {
        this.s = s;
    }

    Object parse() {
        Object v = value();
        space();
        if (i != s.length()) {
            throw new IllegalArgumentException("unexpected text at " + i);
        }
        return v;
    }

    private void space() {
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t' || s.charAt(i) == '\n'
                || s.charAt(i) == '\r')) {
            i++;
        }
    }

    private Object value() {
        space();
        char c = s.charAt(i);
        switch (c) {
            case '{': {
                i++;
                Map<String, Object> m = new LinkedHashMap<>();
                space();
                if (s.charAt(i) == '}') {
                    i++;
                    return m;
                }
                while (true) {
                    space();
                    String key = string();
                    space();
                    expect(':');
                    m.put(key, value());
                    space();
                    if (s.charAt(i) == ',') {
                        i++;
                        continue;
                    }
                    expect('}');
                    return m;
                }
            }
            case '[': {
                i++;
                List<Object> a = new ArrayList<>();
                space();
                if (s.charAt(i) == ']') {
                    i++;
                    return a;
                }
                while (true) {
                    a.add(value());
                    space();
                    if (s.charAt(i) == ',') {
                        i++;
                        continue;
                    }
                    expect(']');
                    return a;
                }
            }
            case '"':
                return string();
            case 't':
                i += 4;
                return Boolean.TRUE;
            case 'f':
                i += 5;
                return Boolean.FALSE;
            case 'n':
                i += 4;
                return null;
            default: {
                int start = i;
                while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
                    i++;
                }
                return Double.parseDouble(s.substring(start, i));
            }
        }
    }

    private void expect(char c) {
        if (s.charAt(i) != c) {
            throw new IllegalArgumentException("expected " + c + " at " + i);
        }
        i++;
    }

    private String string() {
        expect('"');
        StringBuilder out = new StringBuilder();
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') {
                return out.toString();
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            char e = s.charAt(i++);
            switch (e) {
                case 'n':
                    out.append('\n');
                    break;
                case 't':
                    out.append('\t');
                    break;
                case 'r':
                    out.append('\r');
                    break;
                case 'b':
                    out.append('\b');
                    break;
                case 'f':
                    out.append('\f');
                    break;
                case 'u':
                    out.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                    break;
                default:
                    out.append(e);
            }
        }
    }
}
