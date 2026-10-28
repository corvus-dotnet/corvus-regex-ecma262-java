package io.github.corvusdotnet.regex.ecma262;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Case-insensitive matching ({@code fold.rs}). ECMA-262 defines it through a Canonicalize function: two characters
 * match when they canonicalize to the same character. With the u flag it is Unicode simple case folding; with no flag
 * it is the single character uppercase mapping of a UTF-16 code unit, which never maps a character outside ASCII into
 * ASCII. The parser applies the equivalence to every literal and class as it reads them, so only a backreference needs
 * the function when matching. The folding and the uppercase mapping come from the tables of the {@link Ucd} of the
 * pattern's version of Unicode.
 */
final class Fold {
    private Fold() {}

    /** The characters of the Basic Multilingual Plane whose uppercase form is more than one character. */
    private static final CharSet MULTI_UPPER = CharSet.of(new int[] {
        0xDF, 0xDF, 0x149, 0x149, 0x1F0, 0x1F0, 0x390, 0x390, 0x3B0, 0x3B0, 0x587, 0x587, 0x1E96, 0x1E9A, 0x1F50,
        0x1F50, 0x1F52, 0x1F52, 0x1F54, 0x1F54, 0x1F56, 0x1F56, 0x1F80, 0x1FAF, 0x1FB2, 0x1FB4, 0x1FB6, 0x1FB7,
        0x1FBC, 0x1FBC, 0x1FC2, 0x1FC4, 0x1FC6, 0x1FC7, 0x1FCC, 0x1FCC, 0x1FD2, 0x1FD3, 0x1FD6, 0x1FD7, 0x1FE2,
        0x1FE4, 0x1FE6, 0x1FE7, 0x1FF2, 0x1FF4, 0x1FF6, 0x1FF7, 0x1FFC, 0x1FFC, 0xFB00, 0xFB06, 0xFB13, 0xFB17,
    }, false);

    /** The Canonicalize function of ECMA-262 for case-insensitive matching, with the case mappings of a version. */
    static int canonicalize(Ucd ucd, int r, boolean unicodeMode) {
        if (unicodeMode) {
            // Every character of a simple case folding class folds to the same character.
            return ucd.fold(r);
        }
        // A character beyond the Basic Multilingual Plane is two code units, and neither has an uppercase form.
        if (r > 0xFFFF || MULTI_UPPER.contains(r)) {
            return r;
        }
        int upper = ucd.toUpper(r);
        if (r >= 128 && upper < 128 || upper > 0xFFFF) {
            return r;
        }
        return upper;
    }

    /** The characters that are equivalent to some other character, each with all the members of its class. */
    static final class FoldTable {
        final int[] points;
        /** For each point, the number of its class in {@code classes}. */
        final int[] classOf;
        final int[][] classes;

        FoldTable(Map<Integer, IntList> byKey) {
            List<int[]> cls = new ArrayList<>();
            Map<Integer, Integer> byMember = new HashMap<>();
            IntList pts = new IntList();
            for (IntList members : byKey.values()) {
                if (members.size() < 2) {
                    continue;
                }
                for (int i = 0; i < members.size(); i++) {
                    pts.add(members.get(i));
                    byMember.put(members.get(i), cls.size());
                }
                cls.add(members.toArray());
            }
            points = pts.toArray();
            Arrays.sort(points);
            classOf = new int[points.length];
            for (int i = 0; i < points.length; i++) {
                classOf[i] = byMember.get(points[i]);
            }
            classes = cls.toArray(new int[0][]);
        }
    }

    /**
     * Whether two characters are equal ignoring case, with the case mappings of the version of Unicode whose major
     * number a backreference carries ({@code Compiler}): the test of a case-insensitive backreference. It is a call of
     * its own so that the matcher's method, which must stay under HotSpot's limit for compiling a method, grows by
     * nothing.
     */
    static boolean equalIgnoringCase(int major, int a, int b, boolean unicodeMode) {
        Ucd ucd = Ucd.byMajor(major);
        return canonicalize(ucd, a, unicodeMode) == canonicalize(ucd, b, unicodeMode);
    }

    /** The table of the u flag's grammar under a version of Unicode ({@link Ucd#unicodeFold} keeps it). */
    static FoldTable unicodeFoldTable(Ucd ucd) {
        // A class is a character that others fold to, with those others.
        Map<Integer, IntList> classes = new HashMap<>();
        for (int r : ucd.folding()) {
            int key = ucd.fold(r);
            classes.computeIfAbsent(key, k -> IntList.of(k)).add(r);
        }
        return new FoldTable(classes);
    }

    /** The table of the grammar of no flag under a version of Unicode ({@link Ucd#legacyFold} keeps it). */
    static FoldTable legacyFoldTable(Ucd ucd) {
        Map<Integer, IntList> classes = new HashMap<>();
        for (int r = 0; r <= 0xFFFF; r++) {
            classes.computeIfAbsent(canonicalize(ucd, r, false), k -> new IntList()).add(r);
        }
        return new FoldTable(classes);
    }

    /** The table of the grammar in use for a version of Unicode, built on first use. */
    static FoldTable foldClasses(Ucd ucd, boolean unicodeMode) {
        return unicodeMode ? ucd.unicodeFold.get() : ucd.legacyFold.get();
    }

    /** The result of {@link #foldClosure}: the closed set, and the number of characters with a case variant it held. */
    static final class Closure {
        final CharSet set;
        final int variants;

        Closure(CharSet set, int variants) {
            this.set = set;
            this.variants = variants;
        }
    }

    /** The set of every character equivalent to some member of the set under a version, with the work it did. */
    static Closure foldClosure(Ucd ucd, CharSet set, boolean unicodeMode) {
        FoldTable table = foldClasses(ucd, unicodeMode);
        IntList extra = new IntList();
        int variants = 0;
        int[] rs = set.ranges;
        for (int i = 0; i < rs.length; i += 2) {
            int lo = rs[i];
            int hi = rs[i + 1];
            int k = partitionPoint(table.points, lo);
            while (k < table.points.length && table.points[k] <= hi) {
                variants++;
                for (int member : table.classes[table.classOf[k]]) {
                    if (!set.contains(member)) {
                        extra.add(member, member);
                    }
                }
                k++;
            }
        }
        if (extra.isEmpty()) {
            return new Closure(set, variants);
        }
        extra.addAll(set.ranges);
        return new Closure(CharSet.of(extra, false), variants);
    }

    /** The index of the first element of the sorted array that is not below the value. */
    static int partitionPoint(int[] sorted, int value) {
        int lo = 0;
        int hi = sorted.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sorted[mid] < value) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /**
     * With the u flag and case-insensitive matching, {@code \w} and {@code \b} also count the two characters that fold
     * to an ASCII letter (U+017F, the long s, and U+212A, the Kelvin sign) as word characters.
     */
    private static final int[] FOLD_WORD_PAIRS = {
        0x30, 0x39, 0x41, 0x5A, 0x5F, 0x5F, 0x61, 0x7A, 0x17F, 0x17F, 0x212A, 0x212A,
    };

    static final CharSet FOLD_WORD_SET = CharSet.of(FOLD_WORD_PAIRS, false);
    static final CharSet NOT_FOLD_WORD_SET = CharSet.of(FOLD_WORD_PAIRS, true);

    static boolean isFoldWordRune(int r) {
        return (r >= 0 && r < 128 && CharSet.isWordByte(r)) || r == 0x17F || r == 0x212A;
    }
}
