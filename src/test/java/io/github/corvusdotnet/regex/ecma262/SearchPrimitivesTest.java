package io.github.corvusdotnet.regex.ecma262;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The searches the Rust crate takes from {@code memchr} and {@code aho-corasick}, written here on {@link Swar}: each
 * finds what comparing every literal at every start finds (the Rust crate's {@code the_pair_search_finds_the_leftmost_
 * literal}, and the same for the others).
 */
class SearchPrimitivesTest {
    static final class Xorshift {
        int s;

        Xorshift(int seed) {
            s = seed;
        }

        int next() {
            s ^= s << 13;
            s ^= s >>> 17;
            s ^= s << 5;
            return s;
        }

        int below(int n) {
            return Integer.remainderUnsigned(next(), n);
        }
    }

    /** The leftmost occurrence at or after {@code from} (the first literal in order of those that start there). */
    static long naive(byte[] h, int from, int end, byte[][] literals) {
        for (int i = from; i < end; i++) {
            for (byte[] l : literals) {
                if (Swar.startsWith(h, i, end, l)) {
                    return ByteFinder.span(i, i + l.length);
                }
            }
        }
        return -1;
    }

    static byte[][] utf8(String... literals) {
        byte[][] out = new byte[literals.length][];
        for (int k = 0; k < literals.length; k++) {
            out[k] = literals[k].getBytes(StandardCharsets.UTF_8);
        }
        return out;
    }

    @Test
    void theByteSearchesFindTheFirstByte() {
        Xorshift seed = new Xorshift(0x1b873593);
        for (int round = 0; round < 300; round++) {
            byte[] h = new byte[seed.below(100)];
            for (int k = 0; k < h.length; k++) {
                h[k] = (byte) "abc\u0080ÿ\u0000".charAt(seed.below(6));
            }
            int[] want = {'a', 'b', 'c', 0x80, 0xFF, 0};
            for (int from = 0; from <= h.length; from++) {
                for (int end = from; end <= h.length; end++) {
                    int a = want[seed.below(6)];
                    int b = want[seed.below(6)];
                    int c = want[seed.below(6)];
                    assertEquals(first(h, from, end, a, a, a), Swar.indexOf(h, from, end, a));
                    assertEquals(first(h, from, end, a, b, b), Swar.indexOf(h, from, end, a, b));
                    assertEquals(first(h, from, end, a, b, c), Swar.indexOf(h, from, end, a, b, c));
                }
            }
        }
    }

    @Test
    void theRangeSearchFindsTheFirstByteInTheRange() {
        Xorshift seed = new Xorshift(0x3c6ef372);
        int[][] ranges = {{'A', 'Z'}, {'F', 'T'}, {'0', '9'}, {0, 0x7F}, {'a', 'a'}};
        for (int round = 0; round < 300; round++) {
            byte[] h = new byte[seed.below(100)];
            for (int k = 0; k < h.length; k++) {
                h[k] = (byte) seed.below(256);
            }
            for (int[] r : ranges) {
                for (int from = 0; from <= h.length; from++) {
                    int want = -1;
                    for (int k = from; k < h.length && want < 0; k++) {
                        if (h[k] >= r[0] && h[k] <= r[1]) {
                            want = k;
                        }
                    }
                    assertEquals(want, Swar.indexOfBetween(h, from, h.length, r[0], r[1]), r[0] + ".." + r[1]);
                }
            }
        }
    }

    static int first(byte[] h, int from, int end, int a, int b, int c) {
        for (int k = from; k < end; k++) {
            int x = h[k] & 0xFF;
            if (x == a || x == b || x == c) {
                return k;
            }
        }
        return -1;
    }

    static final String[][] SETS = {
        {"Sher", "Hol"}, {"Sherlock", "Holmes"}, {"the", "thE", "tHe", "tHE", "The", "ThE", "THe", "THE"},
        {"ab", "abc", "ba"}, {"шер", "Шер", "шЕр", "ШЕР"},
        {"aa", "ab"}, {"xyz", "xéz"}, {"Sherlock"}, {"zqj"}, {"jj j"}, {"S", "H"}, {"Sherlock", "Street"},
        {"Tom", "river", "Tomas"}, {"awyer", "inn"}, {"a"},
        {"Sherlock", "Holmes", "Watson", "Irene", "Adler", "John", "Baker", "sherlock", "holmes", "watson", "irene"},
    };

    static final String[] ALPHABET = {"a", "b", "c", "e", "h", "r", "t", "H", "S", "T", "E", " ", "ш", "Ш",
        "е", "Е", "р", "é", "x", "y", "z", "o", "l", "m", "s", "j", "q", "k", "w", "i", "n"};

