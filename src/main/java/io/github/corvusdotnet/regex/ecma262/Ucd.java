package io.github.corvusdotnet.regex.ecma262;

import java.util.Arrays;

/**
 * The Unicode Character Database properties the engine reads, for one fixed version of Unicode ({@code ucd.rs}, the
 * {@code ucd} package of the Go module).
 *
 * <p>Nothing in the engine takes Unicode properties from the Java platform, whose tables follow the JDK. With its own
 * tables the library gives the same answers whatever runs it, and the answers change only when the tables are
 * generated again. {@link TablesGen#VERSION} names the version of Unicode they hold. A value that follows from other
 * values (a General_Category group such as L, the Assigned property, the Unknown script) has no table of its own and
 * is built when it is first asked for.
 */
final class Ucd {
    private Ucd() {}

    /** The last Unicode code point. */
    static final int MAX_RUNE = 0x10FFFF;

    /** The table of the code points that any of the tables holds, as inclusive pairs. */
    static int[] union(Table... tables) {
        IntList all = new IntList();
        for (Table t : tables) {
            t.appendRanges(all);
        }
        return merge(all.toArray());
    }

    /** Sorts inclusive pairs and merges those that overlap or touch. */
    static int[] merge(int[] pairs) {
        int count = pairs.length / 2;
        long[] order = new long[count];
        for (int i = 0; i < count; i++) {
            order[i] = (long) pairs[2 * i] << 32 | i;
        }
        Arrays.sort(order);
        IntList merged = new IntList(pairs.length);
        for (long o : order) {
            int i = (int) o;
            int lo = pairs[2 * i];
            int hi = pairs[2 * i + 1];
            if (!merged.isEmpty() && lo <= merged.last() + 1) {
                if (hi > merged.last()) {
                    merged.set(merged.size() - 1, hi);
                }
                continue;
            }
            merged.add(lo, hi);
        }
        return merged.toArray();
    }

    /** The code points that the table does not hold, as inclusive pairs. */
    static int[] complement(Table t) {
        int[] pairs = t.ranges();
        IntList inverse = new IntList(pairs.length + 2);
        int next = 0;
        for (int i = 0; i < pairs.length; i += 2) {
            if (pairs[i] > next) {
                inverse.add(next, pairs[i] - 1);
            }
            next = pairs[i + 1] + 1;
        }
        if (next <= MAX_RUNE) {
            inverse.add(next, MAX_RUNE);
        }
        return inverse.toArray();
    }

    // The General_Category values that are groups of other values, the Assigned property and the Unknown script. Each
    // is built on first use and kept, a holder class each.
    private static final class Letter {
        static final int[] PAIRS =
                union(TablesGen.GC_LU, TablesGen.GC_LL, TablesGen.GC_LT, TablesGen.GC_LM, TablesGen.GC_LO);
    }

    private static final class CasedLetter {
        static final int[] PAIRS = union(TablesGen.GC_LU, TablesGen.GC_LL, TablesGen.GC_LT);
    }

    private static final class Mark {
        static final int[] PAIRS = union(TablesGen.GC_MN, TablesGen.GC_MC, TablesGen.GC_ME);
    }

    private static final class NumberGroup {
        static final int[] PAIRS = union(TablesGen.GC_ND, TablesGen.GC_NL, TablesGen.GC_NO);
    }

    private static final class Punctuation {
        static final int[] PAIRS = union(TablesGen.GC_PC, TablesGen.GC_PD, TablesGen.GC_PS, TablesGen.GC_PE,
                TablesGen.GC_PI, TablesGen.GC_PF, TablesGen.GC_PO);
    }

    private static final class Symbol {
        static final int[] PAIRS = union(TablesGen.GC_SM, TablesGen.GC_SC, TablesGen.GC_SK, TablesGen.GC_SO);
    }

    private static final class Separator {
        static final int[] PAIRS = union(TablesGen.GC_ZS, TablesGen.GC_ZL, TablesGen.GC_ZP);
    }

    private static final class Other {
        static final int[] PAIRS =
                union(TablesGen.GC_CC, TablesGen.GC_CF, TablesGen.GC_CS, TablesGen.GC_CO, TablesGen.GC_CN);
    }

    /** The code points that have a General_Category other than Cn. */
    private static final class Assigned {
        static final int[] PAIRS = complement(TablesGen.GC_CN);
    }

