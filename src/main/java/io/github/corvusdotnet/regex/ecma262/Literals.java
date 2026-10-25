package io.github.corvusdotnet.regex.ecma262;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The literals of a search, over UTF-8 and over UTF-16 ({@code Literals} of {@code search.rs}): a search for the
 * leftmost occurrence of any of them, the first in the pattern's order of those that start there. Its answers are
 * packed as {@code start << 32 | end}, or -1 for none.
 */
final class Literals {
    /** The search over UTF-8. */
    final ByteFinder utf8;
    /** The one literal over UTF-8, when there is one; {@code null} otherwise. */
    final byte[] single8;
    /** The bytes the UTF-8 literals start with. */
    final boolean[] firsts = new boolean[256];
    /** The most code units of a literal. */
    final int longest;
    /** Every literal over UTF-16, in the pattern's order. */
    final String[] all16;
    /**
     * One literal over UTF-16 with a rare unit: {@link String#indexOf(int, int)} for that unit, at {@link #oneAt} in the
     * literal, then a compare of the literal; {@code null} otherwise.
     */
    final String one16;
    final char oneUnit;
    final int oneAt;
    /**
     * Up to four literals over UTF-16 that start with one rare unit: {@link String#indexOf(int, int)} for it, then a
     * compare of each.
     */
    final char shared16;
    final boolean hasShared16;
    /** The search of the low bytes of several literals over UTF-16 (see {@link UnitSearch#findPacked}). */
    final ByteFinder packed16;

    /** The most units of a literal the packed search takes, so that the chunks' overlap stays short. */
    private static final int PACKED_LONGEST = 64;

    /**
     * The most share of text (in parts per ten thousand, {@link ByteFinder#share16}) a unit may have for the UTF-16
     * search to look for it by {@link String#indexOf(int, int)}: the JDK's own vectorised search, over the string's own
     * storage, against the packed search's copy of each chunk of the text into bytes. Measured with a warm-up as short as
     * the harness's: Twain's T over the Leipzig corpus 1,974 us against 3,408, Sherlock's S 24 against 104, zqj's z 18
     * against 103 (one call a count, with no match), the Chinese literal 9 against 62, the Russian 37 against 204.
     */
    private static final int INDEX_OF_ALONE = 40;

    /**
     * The search for the literals, each a sequence of code points, with the most bytes of each written out over UTF-8
     * (see {@code utf8Cuts} of {@link Search}).
     */
    Literals(List<int[]> literals, int[] cuts, boolean fingerprint) {
        // A literal cut to fewer bytes finds the same starts, and one that an earlier literal begins with finds no start
        // the earlier one does not.
        List<byte[]> utf8 = new ArrayList<>();
        String[] strings = new String[literals.size()];
        int longest = 0;
        for (int k = 0; k < literals.size(); k++) {
            int[] l = literals.get(k);
            String string = new String(l, 0, l.length);
            strings[k] = string;
            longest = Math.max(longest, string.length());
            byte[] b = string.getBytes(StandardCharsets.UTF_8);
            int cut = cuts[k];
            // A cut inside a character is not made: measured in the Rust crate, the Cyrillic letters with a three-byte
            // form cut so made the search 1.2 times slower.
            if (cut < b.length && (b[cut] & 0xC0) != 0x80) {
                b = Arrays.copyOf(b, cut);
            }
            boolean covered = false;
            for (byte[] e : utf8) {
                covered |= b.length >= e.length && Arrays.equals(b, 0, e.length, e, 0, e.length);
            }
            if (!covered) {
                utf8.add(b);
            }
        }
        byte[][] bytes = utf8.toArray(new byte[0][]);
        for (byte[] b : bytes) {
            firsts[b[0] & 0xFF] = true;
        }
        this.utf8 = ByteFinder.of(bytes, fingerprint);
        this.single8 = bytes.length == 1 ? bytes[0] : null;
        this.longest = longest;
        this.all16 = strings;
        int at = -1;
        if (strings.length == 1) {
            String l = strings[0];
            for (int k = 0; k < l.length(); k++) {
                char u = l.charAt(k);
                if (!Character.isSurrogate(u) && (at < 0 || ByteFinder.share16(u) < ByteFinder.share16(l.charAt(at)))) {
                    at = k;
                }
            }
            if (at >= 0 && ByteFinder.share16(l.charAt(at)) > INDEX_OF_ALONE) {
                at = -1;
            }
        }
        this.one16 = at >= 0 ? strings[0] : null;
        this.oneAt = Math.max(at, 0);
        this.oneUnit = at >= 0 ? strings[0].charAt(at) : 0;
        char first = strings[0].charAt(0);
        boolean shared = strings.length > 1 && strings.length <= 4 && !Character.isSurrogate(first)
                && ByteFinder.share16(first) <= INDEX_OF_ALONE;
        for (String s : strings) {
            shared &= s.charAt(0) == first;
        }
        this.hasShared16 = shared;
        this.shared16 = first;
        if (longest < PACKED_LONGEST) {
            // The low byte of each unit, as the packed text holds it.
            List<byte[]> low = new ArrayList<>();
            for (String s : strings) {
                byte[] b = new byte[s.length()];
                for (int i = 0; i < b.length; i++) {
                    b[i] = (byte) s.charAt(i);
                }
                boolean seen = false;
                for (byte[] e : low) {
                    seen |= Arrays.equals(e, b);
                }
                if (!seen) {
                    low.add(b);
                }
            }
            this.packed16 = ByteFinder.of(low.toArray(new byte[0][]), fingerprint, ByteFinder.packedRanks(strings));
        } else {
            this.packed16 = null;
        }
    }

