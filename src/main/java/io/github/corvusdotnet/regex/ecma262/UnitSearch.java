package io.github.corvusdotnet.regex.ecma262;

import java.util.ArrayList;
import java.util.List;

/**
 * The searches over UTF-16 text, a {@link String}, that the Rust crate has as {@code simd16.rs}: one unit and one
 * literal by {@link String#indexOf}, which the JIT compiles to vector code, several literals by the packed search, and a
 * set of units as ranges.
 */
final class UnitSearch {
    private UnitSearch() {}

    /** The units packed into bytes at a time for the packed search. */
    static final int PACK = 4096;
    /** The first chunk packed when no chunk of the text is kept, so that a search whose occurrence is near packs little. */
    private static final int FIRST_PACK = 256;

    /**
     * The chunk of a text packed last on a thread, kept for the next search of the same text: a {@code String} cannot
     * change, so a chunk kept for it stays true for every search of it, from any pattern.
     */
    private static final class Pack {
        final byte[] buf = new byte[PACK];
        String text;
        int start;
        int end;
    }

    private static final ThreadLocal<Pack> PACKS = ThreadLocal.withInitial(Pack::new);

    /**
     * The leftmost occurrence at or after {@code from} of one of {@code all} (in the pattern's order, the first of those
     * that start there), packed as {@code start << 32 | end}, or -1: the low bytes of a chunk of units packed into bytes
     * and searched for the low bytes of the literals by {@code finder}, each candidate then compared with the literals
     * themselves (a unit beyond ASCII packs to a byte an ASCII unit has too). The .NET reference engine's packed search,
     * which the crate makes with the ASCII units alone; the low byte keeps the search one for every literal. An
     * occurrence that starts so near the end of a chunk that a literal could start before it and end beyond the chunk is
     * looked for again in the next chunk, which starts that far back.
     */
    @SuppressWarnings("deprecation")
    static long findPacked(String s, int from, ByteFinder finder, String[] all, int longest) {
        int units = s.length();
        if (from > units) {
            return -1;
        }
        int overlap = Math.max(longest - 1, 0);
        Pack pack = PACKS.get();
        byte[] buf = pack.buf;
        int at;
        int end;
        if (pack.text == s && from >= pack.start && from < pack.end) {
            at = pack.start;
            end = pack.end;
        } else {
            at = from;
            end = Math.min(at + FIRST_PACK, units);
            s.getBytes(at, end, buf, 0);
            pack.text = s;
            pack.start = at;
            pack.end = end;
        }
        while (true) {
            int n = end - at;
            boolean last = end == units;
            int limit = last ? n : n - overlap;
            int k = Math.max(from - at, 0);
            while (true) {
                long found = finder.find(buf, k, n);
                if (found < 0) {
                    break;
                }
                int c = (int) (found >>> 32);
                if (c >= limit) {
                    break;
                }
                int start = at + c;
                for (String l : all) {
                    if (s.startsWith(l, start)) {
                        return ByteFinder.span(start, start + l.length());
                    }
                }
                k = c + 1;
            }
            if (last) {
                return -1;
            }
            at = end - overlap;
            end = Math.min(at + PACK, units);
            s.getBytes(at, end, buf, 0);
            pack.text = s;
            pack.start = at;
            pack.end = end;
        }
    }

    /**
     * The chunk of a text copied into units last on a thread, for {@link UnitSet#find} with the Vector API: a {@code
     * String} cannot change, so a chunk kept for it stays true for every search of it.
     */
    private static final class Units {
        final char[] buf = new char[PACK];
        String text;
        int start;
        int end;
    }

    private static final ThreadLocal<Units> UNITS = ThreadLocal.withInitial(Units::new);

    /** The units a set's search looks at one at a time before it searches vectors of them. */
    private static final int NEAR = 4;

    /**
     * A set of code units as ranges (inclusive pairs), for the units a match can start with and for the units that end
     * a run of a loop: one unit by {@link String#indexOf(int, int)}, one range by a compare, and any other set by a table
     * of bits a unit at a time: a block of 256 bits for each high byte of a unit, the blocks the set has nothing in
     * shared, so that a test is two loads and a shift (the ranges tested in turn took fifteen times as long over the
     * Leipzig text).
     */
    static final class UnitSet {
        private static final long[] NONE = new long[4];

        final int[] ranges;
        /** The one unit of a set of one, or -1. */
        final int one;
        /** The blocks of bits by high byte, for a set of more than one range; {@code null} otherwise. */
        private final long[][] blocks;

        UnitSet(int[] ranges) {
            this.ranges = ranges;
            this.one = ranges.length == 2 && ranges[0] == ranges[1] ? ranges[0] : -1;
            if (ranges.length > 2) {
                long[][] b = new long[256][];
                java.util.Arrays.fill(b, NONE);
                for (int k = 0; k < ranges.length; k += 2) {
                    for (int u = ranges[k]; u <= Math.min(ranges[k + 1], 0xFFFF); u++) {
                        if (b[u >>> 8] == NONE) {
                            b[u >>> 8] = new long[4];
                        }
                        b[u >>> 8][(u >>> 6) & 3] |= 1L << u;
                    }
                }
                blocks = b;
            } else {
                blocks = null;
            }
        }

