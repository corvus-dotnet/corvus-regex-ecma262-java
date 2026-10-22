package io.github.corvusdotnet.regex.ecma262;

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
     * A set of code units as ranges (inclusive pairs), for the units a match can start with and for the units that end
     * a run of a loop: one unit by {@link String#indexOf(int, int)}, any other set a unit at a time.
     */
    static final class UnitSet {
        final int[] ranges;
        /** The one unit of a set of one, or -1. */
        final int one;

        UnitSet(int[] ranges) {
            this.ranges = ranges;
            this.one = ranges.length == 2 && ranges[0] == ranges[1] ? ranges[0] : -1;
        }

        boolean contains(int u) {
            int[] r = ranges;
            for (int k = 0; k < r.length; k += 2) {
                if (u - r[k] >= 0 && u <= r[k + 1]) {
                    return true;
                }
            }
            return false;
        }

        /** The first unit at or after {@code from} in the set; -1 for none. */
        int find(String s, int from) {
            if (one >= 0) {
                return s.indexOf(one, from);
            }
            int n = s.length();
            int[] r = ranges;
            if (r.length == 2) {
                int lo = r[0];
                int width = r[1] - lo;
                for (int at = from; at < n; at++) {
                    int d = s.charAt(at) - lo;
                    if (d >= 0 && d <= width) {
                        return at;
                    }
                }
                return -1;
            }
            for (int at = from; at < n; at++) {
                if (contains(s.charAt(at))) {
                    return at;
                }
            }
            return -1;
        }
    }
}