    /** The leftmost occurrence at or after {@code from} in UTF-8 text. */
    long find(byte[] h, int from) {
        return utf8.find(h, from);
    }

    /** The leftmost occurrence at or after {@code from} in UTF-16 text. */
    long find(String s, int from) {
        if (one16 != null) {
            int at = Math.max(from, 0) + oneAt;
            while (true) {
                int p = s.indexOf(oneUnit, at);
                if (p < 0) {
                    return -1;
                }
                int start = p - oneAt;
                if (s.startsWith(one16, start)) {
                    return ByteFinder.span(start, start + one16.length());
                }
                at = p + 1;
            }
        }
        if (hasShared16) {
            int at = Math.max(from, 0);
            while (true) {
                int p = s.indexOf(shared16, at);
                if (p < 0) {
                    return -1;
                }
                for (String l : all16) {
                    if (s.startsWith(l, p)) {
                        return ByteFinder.span(p, p + l.length());
                    }
                }
                at = p + 1;
            }
        }
        if (packed16 != null) {
            return UnitSearch.findPacked(s, from, packed16, all16, longest);
        }
        for (int p = Math.max(from, 0); p < s.length(); p++) {
            long found = literalAt(s, p);
            if (found >= 0) {
                return found;
            }
        }
        return -1;
    }

    /** The first literal in order that starts at {@code p} of UTF-16 text, as a span; -1 for none. */
    private long literalAt(String s, int p) {
        char c = s.charAt(p);
        for (String l : all16) {
            if (l.charAt(0) == c && s.startsWith(l, p)) {
                return ByteFinder.span(p, p + l.length());
            }
        }
        return -1;
    }

    /**
     * The leftmost occurrence that starts at or after {@code from} and at or before {@code last}, in UTF-8 text: the
     * position, or -1. The few bytes between are looked at one at a time for a literal's first byte before a search is
     * called, since a call on so short a text costs more than reading it.
     */
    int findWithin(byte[] h, int from, int last) {
        last = Math.min(last, h.length - 1);
        if (from > last) {
            return -1;
        }
        if (single8 != null) {
            for (int p = from; p <= last; p++) {
                if (h[p] == single8[0] && Swar.startsWith(h, p, h.length, single8)) {
                    return p;
                }
            }
            return -1;
        }
        int p = from;
        while (p <= last && !firsts[h[p] & 0xFF]) {
            p++;
        }
        if (p > last) {
            return -1;
        }
        int reach = (int) Math.min((long) last + 3L * longest, h.length);
        long found = utf8.find(h, p, reach);
        int q = (int) (found >>> 32);
        return found >= 0 && q <= last ? q : -1;
    }

    /** {@link #findWithin(byte[], int, int)} in UTF-16 text: each start between is looked at, as they are few. */
    int findWithin(String s, int from, int last) {
        last = Math.min(last, s.length() - 1);
        for (int p = from; p <= last; p++) {
            if (literalAt(s, p) >= 0) {
                return p;
            }
        }
        return -1;
    }
}
