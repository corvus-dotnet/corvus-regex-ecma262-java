package io.github.corvusdotnet.regex.ecma262;

/**
 * Reading UTF-16 text, which may hold lone surrogates ({@code Utf16} of {@code units.rs}). Every unit is a position;
 * with the u flag a position inside a surrogate pair is not one the matcher stands at. Each function returns its
 * character packed as {@code codePoint << 3 | width}.
 */
final class Utf16 {
    private Utf16() {}

    private static int pack(int r, int w) {
        return r << 3 | w;
    }

    static boolean high(int u) {
        return u >= 0xD800 && u <= 0xDBFF;
    }

    static boolean low(int u) {
        return u >= 0xDC00 && u <= 0xDFFF;
    }

    /** The code point at the position and its width: a surrogate pair is one code point, a lone surrogate its own. */
    static int decode(String input, int pos) {
        char u = input.charAt(pos);
        if (high(u) && pos + 1 < input.length()) {
            char l = input.charAt(pos + 1);
            if (low(l)) {
                return pack(0x10000 + ((u - 0xD800) << 10) + (l - 0xDC00), 2);
            }
        }
        return pack(u, 1);
    }

    /** The code point that ends at the position, and its width. */
    static int decodeLast(String input, int pos) {
        char u = input.charAt(pos - 1);
        if (low(u) && pos >= 2) {
            char h = input.charAt(pos - 2);
            if (high(h)) {
                return pack(0x10000 + ((h - 0xD800) << 10) + (u - 0xDC00), 2);
            }
        }
        return pack(u, 1);
    }

    static int unitAt(String input, int pos) {
        return pack(input.charAt(pos), 1);
    }

    static int unitBefore(String input, int pos) {
        return pack(input.charAt(pos - 1), 1);
    }

    static int read(String input, int pos, boolean units) {
        return units ? pack(input.charAt(pos), 1) : decode(input, pos);
    }

    static int readLast(String input, int pos, boolean units) {
        return units ? pack(input.charAt(pos - 1), 1) : decodeLast(input, pos);
    }

    static int giveBack(String input, int from, int pos, boolean units) {
        return !units && low(input.charAt(pos - 1)) && pos >= from + 2 && high(input.charAt(pos - 2)) ? 2 : 1;
    }

    static boolean lineTerminatorAt(String input, int pos) {
        char c = input.charAt(pos);
        return c == 0x0A || c == 0x0D || c == 0x2028 || c == 0x2029;
    }

    static boolean lineTerminatorBefore(String input, int pos) {
        char c = input.charAt(pos - 1);
        return c == 0x0A || c == 0x0D || c == 0x2028 || c == 0x2029;
    }

    /**
     * Whether the units {@code input[from..to]}, compared equal to a group's, are the same code points: false only when
     * the comparison ends inside a surrogate pair of the text, which the u flag reads as one code point.
     */
    static boolean whole(String input, int from, int to) {
        int n = input.length();
        boolean startsInside = from > 0 && from < n && low(input.charAt(from)) && high(input.charAt(from - 1));
        boolean endsInside = to > from && to < n && high(input.charAt(to - 1)) && low(input.charAt(to));
        return !(startsInside || endsInside);
    }

    static int normalize(String input, int start, boolean unicode) {
        if (unicode && start > 0 && start < input.length() && low(input.charAt(start)) && high(input.charAt(start - 1))) {
            return start - 1;
        }
        return start;
    }

    static int advance(String input, int pos, boolean unicode) {
        if (pos >= input.length()) {
            return pos + 1;
        }
        return unicode ? pos + (decode(input, pos) & 7) : pos + 1;
    }

    /** Whether a position of UTF-16 text is between the halves of a pair. */
    static boolean midPair(String units, int at) {
        return at > 0 && at < units.length() && low(units.charAt(at)) && high(units.charAt(at - 1));
    }
}
