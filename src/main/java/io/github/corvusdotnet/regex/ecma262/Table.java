package io.github.corvusdotnet.regex.ecma262;

/**
 * An immutable set of code points from the Unicode tables. {@code r16} holds the ranges that end at or below U+FFFF
 * and {@code r32} the ranges that start above it, each as inclusive pairs in ascending order. No two ranges of one
 * list overlap or touch. (The {@code Table} of {@code ucd.rs}.)
 */
final class Table {
    final char[] r16;
    final int[] r32;

    private Table(char[] r16, int[] r32) {
        this.r16 = r16;
        this.r32 = r32;
    }

    /** A table from its encoded lists: one char for a 16-bit bound, two for a 32-bit one (see gen_tables.py). */
    static Table of(String r16, String r32) {
        int[] high = new int[r32.length() / 2];
        for (int i = 0; i < high.length; i++) {
            high[i] = r32.charAt(2 * i) << 16 | r32.charAt(2 * i + 1);
        }
        return new Table(r16.toCharArray(), high);
    }

    /** The case mapping rows, four ints each (first, delta, length, mask), from six chars each. */
    static int[] caseRanges(String s) {
        int rows = s.length() / 6;
        int[] out = new int[rows * 4];
        for (int i = 0; i < rows; i++) {
            int k = 6 * i;
            out[4 * i] = s.charAt(k) << 16 | s.charAt(k + 1);
            out[4 * i + 1] = s.charAt(k + 2) << 16 | s.charAt(k + 3);
            out[4 * i + 2] = s.charAt(k + 4);
            out[4 * i + 3] = s.charAt(k + 5);
        }
        return out;
    }

    /** Whether the table holds the code point. */
    boolean contains(int r) {
        if (r <= 0xFFFF) {
            char[] ranges = r16;
            int lo = 0;
            int hi = ranges.length / 2;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (r > ranges[2 * mid + 1]) {
                    lo = mid + 1;
                } else if (r < ranges[2 * mid]) {
                    hi = mid;
                } else {
                    return true;
                }
            }
            return false;
        }
        int[] ranges = r32;
        int lo = 0;
        int hi = ranges.length / 2;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (r > ranges[2 * mid + 1]) {
                lo = mid + 1;
            } else if (r < ranges[2 * mid]) {
                hi = mid;
            } else {
                return true;
            }
        }
        return false;
    }

    /** Appends the ranges of the table to {@code dst} as inclusive pairs in ascending order. */
    void appendRanges(IntList dst) {
        for (char c : r16) {
            dst.add(c);
        }
        int from = 0;
        // A range that crosses U+FFFF is stored in two parts.
        if (r16.length > 0 && r32.length > 0 && r16[r16.length - 1] == 0xFFFF && r32[0] == 0x10000) {
            dst.set(dst.size() - 1, r32[1]);
            from = 2;
        }
        dst.addAll(r32, from, r32.length);
    }

    /** The ranges of the table as inclusive pairs. */
    int[] ranges() {
        IntList pairs = new IntList(r16.length + r32.length);
        appendRanges(pairs);
        return pairs.toArray();
    }
}