    /** A text of the alphabet with the set's literals in it, from a seed. */
    static String text(Xorshift seed, String[] set, int n) {
        StringBuilder t = new StringBuilder();
        while (t.length() < n) {
            if (seed.below(9) == 0) {
                t.append(set[seed.below(set.length)]);
            } else {
                t.append(ALPHABET[seed.below(ALPHABET.length)]);
            }
        }
        return t.toString();
    }

    @Test
    void theFindersFindTheLeftmostLiteral() {
        Xorshift seed = new Xorshift(0x51ed270b);
        int pairs = 0;
        for (String[] set : SETS) {
            byte[][] literals = utf8(set);
            List<ByteFinder> finders = new ArrayList<>();
            finders.add(ByteFinder.of(literals, false));
            finders.add(ByteFinder.of(literals, true));
            finders.add(ByteFinder.Table.of(literals));
            ByteFinder.Pair pair = ByteFinder.Pair.of(literals);
            if (pair != null) {
                finders.add(pair);
                pairs++;
            }
            for (int round = 0; round < 100; round++) {
                byte[] h = text(seed, set, seed.below(300)).getBytes(StandardCharsets.UTF_8);
                for (int from = 0; from <= h.length + 1; from++) {
                    int end = seed.below(4) == 0 ? seed.below(h.length + 1) : h.length;
                    long want = from > end ? -1 : naive(h, from, end, literals);
                    for (ByteFinder f : finders) {
                        assertEquals(want, f.find(h, from, end), f.getClass().getSimpleName() + " of "
                                + String.join("|", set) + " in " + new String(h, StandardCharsets.UTF_8) + " from "
                                + from + " to " + end);
                    }
                }
            }
        }
        assertTrue(pairs >= 8, pairs + " pair searches");
        // A literal of one byte has no pair of positions; the cases of a word written out are every sequence.
        assertNull(ByteFinder.Pair.of(utf8("S", "H")));
        assertTrue(ByteFinder.Pair.of(utf8("the", "thE", "tHe", "tHE", "The", "ThE", "THe", "THE")).every);
        assertNotNull(ByteFinder.Pair.of(utf8("Sherlock")));
    }

    @Test
    void thePackedSearchFindsTheLeftmostLiteral() {
        Xorshift seed = new Xorshift(0x2f6b1c3d);
        String[][] sets = {
            {"Sherlock", "Holmes"}, {"the", "thE", "tHe", "tHE", "The", "ThE", "THe", "THE"}, {"sk", "ſk", "sK"},
            {"шер", "Шер"}, {"ab", "abc", "ba"}, {"š", "a"},
        };
        for (String[] set : sets) {
            byte[][] low = new byte[set.length][];
            int longest = 0;
            for (int k = 0; k < set.length; k++) {
                low[k] = new byte[set[k].length()];
                for (int i = 0; i < set[k].length(); i++) {
                    low[k][i] = (byte) set[k].charAt(i);
                }
                longest = Math.max(longest, set[k].length());
            }
            ByteFinder finder = ByteFinder.of(low, true);
            for (int round = 0; round < 40; round++) {
                // Texts longer than a chunk, so that occurrences fall across the chunks' ends.
                int n = seed.below(3) == 0 ? seed.below(3 * UnitSearch.PACK) : seed.below(300);
                String s = text(seed, set, n) + "šſK";
                for (int from = 0; from <= s.length() + 1; from += 1 + seed.below(40)) {
                    long want = -1;
                    for (int i = from; i < s.length() && want < 0; i++) {
                        for (String l : set) {
                            if (s.startsWith(l, i)) {
                                want = ByteFinder.span(i, i + l.length());
                                break;
                            }
                        }
                    }
                    assertEquals(want, UnitSearch.findPacked(s, from, finder, set, longest),
                            String.join("|", set) + " from " + from);
                }
            }
        }
    }

    @Test
    void aUnitSetFindsItsFirstUnit() {
        Xorshift seed = new Xorshift(0x7a3d11);
        int[][] sets = {{'a', 'a'}, {'a', 'z'}, {'0', '9', 'A', 'F'}, {0xD800, 0xDBFF}, {'\n', '\n', '\r', '\r', 0x2028,
            0x2029}};
        String alphabet = "az09AF\n\r  😀é ";
        for (int[] r : sets) {
            UnitSearch.UnitSet set = new UnitSearch.UnitSet(r);
            for (int round = 0; round < 100; round++) {
                StringBuilder t = new StringBuilder();
                for (int k = seed.below(100); k > 0; k--) {
                    t.append(alphabet.charAt(seed.below(alphabet.length())));
                }
                String s = t.toString();
                for (int from = 0; from <= s.length(); from++) {
                    int want = -1;
                    for (int k = from; k < s.length() && want < 0; k++) {
                        for (int i = 0; i < r.length; i += 2) {
                            if (s.charAt(k) >= r[i] && s.charAt(k) <= r[i + 1]) {
                                want = k;
                            }
                        }
                    }
                    assertEquals(want, set.find(s, from));
                }
            }
        }
    }
}