    /** The code points that belong to no script: the unassigned, private use and surrogate code points. */
    private static final class UnknownScript {
        static final int[] PAIRS = union(TablesGen.GC_CN, TablesGen.GC_CO, TablesGen.GC_CS);
    }

    /**
     * The table of the General_Category value by its short name, such as "Lu" or "L", as inclusive pairs (a copy the
     * caller may change). {@code null} for any other name.
     */
    static int[] category(String name) {
        int[] built;
        switch (name) {
            case "L":
                built = Letter.PAIRS;
                break;
            case "LC":
                built = CasedLetter.PAIRS;
                break;
            case "M":
                built = Mark.PAIRS;
                break;
            case "N":
                built = NumberGroup.PAIRS;
                break;
            case "P":
                built = Punctuation.PAIRS;
                break;
            case "S":
                built = Symbol.PAIRS;
                break;
            case "Z":
                built = Separator.PAIRS;
                break;
            case "C":
                built = Other.PAIRS;
                break;
            default:
                Table t = TablesGen.categoryTable(name);
                return t == null ? null : t.ranges();
        }
        return built.clone();
    }

    /**
     * The table of a binary property by its canonical name, such as "Alphabetic", as inclusive pairs. {@code null} for
     * any other name. ASCII and Any, which ECMA-262 adds to the properties of Unicode, are not here.
     */
    static int[] binary(String name) {
        if (name.equals("Assigned")) {
            return Assigned.PAIRS.clone();
        }
        Table t = TablesGen.binaryTable(name);
        return t == null ? null : t.ranges();
    }

    /** The generated table of a binary property, for the callers that test one code point. */
    static Table binaryTable(String name) {
        return TablesGen.binaryTable(name);
    }

    /** The table of a Script value by its long name or an alias, such as "Greek" or "Grek". */
    static int[] script(String name) {
        switch (name) {
            case "Unknown":
            case "Zzzz":
                return UnknownScript.PAIRS.clone();
            case "Katakana_Or_Hiragana":
            case "Hrkt":
                // PropertyValueAliases.txt lists the value, and no code point has it.
                return new int[0];
            default:
                Table t = TablesGen.scriptTable(name);
                return t == null ? null : t.ranges();
        }
    }

    /** The table of a Script_Extensions value by the long name of the script or an alias. */
    static int[] scriptExtensions(String name) {
        switch (name) {
            case "Unknown":
            case "Zzzz":
                return UnknownScript.PAIRS.clone();
            case "Katakana_Or_Hiragana":
            case "Hrkt":
                return new int[0];
            default:
                Table t = TablesGen.scriptExtensionsTable(name);
                return t == null ? null : t.ranges();
        }
    }

    /**
     * Maps some of the code points {@code first} to {@code first + length - 1} to themselves plus {@code delta}: those
     * whose offset from {@code first} has none of the bits of {@code mask}. The rows are four ints each.
     */
    private static int mapCase(int[] table, int r) {
        int lo = 0;
        int hi = table.length / 4;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            int first = table[4 * mid];
            if (r < first) {
                hi = mid;
            } else {
                int offset = r - first;
                if (offset >= table[4 * mid + 2]) {
                    lo = mid + 1;
                } else if ((offset & table[4 * mid + 3]) == 0) {
                    return r + table[4 * mid + 1];
                } else {
                    return r;
                }
            }
        }
        return r;
    }

    /**
     * The simple case folding of a code point (the C and S rows of CaseFolding.txt), which is the code point itself
     * when it has none.
     */
    static int fold(int r) {
        if (r < 0x80) {
            return r >= 'A' && r <= 'Z' ? r + ('a' - 'A') : r;
        }
        return mapCase(TablesGen.CASE_FOLDS, r);
    }

    /** The simple uppercase mapping of a code point, which is the code point itself when it has none. */
    static int toUpper(int r) {
        if (r < 0x80) {
            return r >= 'a' && r <= 'z' ? r - ('a' - 'A') : r;
        }
        return mapCase(TablesGen.UPPER_CASES, r);
    }

    /** Every code point that {@link #fold} changes, in ascending order. */
    static int[] folding() {
        IntList dst = new IntList();
        int[] t = TablesGen.CASE_FOLDS;
        for (int i = 0; i < t.length; i += 4) {
            for (int offset = 0; offset < t[i + 2]; offset++) {
                if ((offset & t[i + 3]) == 0 && t[i + 1] != 0) {
                    dst.add(t[i] + offset);
                }
            }
        }
        return dst.toArray();
    }
}
