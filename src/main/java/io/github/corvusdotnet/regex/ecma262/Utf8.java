package io.github.corvusdotnet.regex.ecma262;

/**
 * Reading UTF-8, and matching by UTF-16 code unit over UTF-8 text ({@code units.rs}). A character of one, two or three
 * bytes is one code unit; a character of four bytes is two, a high surrogate and a low one, and the position between
 * them is the offset of the character's third byte. Each function returns its character packed as
 * {@code codePoint << 3 | width}, so that reading one allocates nothing.
 */
final class Utf8 {
    private Utf8() {}

    /** What a byte that is not UTF-8 decodes to, one byte at a time. */
    static final int RUNE_ERROR = 0xFFFD;
    /** The first byte value that is not a character by itself. */
    static final int RUNE_SELF = 0x80;

    static int rune(int packed) {
        return packed >> 3;
    }

    static int width(int packed) {
        return packed & 7;
    }

    private static int pack(int r, int w) {
        return r << 3 | w;
    }

    private static boolean continuation(int b) {
        return (b & 0xC0) == 0x80;
    }

    /**
     * The code point at {@code pos} of {@code p[..end]} and its width: RUNE_ERROR with 1 for bytes that are not
     * UTF-8, and with 0 for no bytes (utf8.DecodeRune).
     */
    static int decodeRune(byte[] p, int pos, int end) {
        if (pos >= end) {
            return pack(RUNE_ERROR, 0);
        }
        int p0 = p[pos] & 0xFF;
        if (p0 < RUNE_SELF) {
            return pack(p0, 1);
        }
        int left = end - pos;
        if (p0 >= 0xC2 && p0 <= 0xDF) {
            if (left >= 2 && continuation(p[pos + 1])) {
                return pack((p0 & 0x1F) << 6 | (p[pos + 1] & 0x3F), 2);
            }
        } else if (p0 >= 0xE0 && p0 <= 0xEF) {
            if (left >= 3 && continuation(p[pos + 2])) {
                int lo = 0x80;
                int hi = 0xBF;
                if (p0 == 0xE0) {
                    lo = 0xA0;
                } else if (p0 == 0xED) {
                    hi = 0x9F;
                }
                int b1 = p[pos + 1] & 0xFF;
                if (b1 >= lo && b1 <= hi) {
                    return pack((p0 & 0x0F) << 12 | (b1 & 0x3F) << 6 | (p[pos + 2] & 0x3F), 3);
                }
            }
        } else if (p0 >= 0xF0 && p0 <= 0xF4) {
            if (left >= 4 && continuation(p[pos + 2]) && continuation(p[pos + 3])) {
                int lo = 0x80;
                int hi = 0xBF;
                if (p0 == 0xF0) {
                    lo = 0x90;
                } else if (p0 == 0xF4) {
                    hi = 0x8F;
                }
                int b1 = p[pos + 1] & 0xFF;
                if (b1 >= lo && b1 <= hi) {
                    return pack((p0 & 0x07) << 18 | (b1 & 0x3F) << 12 | (p[pos + 2] & 0x3F) << 6 | (p[pos + 3] & 0x3F),
                            4);
                }
            }
        }
        return pack(RUNE_ERROR, 1);
    }

    /** The code point at the position and its width. */
    static int decode(byte[] input, int pos) {
        return decodeRune(input, pos, input.length);
    }

    /** The code point at the end of {@code p[from..end]} and its width (utf8.DecodeLastRune). */
    static int decodeLastRune(byte[] p, int from, int end) {
        if (end <= from) {
            return pack(RUNE_ERROR, 0);
        }
        int last = p[end - 1] & 0xFF;
        if (last < RUNE_SELF) {
            return pack(last, 1);
        }
        // A character is four bytes at most, so its first byte is one of the last four.
        int lim = Math.max(end - 4, from);
        int start = end - 1;
        while (start > lim) {
            start--;
            if (!continuation(p[start])) {
                break;
            }
        }
        int rw = decodeRune(p, start, end);
        if (start + width(rw) != end) {
            return pack(RUNE_ERROR, 1);
        }
        return rw;
    }

    /** The code point that ends at the position, and its width. */
    static int decodeLast(byte[] input, int pos) {
        return decodeLastRune(input, 0, pos);
    }

    /** The two UTF-16 code units of a code point beyond the Basic Multilingual Plane. */
    static int highSurrogate(int c) {
        return 0xD800 + ((c - 0x10000) >> 10);
    }

    static int lowSurrogate(int c) {
        return 0xDC00 + ((c - 0x10000) & 0x3FF);
    }

