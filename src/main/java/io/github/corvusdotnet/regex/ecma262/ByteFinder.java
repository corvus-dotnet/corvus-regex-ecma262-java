package io.github.corvusdotnet.regex.ecma262;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A search for one of a set of literals in a text's bytes ({@code Finder} of {@code search.rs}). Its answer is the
 * leftmost occurrence at or after a position, the first literal in their order of those that start there, packed as
 * {@code start << 32 | end}, or -1 for none.
 *
 * <p>The Rust crate takes {@code memchr::memmem}, {@code aho-corasick} and its packed searcher Teddy; Java has none of
 * them, so the searches here are written on {@link Swar}: a byte rare in text by {@link Swar#indexOf}, a few literals by
 * two of their byte positions ({@link Pair}, PCRE2's search, which the crate has as {@code pair8.rs} and which here
 * takes one literal too, in place of {@code memmem}'s search for its two rarest bytes), and any other set by a table of
 * the bytes at its rarest position ({@link Table}).
 */
abstract class ByteFinder {
    /** The leftmost occurrence that starts at or after {@code from} and ends at or before {@code end}. */
    abstract long find(byte[] h, int from, int end);

    final long find(byte[] h, int from) {
        return find(h, from, h.length);
    }

    static long span(int start, int end) {
        return (long) start << 32 | (end & 0xFFFFFFFFL);
    }

    /**
     * How common each byte is in text, from 0 (rare) to 255: the table {@code memchr::memmem} ranks a needle's bytes by
     * ({@code memchr} 2.8, {@code arch/all/packedpair/default_rank.rs}; MIT or Unlicense), which {@code pair8.rs} takes.
     */
    static final int[] RANK = {
        55, 52, 51, 50, 49, 48, 47, 46, 45, 103, 242, 66, 67, 229, 44, 43, 42, 41, 40, 39, 38, 37, 36, 35, 34, 33, 56, 32,
        31, 30, 29, 28, 255, 148, 164, 149, 136, 160, 155, 173, 221, 222, 134, 122, 232, 202, 215, 224, 208, 220, 204, 187,
        183, 179, 177, 168, 178, 200, 226, 195, 154, 184, 174, 126, 120, 191, 157, 194, 170, 189, 162, 161, 150, 193, 142,
        137, 171, 176, 185, 167, 186, 112, 175, 192, 188, 156, 140, 143, 123, 133, 128, 147, 138, 146, 114, 223, 151, 249,
        216, 238, 236, 253, 227, 218, 230, 247, 135, 180, 241, 233, 246, 244, 231, 139, 245, 243, 251, 235, 201, 196, 240,
        214, 152, 182, 205, 181, 127, 27, 212, 211, 210, 213, 228, 197, 169, 159, 131, 172, 105, 80, 98, 96, 97, 81, 207,
        145, 116, 115, 144, 130, 153, 121, 107, 132, 109, 110, 124, 111, 82, 108, 118, 141, 113, 129, 119, 125, 165, 117,
        92, 106, 83, 72, 99, 93, 65, 79, 166, 237, 163, 199, 190, 225, 209, 203, 198, 217, 219, 206, 234, 248, 158, 239,
        255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
        255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
        255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
    };

    /**
     * The bytes rare enough in text that a search for one of them, and a compare where it is found, is faster than the
     * search for a literal's two rarest bytes, from the rarest: measured over the Industry texts, one in a thousand bytes
     * or fewer ({@code VERY_RARE} of {@code search.rs}).
     */
    private static final byte[] VERY_RARE = "ZXQKVJzqjx".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    /**
     * Whether a byte is rare enough in text for a search for it alone to pay: not a lower case letter (but the rarest
     * five), a space or the commonest punctuation, nor a byte beyond ASCII, where the lead bytes of a script are common.
     */
    static boolean rare(int b) {
        if (b >= 'A' && b <= 'Z' || b >= '0' && b <= '9') {
            return true;
        }
        if (b > 0x20 && b < 0x7F && !Character.isLetterOrDigit(b)) {
            return ",.'\"-".indexOf(b) < 0;
        }
        return "jqxzk".indexOf(b) >= 0;
    }

    /** The search for the literals, in the pattern's order. */
    static ByteFinder of(byte[][] literals, boolean fingerprint) {
        if (literals.length == 1) {
            byte[] l = literals[0];
            if (l.length == 1) {
                return new One(l[0]);
            }
            int best = -1;
            for (int k = 0; k < l.length; k++) {
                int order = indexOf(VERY_RARE, l[k]);
                if (order >= 0 && (best < 0 || order < indexOf(VERY_RARE, l[best]))) {
                    best = k;
                }
            }
            if (best >= 0) {
                return new Rare(l, best);
            }
            Pair pair = Pair.of(literals);
            if (pair != null) {
                return pair;
            }
            return Table.of(literals);
        }
        // Up to four literals that start with the same rare byte are found by the search for that byte and a compare of
        // each (Sherlock|Street, as the regex crate finds them).
        int first = literals[0].length > 0 ? literals[0][0] & 0xFF : -1;
        boolean oneRareFirst = first >= 0 && rare(first);
        for (byte[] l : literals) {
            oneRareFirst &= l.length > 0 && (l[0] & 0xFF) == first;
        }
        if (!fingerprint && oneRareFirst && literals.length <= 4) {
            return new Shared((byte) first, literals);
        }
        Pair pair = Pair.of(literals);
        if (pair != null) {
            return pair;
        }
        return Table.of(literals);
    }

    private static int indexOf(byte[] a, byte b) {
        for (int k = 0; k < a.length; k++) {
            if (a[k] == b) {
                return k;
            }
        }
        return -1;
    }

    /** A literal of one byte. */
    static final class One extends ByteFinder {
        final byte b;

        One(byte b) {
            this.b = b;
        }

        @Override
        long find(byte[] h, int from, int end) {
            if (from > end) {
                return -1;
            }
            int p = Swar.indexOf(h, from, end, b);
            return p < 0 ? -1 : span(p, p + 1);
        }
    }

    /**
     * One literal with a byte rare enough in text to be searched for alone: the search for it at its offset, then the
     * literal compared, as V8 searches a literal pattern for its first character.
     */
    static final class Rare extends ByteFinder {
        final byte[] literal;
        final int k;

        Rare(byte[] literal, int k) {
            this.literal = literal;
            this.k = k;
        }

        @Override
        long find(byte[] h, int from, int end) {
            int at = from + k;
            byte b = literal[k];
            while (at < end) {
                int p = Swar.indexOf(h, at, end, b);
                if (p < 0) {
                    return -1;
                }
                int start = p - k;
                if (Swar.startsWith(h, start, end, literal)) {
                    return span(start, start + literal.length);
                }
                at = p + 1;
            }
            return -1;
        }
    }

    /** A few literals that all start with one rare byte: the search for the byte, then each literal compared in order. */
    static final class Shared extends ByteFinder {
        final byte first;
        final byte[][] literals;

        Shared(byte first, byte[][] literals) {
            this.first = first;
            this.literals = literals;
        }

        @Override
        long find(byte[] h, int from, int end) {
            int at = from;
            while (at < end) {
                int p = Swar.indexOf(h, at, end, first);
                if (p < 0) {
                    return -1;
                }
                for (byte[] l : literals) {
                    if (Swar.startsWith(h, p, end, l)) {
                        return span(p, p + l.length);
                    }
                }
                at = p + 1;
            }
            return -1;
        }
    }

    /** The values a position of the literals holds, sorted. */
    static int[] values(byte[][] literals, int k) {
        boolean[] seen = new boolean[256];
        int n = 0;
        for (byte[] l : literals) {
            if (!seen[l[k] & 0xFF]) {
                seen[l[k] & 0xFF] = true;
                n++;
            }
        }
        int[] out = new int[n];
        int i = 0;
        for (int b = 0; b < 256; b++) {
            if (seen[b]) {
                out[i++] = b;
            }
        }
        return out;
    }

    /**
     * The tests of a position's values as pairs of a mask and a value, a byte {@code x} passing one where {@code (x |
     * mask) == value}: two values that differ in one bit (the cases of an ASCII letter, the lead bytes of the cases of a
     * Cyrillic letter) make one test with that bit set, any other value a test of its own. {@code null} where that makes
     * more than {@code most}.
     */
    static int[] tests(int[] values, int most) {
        boolean[] used = new boolean[values.length];
        int[] out = new int[2 * most];
        int n = 0;
        for (int a = 0; a < values.length; a++) {
            if (used[a]) {
                continue;
            }
            used[a] = true;
            int mask = 0;
            for (int b = a + 1; b < values.length; b++) {
                int bit = values[a] ^ values[b];
                if (!used[b] && Integer.bitCount(bit) == 1) {
                    used[b] = true;
                    mask = bit;
                    break;
                }
            }
            if (n == most) {
                return null;
            }
            out[2 * n] = mask;
            out[2 * n + 1] = values[a] | mask;
            n++;
        }
        return Arrays.copyOf(out, 2 * n);
    }

    /**
     * The search for a few literals by two of their byte positions, each of whose values are a few tests ({@link
     * #tests}): PCRE2's search before a match ({@code fast_forward_char_pair_simd} in its JIT compiler), which finds
     * {@code Sher[a-z]+|Hol[a-z]+} by an {@code S} or an {@code H} with an {@code e} or an {@code l} two bytes on, and a
     * case-insensitive {@code Holmes} by both cases of two of its letters. Eight starts are tested at a time; each
     * candidate is compared with the literals out of that loop, so that the loop keeps everything in registers.
     */
    static final class Pair extends ByteFinder {
        /** The most tests of a position. */
        static final int MOST_TESTS = 4;
        /** The most bytes of the literals looked at before they are compared one by one. */
        static final int CHECKED = 16;

        final byte[][] literals;
        /** The fewest bytes of a literal. */
        final int shortest;
        /** The two positions, and the masks and values of each one's tests, broadcast. */
        final int i;
        final int j;
        final long[] mi;
        final long[] vi;
        final long[] mj;
        final long[] vj;
        /** For each of the first bytes of the literals, up to {@link #CHECKED}, the values they hold there. */
        final boolean[][] sets;
        /**
         * Whether the literals are every sequence of the values {@code sets} holds, all of one length (a word's cases
         * written out): a candidate whose bytes pass {@code sets} is then a literal, with nothing more to compare.
         */
        final boolean every;

        private Pair(byte[][] literals, int shortest, int i, int[] ti, int j, int[] tj, boolean[][] sets,
                boolean every) {
            this.literals = literals;
            this.shortest = shortest;
            this.i = i;
            this.j = j;
            this.mi = broadcastEach(ti, 0);
            this.vi = broadcastEach(ti, 1);
            this.mj = broadcastEach(tj, 0);
            this.vj = broadcastEach(tj, 1);
            this.sets = sets;
            this.every = every;
        }

        private static long[] broadcastEach(int[] tests, int which) {
            long[] out = new long[tests.length / 2];
            for (int k = 0; k < out.length; k++) {
                out[k] = Swar.broadcast(tests[2 * k + which]);
            }
            return out;
        }

        /**
         * The search for the literals (of at least two bytes each, in the pattern's order), or {@code null} where no two
         * of their positions take {@link #MOST_TESTS} tests or fewer each.
         */
        static Pair of(byte[][] literals) {
            int shortest = Integer.MAX_VALUE;
            for (byte[] l : literals) {
                shortest = Math.min(shortest, l.length);
            }
            if (shortest < 2) {
                return null;
            }
            // Each position of few enough tests, with how common its commonest value is.
            int[][] tests = new int[shortest][];
            int[][] values = new int[shortest][];
            int[] rank = new int[shortest];
            for (int k = 0; k < shortest; k++) {
                values[k] = values(literals, k);
                tests[k] = tests(values[k], MOST_TESTS);
                for (int v : values[k]) {
                    rank[k] = Math.max(rank[k], RANK[v]);
                }
            }
            // The pair of the rarest positions, their values apart where any pair has them apart (a run of one byte
            // would pass both otherwise), as PCRE2 chooses; of those as rare, the one of fewer tests.
            int bi = -1;
            int bj = -1;
            long best = Long.MAX_VALUE;
            for (boolean apart : new boolean[] {true, false}) {
                for (int x = 0; x < shortest; x++) {
                    for (int y = x + 1; y < shortest; y++) {
                        if (tests[x] == null || tests[y] == null || apart && overlap(values[x], values[y])) {
                            continue;
                        }
                        long cost = (long) (rank[x] + rank[y]) << 8 | (tests[x].length + tests[y].length);
                        if (cost < best) {
                            best = cost;
                            bi = x;
                            bj = y;
                        }
                    }
                }
                if (bi >= 0) {
                    break;
                }
            }
            if (bi < 0) {
                return null;
            }
            int checked = Math.min(shortest, CHECKED);
            boolean[][] sets = new boolean[checked][256];
            for (int k = 0; k < checked; k++) {
                for (byte[] l : literals) {
                    sets[k][l[k] & 0xFF] = true;
                }
            }
            boolean every = shortest <= CHECKED;
            long product = 1;
            for (byte[] l : literals) {
                every &= l.length == shortest;
            }
            for (int k = 0; k < shortest && every; k++) {
                product *= values[k].length;
                every = product <= literals.length;
            }
            every &= product == literals.length;
            return new Pair(literals, shortest, bi, tests[bi], bj, tests[bj], sets, every);
        }

        private static boolean overlap(int[] a, int[] b) {
            for (int x : a) {
                for (int y : b) {
                    if (x == y) {
                        return true;
                    }
                }
            }
            return false;
        }

        /** Where the literal that starts at {@code at} ends (the first in the pattern's order), or -1. */
        private int literalAt(byte[] h, int at, int end) {
            int n = Math.min(sets.length, end - at);
            for (int k = 0; k < n; k++) {
                if (!sets[k][h[at + k] & 0xFF]) {
                    return -1;
                }
            }
            if (every) {
                return at + shortest;
            }
            for (byte[] l : literals) {
                if (Swar.startsWith(h, at, end, l)) {
                    return at + l.length;
                }
            }
            return -1;
        }

        /** The lanes of a word that pass the tests. */
        private static long pass(long x, long[] m, long[] v) {
            long out = Swar.eq(x | m[0], v[0]);
            for (int k = 1; k < m.length; k++) {
                out |= Swar.eq(x | m[k], v[k]);
            }
            return out;
        }

        @Override
        long find(byte[] h, int from, int end) {
            int last = end - shortest;
            if (from > last) {
                return -1;
            }
            int at = from;
            // The last start whose two words are within the text.
            int blocks = end - 8 - Math.max(i, j);
            if (mi.length == 1 && mj.length == 1) {
                long mi0 = mi[0];
                long vi0 = vi[0];
                long mj0 = mj[0];
                long vj0 = vj[0];
                while (at <= blocks) {
                    long m = Swar.eq(Swar.word(h, at + i) | mi0, vi0) & Swar.eq(Swar.word(h, at + j) | mj0, vj0);
                    while (m != 0) {
                        int s = at + Swar.lane(m);
                        if (s <= last) {
                            int e = literalAt(h, s, end);
                            if (e >= 0) {
                                return span(s, e);
                            }
                        }
                        m &= m - 1;
                    }
                    at += 8;
                }
            } else {
                while (at <= blocks) {
                    long m = pass(Swar.word(h, at + i), mi, vi) & pass(Swar.word(h, at + j), mj, vj);
                    while (m != 0) {
                        int s = at + Swar.lane(m);
                        if (s <= last) {
                            int e = literalAt(h, s, end);
                            if (e >= 0) {
                                return span(s, e);
                            }
                        }
                        m &= m - 1;
                    }
                    at += 8;
                }
            }
            for (; at <= last; at++) {
                int e = literalAt(h, at, end);
                if (e >= 0) {
                    return span(at, e);
                }
            }
            return -1;
        }
    }

    /**
     * Any set of literals: a table of the bytes the literals hold at their rarest position, read a byte at a time, and
     * the literals that start with the byte found compared in order. The search the crate makes with Teddy and with
     * {@code aho-corasick}'s automaton, where no two positions take few enough tests for {@link Pair}.
     */
    static final class Table extends ByteFinder {
        final int k;
        final boolean[] at;
        final int shortest;
        /** The literals by their first byte, in the pattern's order. */
        final byte[][][] byFirst;

        private Table(int k, boolean[] at, int shortest, byte[][][] byFirst) {
            this.k = k;
            this.at = at;
            this.shortest = shortest;
            this.byFirst = byFirst;
        }

        static Table of(byte[][] literals) {
            int shortest = Integer.MAX_VALUE;
            for (byte[] l : literals) {
                shortest = Math.min(shortest, l.length);
            }
            int bestK = 0;
            long bestCost = Long.MAX_VALUE;
            for (int k = 0; k < shortest; k++) {
                long cost = 0;
                for (int v : values(literals, k)) {
                    cost += 1L << (RANK[v] / 16);
                }
                if (cost < bestCost) {
                    bestCost = cost;
                    bestK = k;
                }
            }
            boolean[] at = new boolean[256];
            List<List<byte[]>> lists = new ArrayList<>();
            for (int b = 0; b < 256; b++) {
                lists.add(new ArrayList<>());
            }
            for (byte[] l : literals) {
                at[l[bestK] & 0xFF] = true;
                lists.get(l[0] & 0xFF).add(l);
            }
            byte[][][] byFirst = new byte[256][][];
            for (int b = 0; b < 256; b++) {
                byFirst[b] = lists.get(b).toArray(new byte[0][]);
            }
            return new Table(bestK, at, shortest, byFirst);
        }

        @Override
        long find(byte[] h, int from, int end) {
            int last = end - shortest;
            boolean[] at = this.at;
            int k = this.k;
            for (int s = from; s <= last; s++) {
                if (at[h[s + k] & 0xFF]) {
                    for (byte[] l : byFirst[h[s] & 0xFF]) {
                        if (Swar.startsWith(h, s, end, l)) {
                            return span(s, s + l.length);
                        }
                    }
                }
            }
            return -1;
        }
    }
}
