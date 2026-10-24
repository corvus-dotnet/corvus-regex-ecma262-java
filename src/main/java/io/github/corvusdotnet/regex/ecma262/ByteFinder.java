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

    /**
     * The rank under which a byte is searched for alone (the capital letters, the digits and the rarest lower case
     * letters): over SWAR the search for one byte reads three times as fast as the pair search, so a byte this rare
     * pays for the compares where it is found.
     */
    static final int ALONE = 200;

    /**
     * Each ASCII byte's share of English text in parts per ten thousand: the small letters by their frequency (the table
     * of the .NET reference engine's needle, {@code Needle.cs}), the capitals as measured over the Industry texts (the
     * greater of their shares of the Sherlock Holmes text and the Leipzig corpus: I 65, T 28, H 21, S 16), the space and
     * the commonest punctuation, and ten for any other byte. It knows what {@link #RANK} does not, that a capital is
     * rarer than {@code k} in English text ({@code Sherlock}'s {@code k}, which RANK ranks rarest, is found 3,595 times
     * in the Sherlock Holmes text, its {@code S} 837), and that the capitals are not alike: {@code Twain}'s T, found
     * alone, left the vector loop often enough over the Leipzig corpus to lose to the pair (1,421 us against 1,261).
     */
    private static final int[] SHARE = share();

    private static int[] share() {
        int[] r = new int[128];
        java.util.Arrays.fill(r, 10);
        String letters = "etaoinshrdlcumwfgypbvkjxqz";
        int[] perMille = {102, 73, 66, 60, 56, 54, 50, 49, 48, 34, 32, 22, 22, 19, 19, 18, 16, 16, 15, 12, 8, 6, 2, 2, 1,
            1};
        for (int k = 0; k < letters.length(); k++) {
            r[letters.charAt(k)] = perMille[k] * 10;
        }
        String capitals = "ITHASWMECBNOYRLDPGFJUVKXQZ";
        int[] perTenThousand = {65, 28, 21, 18, 16, 13, 13, 12, 10, 10, 9, 9, 8, 8, 7, 6, 6, 5, 5, 4, 2, 2, 2, 1, 1, 1};
        for (int k = 0; k < capitals.length(); k++) {
            r[capitals.charAt(k)] = perTenThousand[k];
        }
        r[' '] = 1700;
        r['\n'] = 200;
        r['\r'] = 200;
        r['.'] = 70;
        r[','] = 80;
        return r;
    }

    /**
     * The most a byte's share of text may be, in parts per ten thousand, for it to be searched for alone with vectors:
     * S, H and A, not T or I.
     */
    private static final int VECTOR_ALONE = 25;

    /**
     * The same for a literal of three bytes or fewer: T too. Such a literal is a word as common as its capital is
     * (nearly every T of the Sherlock Holmes text begins {@code The}), so no second position turns starts away, and the
     * pair's scan only costs more: {@code The} 19.2 us alone against 25.3 as a pair, where {@code Twain}, whose T is
     * seldom followed by w, took 791 us as a pair against 1,266 alone.
     */
    private static final int VECTOR_ALONE_SHORT = 30;

    /**
     * A UTF-16 code unit's share of text in parts per ten thousand, for the choice of the unit {@link String#indexOf(int,
     * int)} searches for: an ASCII unit as {@link #SHARE} has it; a capital of another alphabet (Latin, Greek, Cyrillic,
     * Armenian) as rare as an ASCII capital and its small letters as common as the commonest ASCII ones, since a text in
     * that script is mostly them; a CJK ideograph or kana as rare (one of thousands); anything else rare.
     */
    static int share16(char u) {
        if (u < 0x80) {
            return SHARE[u];
        }
        if (u < 0x250 || u >= 0x370 && u < 0x590) {
            return Character.isUpperCase(u) ? 20 : Character.isLetter(u) ? 500 : 10;
        }
        return 10;
    }

    /** The most share of text the byte a literal is searched for by alone may have, with vectors. */
    private static int vectorAlone(byte[] literal) {
        return literal.length <= 3 ? VECTOR_ALONE_SHORT : VECTOR_ALONE;
    }

    /** The search for the literals over UTF-8, in the pattern's order. */
    static ByteFinder of(byte[][] literals, boolean fingerprint) {
        return of(literals, fingerprint, RANK);
    }

    /**
     * Whether a byte is rare enough to be searched for alone: one {@link #rare} names (the Rust crate's rule), and one
     * the ranks do not mark common (a byte a UTF-16 literal's unit beyond ASCII packs to, which {@link #packedRanks}
     * marks).
     */
    private static boolean alone(int[] rank, int b) {
        return b < 0x80 && rare(b) && rank[b] <= ALONE;
    }

    /**
     * The search for the literals, in the pattern's order, with how common each byte is ({@link #RANK} over UTF-8, and
     * the ranks of {@link #packedRanks} for the low bytes of UTF-16 units).
     */
    static ByteFinder of(byte[][] literals, boolean fingerprint, int[] rank) {
        if (literals.length == 1) {
            byte[] l = literals[0];
            if (l.length == 1) {
                return new One(l[0]);
            }
            // The rarest byte, searched for alone where it is rare enough; the pair search where every byte is common.
            int best = Kernels.vector() ? rarestByShare(l, rank) : rarestByRank(l, rank);
            // With SWAR one byte reads three times as fast as two, so any rare byte is searched for alone. With the
            // Vector API a pair of positions costs little more than one, so a byte is only where it is as rare as a
            // capital: Sherlock by its k left the vector loop 3,595 times for 97 matches.
            int b = l[best] & 0xFF;
            boolean alone = Kernels.vector() ? b < 0x80 && rank[b] <= ALONE && SHARE[b] <= vectorAlone(l) : alone(rank, b);
            if (alone) {
                return new Rare(l, best);
            }
            Pair pair = Pair.of(literals, rank);
            if (pair != null) {
                return pair;
            }
            return Table.of(literals);
        }
        Firsts firsts = Firsts.of(literals, rank);
        // With the Vector API, the first two bytes where the first bytes are too many to test exactly: a search of
        // their range passes every capital between (the seven names' A to W, 8,230 starts of the Sherlock Holmes text,
        // 188 us against 143). Where a few first bytes are tested exactly, the sixteen compares of the two bytes cost
        // more than the starts they turn away (Sherlock|Holmes|Watson 38 us against 140, Tom|Sawyer|Huckleberry|Finn
        // 2,238 against 3,624, the Russian names over UTF-8 537 against 1,968).
        // Over UTF-8 only ASCII first bytes: a character beyond ASCII is two bytes or more, so its first two bytes are
        // that character, as common as its script's letters are (the Russian names, among them a small letter, 537 us
        // against 2,003; the Chinese 118 against 290). The packed search over UTF-16 takes any (the Chinese 330 against
        // 162).
        // Not where the pair search tests each of its positions once or twice: the cases of a word written out (Holmes
        // under the i flag) share few first two bytes, but a letter's two cases are one masked test, which costs far
        // less than sixteen compares (70 us against 203); over UTF-16 the long s packs to a byte of its own, a second
        // test (Sherlock under the i flag, 65 us against 226).
        if (Kernels.vector() && (firsts == null || firsts.lo >= 0) && (rank != RANK || asciiPrefixes(literals))) {
            Pair pair = Pair.of(literals, rank);
            Prefixes prefixes = pair != null && pair.mi.length <= 2 && pair.mj.length <= 2 ? null : Prefixes.of(literals);
            if (prefixes != null) {
                return prefixes;
            }
        }
        if (firsts != null) {
            return firsts;
        }
        Pair pair = Pair.of(literals, rank);
        if (pair != null) {
            return pair;
        }
        return Table.of(literals, rank);
    }

    /** Whether every literal's first two bytes are ASCII. */
    private static boolean asciiPrefixes(byte[][] literals) {
        for (byte[] l : literals) {
            if (l.length < 2 || l[0] < 0 || l[1] < 0) {
                return false;
            }
        }
        return true;
    }

    /** The position of a literal's rarest byte by {@link #RANK} (or the packed ranks). */
    private static int rarestByRank(byte[] l, int[] rank) {
        int best = 0;
        for (int k = 1; k < l.length; k++) {
            if (rank[l[k] & 0xFF] < rank[l[best] & 0xFF]) {
                best = k;
            }
        }
        return best;
    }

    /**
     * The position of a literal's rarest ASCII byte by {@link #SHARE}, of those whose rank is {@link #RANK}'s own (not
     * a byte a UTF-16 literal's unit beyond ASCII packs to, which {@link #packedRanks} marks), or by the ranks where it
     * has none.
     */
    private static int rarestByShare(byte[] l, int[] rank) {
        int best = -1;
        for (int k = 0; k < l.length; k++) {
            int b = l[k] & 0xFF;
            if (b < 0x80 && rank[b] == RANK[b] && (best < 0 || SHARE[b] < SHARE[l[best] & 0xFF])) {
                best = k;
            }
        }
        return best >= 0 ? best : rarestByRank(l, rank);
    }

    /**
     * How common each low byte of the units of UTF-16 literals is in the packed text: an ASCII unit's byte as {@link
     * #RANK} has it, and a byte any of the literals' units beyond ASCII packs to as common (a Cyrillic letter in Russian
     * text) or nearly so (a Han character, whose low byte any of thousands share), so that neither is searched for
     * alone or taken for a rare position.
     */
    static int[] packedRanks(String[] literals) {
        int[] rank = RANK.clone();
        for (String l : literals) {
            for (int i = 0; i < l.length(); i++) {
                char u = l.charAt(i);
                if (u >= 0x80) {
                    int b = u & 0xFF;
                    rank[b] = Math.max(rank[b], u >= 0x400 && u <= 0x4FF ? 245 : 230);
                }
            }
        }
        return rank;
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
            int p = Kernels.indexOf(h, from, end, b);
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
                int p = Kernels.indexOf(h, at, end, b);
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

    /**
     * Several literals whose bytes at one position are up to four values, or more within a range, each byte rare
     * enough to be searched for alone ({@link #alone}): the search for those bytes (or that range) at that position,
     * then the literals with the byte found there compared in order. {@code Sherlock|Holmes} is found by an {@code S} or an {@code H}, as the regex crate finds
     * {@code Sherlock|Street} by its {@code S}; the position is the one whose values are rarest.
     */
    static final class Firsts extends ByteFinder {
        final int k;
        final int count;
        final int a;
        final int b;
        /** The literals by their byte at {@code k}, in the pattern's order. */
        final byte[][][] byValue;

        /** The range of bytes searched for in place of the values, where there are more than two of them. */
        final int lo;
        final int hi;

        final int c;
        final int d;

        private Firsts(int k, int[] values, byte[][][] byValue, int lo, int hi) {
            this.k = k;
            this.lo = lo;
            this.hi = hi;
            this.count = lo >= 0 ? -1 : values.length;
            this.a = values[0];
            this.b = values[Math.min(1, values.length - 1)];
            this.c = values[Math.min(2, values.length - 1)];
            this.d = values[Math.min(3, values.length - 1)];
            this.byValue = byValue;
        }

        static Firsts of(byte[][] literals, int[] rank) {
            int shortest = Integer.MAX_VALUE;
            for (byte[] l : literals) {
                shortest = Math.min(shortest, l.length);
            }
            int bestK = -1;
            boolean bestRange = false;
            long bestCost = Long.MAX_VALUE;
            for (int k = 0; k < shortest; k++) {
                int[] v = values(literals, k);
                // Up to four values are tested for exactly; more as the range from the least to the greatest, where
                // every byte in it is rare (the capital letters of a list of names), which is one test where the values
                // would be as many. Measured, three or four values found by a range (F to T for Tom|Sawyer|Huckleberry|
                // Finn) took 1.2 times as long as the exact tests, which pass fewer capitals.
                boolean range = v.length > 4;
                int from = range ? v[0] : 0;
                int to = range ? v[v.length - 1] : -1;
                long cost = 0;
                boolean rare = true;
                if (range) {
                    for (int x = from; x <= to; x++) {
                        rare &= alone(rank, x);
                        cost += 1L << (rank[x] / 16);
                    }
                } else {
                    for (int x : v) {
                        rare &= alone(rank, x);
                        cost += 1L << (rank[x] / 16);
                    }
                }
                // Exact values before a range, then the rarer.
                cost += (range ? 2L : 1L) << 20;
                if (rare && cost < bestCost) {
                    bestCost = cost;
                    bestK = k;
                    bestRange = range;
                }
            }
            if (bestK < 0) {
                return null;
            }
            int[] values = values(literals, bestK);
            byte[][][] byValue = new byte[256][][];
            for (int x : values) {
                List<byte[]> with = new ArrayList<>();
                for (byte[] l : literals) {
                    if ((l[bestK] & 0xFF) == x) {
                        with.add(l);
                    }
                }
                byValue[x] = with.toArray(new byte[0][]);
            }
            return bestRange ? new Firsts(bestK, values, byValue, values[0], values[values.length - 1])
                    : new Firsts(bestK, values, byValue, -1, -1);
        }

        @Override
        long find(byte[] h, int from, int end) {
            int at = from + k;
            while (at < end) {
                int p = count == 1 ? Kernels.indexOf(h, at, end, a)
                        : count == 2 ? Kernels.indexOf(h, at, end, a, b)
                        : count > 0 ? Kernels.indexOf(h, at, end, a, b, c, d)
                        : Kernels.indexOfBetween(h, at, end, lo, hi);
                if (p < 0) {
                    return -1;
                }
                int start = p - k;
                byte[][] with = byValue[h[p] & 0xFF];
                if (with == null) {
                    // A byte of the range no literal holds there.
                    at = p + 1;
                    continue;
                }
                for (byte[] l : with) {
                    if (Swar.startsWith(h, start, end, l)) {
                        return span(start, start + l.length);
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
     * Three literals or more, of two bytes or more, with at most eight first two bytes between them, found with the
     * Vector API by those two bytes taken as one sixteen-bit unit, compared with each literal's at every start (the .NET
     * reference engine's search of pairs of bytes): only the literals' own pairs pass, where a search of their first
     * bytes passes any start with one of those. Over the Sherlock Holmes text the seven names' capitals are 8,230
     * starts, their first two bytes 1,075 for 740 matches.
     */
    static final class Prefixes extends ByteFinder {
        /** The most distinct first two bytes. */
        static final int MOST = 8;

        final byte[][] literals;
        final int shortest;
        /** The first two bytes of the literals as units (the first byte low), eight of them, the last repeated. */
        final short[] values;

        private Prefixes(byte[][] literals, int shortest, short[] values) {
            this.literals = literals;
            this.shortest = shortest;
            this.values = values;
        }

        static Prefixes of(byte[][] literals) {
            if (literals.length < 3) {
                return null;
            }
            int shortest = Integer.MAX_VALUE;
            short[] distinct = new short[MOST];
            int count = 0;
            for (byte[] l : literals) {
                if (l.length < 2) {
                    return null;
                }
                shortest = Math.min(shortest, l.length);
                short v = (short) ((l[0] & 0xFF) | (l[1] & 0xFF) << 8);
                boolean seen = false;
                for (int k = 0; k < count; k++) {
                    seen |= distinct[k] == v;
                }
                if (!seen) {
                    if (count == MOST) {
                        return null;
                    }
                    distinct[count++] = v;
                }
            }
            for (int k = count; k < MOST; k++) {
                distinct[k] = distinct[count - 1];
            }
            return new Prefixes(literals, shortest, distinct);
        }

        @Override
        long find(byte[] h, int from, int end) {
            return Kernels.VECTOR != null ? Kernels.VECTOR.prefixesFind(this, h, from, end) : findFrom(h, from, end);
        }

        /** Where the first literal in order that starts at {@code at} ends, or -1. */
        int literalAt(byte[] h, int at, int end) {
            for (byte[] l : literals) {
                if (h[at] == l[0] && end - at >= l.length && Swar.startsWith(h, at, end, l)) {
                    return at + l.length;
                }
            }
            return -1;
        }

        /** The leftmost occurrence from {@code from}, a start at a time: the part of a text shorter than a vector. */
        long findFrom(byte[] h, int from, int end) {
            for (int at = from; at <= end - shortest; at++) {
                int e = literalAt(h, at, end);
                if (e >= 0) {
                    return span(at, e);
                }
            }
            return -1;
        }
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
        /**
         * The same tests as bytes for the Vector API's loop, four of each position as mask and value (a position of
         * fewer repeats its last test).
         */
        final byte[] testsI;
        final byte[] testsJ;
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
            this.testsI = fourTests(ti);
            this.testsJ = fourTests(tj);
            this.sets = sets;
            this.every = every;
        }

        private static byte[] fourTests(int[] tests) {
            byte[] out = new byte[8];
            for (int k = 0; k < 4; k++) {
                int t = Math.min(k, tests.length / 2 - 1);
                out[2 * k] = (byte) tests[2 * t];
                out[2 * k + 1] = (byte) tests[2 * t + 1];
            }
            return out;
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
            return of(literals, RANK);
        }

        static Pair of(byte[][] literals, int[] ranks) {
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
                    rank[k] = Math.max(rank[k], ranks[v]);
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
        int literalAt(byte[] h, int at, int end) {
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

        /**
         * The lanes of a word that pass the tests, by the cheap test of {@link Swar#hasZero}, which may flag a lane that
         * does not pass but never misses one: every candidate is compared with the literals.
         */
        private static long pass(long x, long[] m, long[] v) {
            long out = Swar.hasZero((x | m[0]) ^ v[0]);
            for (int k = 1; k < m.length; k++) {
                out |= Swar.hasZero((x | m[k]) ^ v[k]);
            }
            return out;
        }

        @Override
        long find(byte[] h, int from, int end) {
            ByteKernels v = Kernels.VECTOR;
            return v != null ? v.pairFind(this, h, from, end) : findFrom(h, from, end);
        }

        /** {@link #find} by SWAR: eight starts a step, then one at a time. */
        long findFrom(byte[] h, int from, int end) {
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
                    long m = Swar.hasZero((Swar.word(h, at + i) | mi0) ^ vi0)
                            & Swar.hasZero((Swar.word(h, at + j) | mj0) ^ vj0);
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
            return of(literals, RANK);
        }

        static Table of(byte[][] literals, int[] rank) {
            int shortest = Integer.MAX_VALUE;
            for (byte[] l : literals) {
                shortest = Math.min(shortest, l.length);
            }
            int bestK = 0;
            long bestCost = Long.MAX_VALUE;
            for (int k = 0; k < shortest; k++) {
                long cost = 0;
                for (int v : values(literals, k)) {
                    cost += 1L << (rank[v] / 16);
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