    /** The code unit at the position and the number of bytes it spans. */
    static int unitAt(byte[] input, int pos) {
        int c = input[pos] & 0xFF;
        if (c >= 0xF0) {
            int rw = decodeRune(input, pos, input.length);
            if (width(rw) == 4) {
                return pack(highSurrogate(rune(rw)), 2);
            }
            return pack(RUNE_ERROR, 1);
        }
        if (c >= 0x80 && c < 0xC0 && pos >= 2 && (input[pos - 2] & 0xFF) >= 0xF0) {
            // Between the halves of the character that starts two bytes back.
            int rw = decodeRune(input, pos - 2, input.length);
            if (width(rw) == 4) {
                return pack(lowSurrogate(rune(rw)), 2);
            }
        }
        return decodeRune(input, pos, input.length);
    }

    /** The code unit that ends at the position and the number of bytes it spans. */
    static int unitBefore(byte[] input, int pos) {
        int rw = decodeLastRune(input, 0, pos);
        int r = rune(rw);
        int w = width(rw);
        if (w == 4) {
            return pack(lowSurrogate(r), 2);
        }
        if (w == 1 && r == RUNE_ERROR && pos >= 2 && input.length - pos >= 2 && (input[pos - 2] & 0xFF) >= 0xF0) {
            // Between the halves of the character that starts two bytes back.
            int full = decodeRune(input, pos - 2, input.length);
            if (width(full) == 4) {
                return pack(highSurrogate(rune(full)), 2);
            }
        }
        return rw;
    }

    /** The character at the position: a code unit with {@code units} set, and a code point otherwise. */
    static int read(byte[] input, int pos, boolean units) {
        return units ? unitAt(input, pos) : decodeRune(input, pos, input.length);
    }

    /** {@link #read} for the character that ends at the position. */
    static int readLast(byte[] input, int pos, boolean units) {
        return units ? unitBefore(input, pos) : decodeLastRune(input, 0, pos);
    }

    /** The width of the last character of {@code input[from..pos]}, read as it was read going forward from from. */
    static int giveBack(byte[] input, int from, int pos, boolean units) {
        if ((input[pos - 1] & 0xFF) < RUNE_SELF) {
            return 1;
        } else if (units) {
            return width(unitBefore(input, pos));
        } else {
            return width(decodeLastRune(input, from, pos));
        }
    }

    static boolean lineTerminatorAt(byte[] input, int pos) {
        int c = input[pos] & 0xFF;
        if (c == '\n' || c == '\r') {
            return true;
        }
        return c == 0xE2 && pos + 2 < input.length && (input[pos + 1] & 0xFF) == 0x80
                && ((input[pos + 2] & 0xFF) == 0xA8 || (input[pos + 2] & 0xFF) == 0xA9);
    }

    /** U+2028 and U+2029 are E2 80 A8 and E2 80 A9. */
    static boolean lineTerminatorBefore(byte[] input, int pos) {
        int c = input[pos - 1] & 0xFF;
        if (c == '\n' || c == '\r') {
            return true;
        }
        return (c == 0xA8 || c == 0xA9) && pos >= 3 && (input[pos - 3] & 0xFF) == 0xE2
                && (input[pos - 2] & 0xFF) == 0x80;
    }

    /** Whether units compared equal to a group's are the same code points: always, over UTF-8. */
    static boolean whole(byte[] input, int from, int to) {
        return true;
    }

    /** Whether the position is the one between the halves of a character of four bytes: its third byte. */
    static boolean insideFour(byte[] input, int pos) {
        return pos >= 2 && pos < input.length && continuation(input[pos]) && (input[pos - 2] & 0xFF) >= 0xF0
                && width(decodeRune(input, pos - 2, input.length)) == 4;
    }

    /**
     * The position a search from {@code start} begins at. With the u flag a position inside a character means the
     * character; without it every code unit is a position, a character's first byte or the third byte of one of four.
     */
    static int normalize(byte[] input, int start, boolean unicode) {
        if (start == 0 || start >= input.length || !continuation(input[start])) {
            return start;
        }
        int lead = start;
        while (lead > 0 && continuation(input[lead])) {
            lead--;
        }
        if (!unicode && (input[lead] & 0xFF) >= 0xF0 && start == lead + 2) {
            return start;
        }
        return lead;
    }

    /** The position after the character at pos (AdvanceStringIndex): a code point with u and a code unit without. */
    static int advance(byte[] input, int pos, boolean unicode) {
        if (pos >= input.length) {
            return pos + 1;
        }
        return pos + width(unicode ? decodeRune(input, pos, input.length) : unitAt(input, pos));
    }

    /** Whether a code unit is one of the 63 ASCII word characters. */
    static boolean isWordUnit(int c) {
        return c < 0x80 && CharSet.isWordByte(c);
    }
}