        /**
         * Up to four ranges (each a least unit and a width) that hold every ASCII member of the set, the ranges merged
         * across their narrowest gaps where there are more, for the Vector API's search; a range left over is one no
         * unit is in. A unit beyond ASCII is a candidate where the set has such members ({@link #wide}).
         */
        private char[] lohi;
        private boolean wide;

        private void vectorRanges() {
            List<int[]> ascii = new ArrayList<>();
            boolean beyond = false;
            for (int k = 0; k < ranges.length; k += 2) {
                int lo = ranges[k];
                int hi = Math.min(ranges[k + 1], 0xFFFF);
                if (lo < 0x80) {
                    ascii.add(new int[] {lo, Math.min(hi, 0x7F)});
                }
                beyond |= hi >= 0x80;
            }
            while (ascii.size() > 4) {
                int best = 1;
                for (int k = 2; k < ascii.size(); k++) {
                    if (ascii.get(k)[0] - ascii.get(k - 1)[1] < ascii.get(best)[0] - ascii.get(best - 1)[1]) {
                        best = k;
                    }
                }
                ascii.get(best - 1)[1] = ascii.get(best)[1];
                ascii.remove(best);
            }
            char[] r = new char[8];
            for (int k = 0; k < 4; k++) {
                if (k < ascii.size()) {
                    r[2 * k] = (char) ascii.get(k)[0];
                    r[2 * k + 1] = (char) (ascii.get(k)[1] - ascii.get(k)[0]);
                } else {
                    // Fewer ranges than four: the first repeated.
                    r[2 * k] = r[0];
                    r[2 * k + 1] = r[1];
                }
            }
            if (ascii.isEmpty()) {
                // No ASCII member: every range is 0xFFFF alone (a candidate the caller turns away), and beyond is the
                // test.
                for (int k = 0; k < 4; k++) {
                    r[2 * k] = 0xFFFF;
                    r[2 * k + 1] = 0;
                }
            }
            this.lohi = r;
            this.wide = beyond;
        }

        /** {@link #find} with the Vector API: the text copied into units a chunk at a time, sixteen tested at once. */
        private int findByVectors(String s, int from) {
            if (lohi == null) {
                vectorRanges();
            }
            int n = s.length();
            if (from >= n) {
                return -1;
            }
            Units units = UNITS.get();
            char[] buf = units.buf;
            int at;
            int end;
            if (units.text == s && from >= units.start && from < units.end) {
                at = units.start;
                end = units.end;
            } else {
                at = from;
                end = Math.min(at + PACK, n);
                s.getChars(at, end, buf, 0);
                units.text = s;
                units.start = at;
                units.end = end;
            }
            int k = from - at;
            while (true) {
                int i = Kernels.VECTOR.unitsIndexOf(buf, k, end - at, lohi, wide);
                while (i >= 0) {
                    char u = buf[i];
                    if (contains(u)) {
                        return at + i;
                    }
                    i = Kernels.VECTOR.unitsIndexOf(buf, i + 1, end - at, lohi, wide);
                }
                if (end == n) {
                    return -1;
                }
                at = end;
                end = Math.min(at + PACK, n);
                s.getChars(at, end, buf, 0);
                units.text = s;
                units.start = at;
                units.end = end;
                k = 0;
            }
        }

        boolean contains(int u) {
            if (blocks != null) {
                return (blocks[u >>> 8][(u >>> 6) & 3] >>> u & 1) != 0;
            }
            return u - ranges[0] >= 0 && u <= ranges[1];
        }

        /** The first unit at or after {@code from} in the set; -1 for none. */
        int find(String s, int from) {
            if (one >= 0) {
                return s.indexOf(one, from);
            }
            if (Kernels.VECTOR != null) {
                // The first few units are looked at one at a time first: where the set is most of the text (a word's
                // letters), the next start is a unit or two on, and a search of vectors would cost more than it passes
                // over (\w+ took 1.34 times as long without this).
                int n = s.length();
                int near = Math.min(n, from + NEAR);
                for (int at = from; at < near; at++) {
                    if (contains(s.charAt(at))) {
                        return at;
                    }
                }
                return near < n ? findByVectors(s, near) : -1;
            }
            int n = s.length();
            long[][] b = blocks;
            if (b == null) {
                int lo = ranges[0];
                int width = ranges[1] - lo;
                for (int at = from; at < n; at++) {
                    int d = s.charAt(at) - lo;
                    if (d >= 0 && d <= width) {
                        return at;
                    }
                }
                return -1;
            }
            for (int at = from; at < n; at++) {
                char u = s.charAt(at);
                if ((b[u >>> 8][(u >>> 6) & 3] >>> u & 1) != 0) {
                    return at;
                }
            }
            return -1;
        }
    }
}
