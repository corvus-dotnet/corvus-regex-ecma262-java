package io.github.corvusdotnet.regex.ecma262;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The Unicode Character Database properties the engine reads, for each version of Unicode the library carries
 * ({@code ucd.rs}, one instance of the {@code ucd} package of the Go module for each version): {@link #v13()},
 * {@link #v17()} and {@link #v18()}.
 *
 * <p>Nothing in the engine takes Unicode properties from the Java platform, whose tables follow the JDK. With its own
 * tables the library gives the same answers whatever runs it, and the answers change only when the tables are
 * generated again. A pattern is read with the tables of the version it was compiled for. The tables of a version are
 * in {@code Tables<major>.java} and its properties of strings in {@code Strings<major>.java}; a version's classes load
 * when a pattern first asks for it. A value that follows from other values (a General_Category group such as L, the
 * Assigned property, the Unknown script) has no table of its own and is built when it is first asked for, and so is
 * everything else here that is built from the tables: the case folding classes, the property sets and the properties
 * of strings.
 */
final class Ucd {

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

    /** A value built on first use and then kept. */
    static final class Lazy<T> {
        private final Supplier<T> make;
        private volatile T value;

        Lazy(Supplier<T> make) {
            this.make = make;
        }

        T get() {
            T v = value;
            if (v == null) {
                synchronized (this) {
                    v = value;
                    if (v == null) {
                        v = make.get();
                        value = v;
                    }
                }
            }
            return v;
        }
    }

    /** The version of Unicode the tables hold, such as "17.0.0", and its major number. */
    final String version;
    final int major;
    private final Function<String, Table> categoryTable;
    private final Function<String, Table> binaryTable;
    private final Function<String, Table> scriptTable;
    private final Function<String, Table> scriptExtensionsTable;
    private final int[] caseFolds;
    private final int[] upperCases;
    /** The properties of strings of the v flag ({@code Strings<major>.java}). */
    final String[][] strings;
    /** Every name of every script: the long name of each, followed by its aliases. */
    final String[][] scriptNames;

    // The General_Category values that are groups of other values, the Assigned property (the code points that have a
    // General_Category other than Cn) and the Unknown script (the code points that belong to no script: the
    // unassigned, private use and surrogate code points).
    private final Lazy<int[]> letter = new Lazy<>(() -> union(gc("Lu"), gc("Ll"), gc("Lt"), gc("Lm"), gc("Lo")));
    private final Lazy<int[]> casedLetter = new Lazy<>(() -> union(gc("Lu"), gc("Ll"), gc("Lt")));
    private final Lazy<int[]> mark = new Lazy<>(() -> union(gc("Mn"), gc("Mc"), gc("Me")));
    private final Lazy<int[]> numberGroup = new Lazy<>(() -> union(gc("Nd"), gc("Nl"), gc("No")));
    private final Lazy<int[]> punctuation =
            new Lazy<>(() -> union(gc("Pc"), gc("Pd"), gc("Ps"), gc("Pe"), gc("Pi"), gc("Pf"), gc("Po")));
    private final Lazy<int[]> symbol = new Lazy<>(() -> union(gc("Sm"), gc("Sc"), gc("Sk"), gc("So")));
    private final Lazy<int[]> separator = new Lazy<>(() -> union(gc("Zs"), gc("Zl"), gc("Zp")));
    private final Lazy<int[]> other = new Lazy<>(() -> union(gc("Cc"), gc("Cf"), gc("Cs"), gc("Co"), gc("Cn")));
    private final Lazy<int[]> assigned = new Lazy<>(() -> complement(gc("Cn")));
    private final Lazy<int[]> unknownScript = new Lazy<>(() -> union(gc("Cn"), gc("Co"), gc("Cs")));

    /** The tables a group name is read with ({@code Parser}). */
    final Lazy<Table> idStart = new Lazy<>(() -> binaryTable("ID_Start"));
    final Lazy<Table> idContinue = new Lazy<>(() -> binaryTable("ID_Continue"));
    /** The classes of Canonicalize under the u flag's grammar and under the grammar of no flag ({@link Fold}). */
    final Lazy<Fold.FoldTable> unicodeFold = new Lazy<>(() -> Fold.unicodeFoldTable(this));
    final Lazy<Fold.FoldTable> legacyFold = new Lazy<>(() -> Fold.legacyFoldTable(this));
    /** The properties of strings, read from {@link #strings} ({@link CharSet}). */
    final Lazy<CharSet.StringProperty[]> stringProperties;
    /**
     * The sets already built for property expressions ({@link CharSet#propertySet}). Only an expression ECMA-262
     * defines is kept, and those are a fixed few hundred, so the cache is bounded.
     */
    final ConcurrentHashMap<String, CharSet> propertyCache = new ConcurrentHashMap<>();
    /** The expressions {@code \p{...}} may hold, for the grammar-only reader ({@link Validator}). */
    final Lazy<Validator.PropertyNames> propertyNames = new Lazy<>(() -> Validator.PropertyNames.of(this));

    private Ucd(String version, Function<String, Table> categoryTable, Function<String, Table> binaryTable,
            Function<String, Table> scriptTable, Function<String, Table> scriptExtensionsTable, int[] caseFolds,
            int[] upperCases, String[][] strings, String[][] scriptNames) {
        this.version = version;
        this.major = Integer.parseInt(version.substring(0, version.indexOf('.')));
        this.categoryTable = categoryTable;
        this.binaryTable = binaryTable;
        this.scriptTable = scriptTable;
        this.scriptExtensionsTable = scriptExtensionsTable;
        this.caseFolds = caseFolds;
        this.upperCases = upperCases;
        this.strings = strings;
        this.scriptNames = scriptNames;
        this.stringProperties = new Lazy<>(() -> CharSet.readStringProperties(strings));
    }

    // A holder class for each version, so that a version's tables load when it is first asked for.
    private static final class V13 {
        static final Ucd UCD = new Ucd(Tables13.VERSION, Tables13::categoryTable, Tables13::binaryTable,
                Tables13::scriptTable, Tables13::scriptExtensionsTable, Tables13.CASE_FOLDS, Tables13.UPPER_CASES,
                Strings13.PROPERTIES, Tables13.SCRIPT_NAMES);
    }

    private static final class V17 {
        static final Ucd UCD = new Ucd(Tables17.VERSION, Tables17::categoryTable, Tables17::binaryTable,
                Tables17::scriptTable, Tables17::scriptExtensionsTable, Tables17.CASE_FOLDS, Tables17.UPPER_CASES,
                Strings17.PROPERTIES, Tables17.SCRIPT_NAMES);
    }

    private static final class V18 {
        static final Ucd UCD = new Ucd(Tables18.VERSION, Tables18::categoryTable, Tables18::binaryTable,
                Tables18::scriptTable, Tables18::scriptExtensionsTable, Tables18.CASE_FOLDS, Tables18.UPPER_CASES,
                Strings18.PROPERTIES, Tables18.SCRIPT_NAMES);
    }

    /** Unicode 13.0. */
    static Ucd v13() {
        return V13.UCD;
    }

    /** Unicode 17.0. */
    static Ucd v17() {
        return V17.UCD;
    }

    /** Unicode 18.0. */
    static Ucd v18() {
        return V18.UCD;
    }

    /**
     * The Unicode of a version by its major number: 13, 17 or 18. A backreference carries the number of the version it
     * folds case with ({@code Compiler}), which is never another.
     */
    static Ucd byMajor(int major) {
        switch (major) {
            case 13:
                return v13();
            case 18:
                return v18();
            default:
                return v17();
        }
    }

    /** The generated table of a General_Category value that is not a group. */
    private Table gc(String name) {
        return categoryTable.apply(name);
    }

    /**
     * The table of the General_Category value by its short name, such as "Lu" or "L", as inclusive pairs (a copy the
     * caller may change). {@code null} for any other name.
     */
    int[] category(String name) {
        Lazy<int[]> built;
        switch (name) {
            case "L":
                built = letter;
                break;
            case "LC":
                built = casedLetter;
                break;
            case "M":
                built = mark;
                break;
            case "N":
                built = numberGroup;
                break;
            case "P":
                built = punctuation;
                break;
            case "S":
                built = symbol;
                break;
            case "Z":
                built = separator;
                break;
            case "C":
                built = other;
                break;
            default:
                Table t = categoryTable.apply(name);
                return t == null ? null : t.ranges();
        }
        return built.get().clone();
    }

    /** Whether the General_Category value has a table, by its short name (see {@link #category}). */
    boolean hasCategory(String name) {
        switch (name) {
            case "L":
            case "LC":
            case "M":
            case "N":
            case "P":
            case "S":
            case "Z":
            case "C":
                return true;
            default:
                return categoryTable.apply(name) != null;
        }
    }

    /** Whether the binary property has a table, by its canonical name (see {@link #binary}). */
    boolean hasBinary(String name) {
        return name.equals("Assigned") || binaryTable.apply(name) != null;
    }

    /** Whether the Script value has a table (see {@link #script}). */
    boolean hasScript(String name) {
        return isScriptWithoutTable(name) || scriptTable.apply(name) != null;
    }

    /** Whether the Script_Extensions value has a table (see {@link #scriptExtensions}). */
    boolean hasScriptExtensions(String name) {
        return isScriptWithoutTable(name) || scriptExtensionsTable.apply(name) != null;
    }

    /** The scripts whose sets are made here, not generated: Unknown, and Katakana_Or_Hiragana, which is empty. */
    private static boolean isScriptWithoutTable(String name) {
        switch (name) {
            case "Unknown":
            case "Zzzz":
            case "Katakana_Or_Hiragana":
            case "Hrkt":
                return true;
            default:
                return false;
        }
    }

    /**
     * The table of a binary property by its canonical name, such as "Alphabetic", as inclusive pairs. {@code null} for
     * any other name. ASCII and Any, which ECMA-262 adds to the properties of Unicode, are not here.
     */
    int[] binary(String name) {
        if (name.equals("Assigned")) {
            return assigned.get().clone();
        }
        Table t = binaryTable.apply(name);
        return t == null ? null : t.ranges();
    }

    /** The generated table of a binary property, for the callers that test one code point. */
    Table binaryTable(String name) {
        return binaryTable.apply(name);
    }

    /** The table of a Script value by its long name or an alias, such as "Greek" or "Grek". */
    int[] script(String name) {
        switch (name) {
            case "Unknown":
            case "Zzzz":
                return unknownScript.get().clone();
            case "Katakana_Or_Hiragana":
            case "Hrkt":
                // PropertyValueAliases.txt lists the value, and no code point has it.
                return new int[0];
            default:
                Table t = scriptTable.apply(name);
                return t == null ? null : t.ranges();
        }
    }

    /** The table of a Script_Extensions value by the long name of the script or an alias. */
    int[] scriptExtensions(String name) {
        switch (name) {
            case "Unknown":
            case "Zzzz":
                return unknownScript.get().clone();
            case "Katakana_Or_Hiragana":
            case "Hrkt":
                return new int[0];
            default:
                Table t = scriptExtensionsTable.apply(name);
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
    int fold(int r) {
        if (r < 0x80) {
            return r >= 'A' && r <= 'Z' ? r + ('a' - 'A') : r;
        }
        return mapCase(caseFolds, r);
    }

    /** The simple uppercase mapping of a code point, which is the code point itself when it has none. */
    int toUpper(int r) {
        if (r < 0x80) {
            return r >= 'a' && r <= 'z' ? r - ('a' - 'A') : r;
        }
        return mapCase(upperCases, r);
    }

    /** Every code point that {@link #fold} changes, in ascending order. */
    int[] folding() {
        IntList dst = new IntList();
        int[] t = caseFolds;
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
