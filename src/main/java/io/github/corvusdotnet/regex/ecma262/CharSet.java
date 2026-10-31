package io.github.corvusdotnet.regex.ecma262;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An immutable set of code points ({@code charset.rs}). The ranges are sorted, inclusive, and neither overlap nor
 * touch. The two words of {@code ascii} repeat the membership of the code points below 128 so that the common case is
 * one bit test.
 */
final class CharSet {
    /** The last Unicode code point. */
    static final int MAX_RUNE = Ucd.MAX_RUNE;

    final int[] ranges;
    final long ascii0;
    final long ascii1;

    private CharSet(int[] ranges) {
        this.ranges = ranges;
        long a0 = 0;
        long a1 = 0;
        for (int i = 0; i < ranges.length; i += 2) {
            int lo = ranges[i];
            if (lo >= 128) {
                break;
            }
            for (int c = lo; c <= ranges[i + 1] && c < 128; c++) {
                if (c < 64) {
                    a0 |= 1L << c;
                } else {
                    a1 |= 1L << (c - 64);
                }
            }
        }
        ascii0 = a0;
        ascii1 = a1;
    }

    /** Builds a set from inclusive pairs in any order, which may overlap. With {@code negate}, the complement. */
    static CharSet of(int[] pairs, boolean negate) {
        int[] merged = Ucd.merge(pairs);
        if (negate) {
            IntList inverse = new IntList(merged.length + 2);
            int next = 0;
            for (int i = 0; i < merged.length; i += 2) {
                if (merged[i] > next) {
                    inverse.add(next, merged[i] - 1);
                }
                next = merged[i + 1] + 1;
            }
            if (next <= MAX_RUNE) {
                inverse.add(next, MAX_RUNE);
            }
            merged = inverse.toArray();
        }
        return new CharSet(merged);
    }

    static CharSet of(IntList pairs, boolean negate) {
        return of(pairs.toArray(), negate);
    }

    /** Whether the set holds the ASCII character. */
    boolean hasAscii(int c) {
        return ((c < 64 ? ascii0 >>> c : ascii1 >>> (c - 64)) & 1) != 0;
    }

    /** Whether the set holds the code point. */
    boolean contains(int r) {
        if (r >= 0 && r < 128) {
            return ((r < 64 ? ascii0 >>> r : ascii1 >>> (r - 64)) & 1) != 0;
        }
        int[] rs = ranges;
        int lo = 0;
        int hi = rs.length / 2;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (r > rs[2 * mid + 1]) {
                lo = mid + 1;
            } else if (r < rs[2 * mid]) {
                hi = mid;
            } else {
                return true;
            }
        }
        return false;
    }

    /** Whether the set holds no code point. */
    boolean isEmpty() {
        return ranges.length == 0;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof CharSet && Arrays.equals(ranges, ((CharSet) o).ranges);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(ranges);
    }

    // The classes ECMA-262 defines. \d and \w are ASCII only, \s is WhiteSpace and LineTerminator, and . is everything
    // but the four line terminators.
    static final int[] DIGIT_PAIRS = {0x30, 0x39};
    static final int[] WORD_PAIRS = {0x30, 0x39, 0x41, 0x5A, 0x5F, 0x5F, 0x61, 0x7A};
    static final int[] SPACE_PAIRS = {
        0x9, 0xD, 0x20, 0x20, 0xA0, 0xA0, 0x1680, 0x1680, 0x2000, 0x200A, 0x2028, 0x2029, 0x202F, 0x202F, 0x205F, 0x205F,
        0x3000, 0x3000, 0xFEFF, 0xFEFF,
    };
    static final int[] LINE_TERMINATOR_PAIRS = {0xA, 0xA, 0xD, 0xD, 0x2028, 0x2029};

    static final CharSet DOT_SET = of(LINE_TERMINATOR_PAIRS, true);

    /**
     * Whether a byte is an ECMA-262 word character. Every word character is ASCII, so a word boundary can be decided
     * from the units on either side of a position.
     */
    static boolean isWordByte(int b) {
        return (b >= '0' && b <= '9') || (b >= 'A' && b <= 'Z') || (b >= 'a' && b <= 'z') || b == '_';
    }

    /** The General_Category values ECMA-262 lists: the short name of each, then every name it may be written as. */
    static final String[][] CATEGORY_NAMES = {
        {"L", "Letter", "L"},
        {"Ll", "Lowercase_Letter", "Ll"},
        {"Lu", "Uppercase_Letter", "Lu"},
        {"Lt", "Titlecase_Letter", "Lt"},
        {"LC", "Cased_Letter", "LC"},
        {"Lm", "Modifier_Letter", "Lm"},
        {"Lo", "Other_Letter", "Lo"},
        {"M", "Mark", "Combining_Mark", "M"},
        {"Mn", "Nonspacing_Mark", "Mn"},
        {"Mc", "Spacing_Mark", "Mc"},
        {"Me", "Enclosing_Mark", "Me"},
        {"N", "Number", "N"},
        {"Nd", "Decimal_Number", "digit", "Nd"},
        {"Nl", "Letter_Number", "Nl"},
        {"No", "Other_Number", "No"},
        {"P", "Punctuation", "punct", "P"},
        {"Pc", "Connector_Punctuation", "Pc"},
        {"Pd", "Dash_Punctuation", "Pd"},
        {"Ps", "Open_Punctuation", "Ps"},
        {"Pe", "Close_Punctuation", "Pe"},
        {"Pi", "Initial_Punctuation", "Pi"},
        {"Pf", "Final_Punctuation", "Pf"},
        {"Po", "Other_Punctuation", "Po"},
        {"S", "Symbol", "S"},
        {"Sm", "Math_Symbol", "Sm"},
        {"Sc", "Currency_Symbol", "Sc"},
        {"Sk", "Modifier_Symbol", "Sk"},
        {"So", "Other_Symbol", "So"},
        {"Z", "Separator", "Z"},
        {"Zs", "Space_Separator", "Zs"},
        {"Zl", "Line_Separator", "Zl"},
        {"Zp", "Paragraph_Separator", "Zp"},
        {"C", "Other", "C"},
        {"Cc", "Control", "cntrl", "Cc"},
        {"Cf", "Format", "Cf"},
        {"Cs", "Surrogate", "Cs"},
        {"Co", "Private_Use", "Co"},
        {"Cn", "Unassigned", "Cn"},
    };

    /** Each name a table's rows may be written as, to the first name of its row. */
    private static Map<String, String> byName(String[][] rows) {
        Map<String, String> map = new HashMap<>();
        for (String[] row : rows) {
            for (int k = 1; k < row.length; k++) {
                map.put(row[k], row[0]);
            }
        }
        return map;
    }

    private static final Map<String, String> CATEGORY_NAMES_BY_NAME = byName(CATEGORY_NAMES);

    /** The short name of a General_Category value, from its long name, an alias or the short name itself. */
    private static String categoryName(String value) {
        return CATEGORY_NAMES_BY_NAME.get(value);
    }

    /** The binary properties ECMA-262 lists: the canonical name of each, then every name it may be written as. */
    static final String[][] BINARY_PROPERTY_NAMES = {
        {"ASCII", "ASCII"},
        {"ASCII_Hex_Digit", "ASCII_Hex_Digit", "AHex"},
        {"Alphabetic", "Alphabetic", "Alpha"},
        {"Any", "Any"},
        {"Assigned", "Assigned"},
        {"Bidi_Control", "Bidi_Control", "Bidi_C"},
        {"Bidi_Mirrored", "Bidi_Mirrored", "Bidi_M"},
        {"Case_Ignorable", "Case_Ignorable", "CI"},
        {"Cased", "Cased"},
        {"Changes_When_Casefolded", "Changes_When_Casefolded", "CWCF"},
        {"Changes_When_Casemapped", "Changes_When_Casemapped", "CWCM"},
        {"Changes_When_Lowercased", "Changes_When_Lowercased", "CWL"},
        {"Changes_When_NFKC_Casefolded", "Changes_When_NFKC_Casefolded", "CWKCF"},
        {"Changes_When_Titlecased", "Changes_When_Titlecased", "CWT"},
        {"Changes_When_Uppercased", "Changes_When_Uppercased", "CWU"},
        {"Dash", "Dash"},
        {"Default_Ignorable_Code_Point", "Default_Ignorable_Code_Point", "DI"},
        {"Deprecated", "Deprecated", "Dep"},
        {"Diacritic", "Diacritic", "Dia"},
        {"Emoji", "Emoji"},
        {"Emoji_Component", "Emoji_Component", "EComp"},
        {"Emoji_Modifier", "Emoji_Modifier", "EMod"},
        {"Emoji_Modifier_Base", "Emoji_Modifier_Base", "EBase"},
        {"Emoji_Presentation", "Emoji_Presentation", "EPres"},
        {"Extended_Pictographic", "Extended_Pictographic", "ExtPict"},
        {"Extender", "Extender", "Ext"},
        {"Grapheme_Base", "Grapheme_Base", "Gr_Base"},
        {"Grapheme_Extend", "Grapheme_Extend", "Gr_Ext"},
        {"Hex_Digit", "Hex_Digit", "Hex"},
        {"IDS_Binary_Operator", "IDS_Binary_Operator", "IDSB"},
        {"IDS_Trinary_Operator", "IDS_Trinary_Operator", "IDST"},
        {"ID_Continue", "ID_Continue", "IDC"},
        {"ID_Start", "ID_Start", "IDS"},
        {"Ideographic", "Ideographic", "Ideo"},
        {"Join_Control", "Join_Control", "Join_C"},
        {"Logical_Order_Exception", "Logical_Order_Exception", "LOE"},
        {"Lowercase", "Lowercase", "Lower"},
        {"Math", "Math"},
        {"Noncharacter_Code_Point", "Noncharacter_Code_Point", "NChar"},
        {"Pattern_Syntax", "Pattern_Syntax", "Pat_Syn"},
        {"Pattern_White_Space", "Pattern_White_Space", "Pat_WS"},
        {"Quotation_Mark", "Quotation_Mark", "QMark"},
        {"Radical", "Radical"},
        {"Regional_Indicator", "Regional_Indicator", "RI"},
        {"Sentence_Terminal", "Sentence_Terminal", "STerm"},
        {"Soft_Dotted", "Soft_Dotted", "SD"},
        {"Terminal_Punctuation", "Terminal_Punctuation", "Term"},
        {"Unified_Ideograph", "Unified_Ideograph", "UIdeo"},
        {"Uppercase", "Uppercase", "Upper"},
        {"Variation_Selector", "Variation_Selector", "VS"},
        {"White_Space", "White_Space", "space"},
        {"XID_Continue", "XID_Continue", "XIDC"},
        {"XID_Start", "XID_Start", "XIDS"},
    };

    private static final Map<String, String> BINARY_PROPERTY_NAMES_BY_NAME = byName(BINARY_PROPERTY_NAMES);

    /** The canonical name of a binary property ECMA-262 lists, from the name or an alias. */
    private static String binaryName(String name) {
        return BINARY_PROPERTY_NAMES_BY_NAME.get(name);
    }

    /**
     * Resolves the expression between the braces of {@code \p{...}} to its set under a version of Unicode;
     * {@code null} for an unknown one. The sets already built are kept, a cache for each version
     * ({@link Ucd#propertyCache}).
     */
    static CharSet propertySet(Ucd ucd, String expr) {
        CharSet cached = ucd.propertyCache.get(expr);
        if (cached != null) {
            return cached;
        }
        int[] pairs = propertyPairs(ucd, expr);
        if (pairs == null) {
            return null;
        }
        CharSet set = of(pairs, false);
        ucd.propertyCache.putIfAbsent(expr, set);
        return set;
    }

    private static int[] propertyPairs(Ucd ucd, String expr) {
        int eq = expr.indexOf('=');
        if (eq >= 0) {
            String name = expr.substring(0, eq);
            String value = expr.substring(eq + 1);
            switch (name) {
                case "General_Category":
                case "gc":
                    return categoryPairs(ucd, value);
                case "Script":
                case "sc":
                    return ucd.script(value);
                case "Script_Extensions":
                case "scx":
                    return ucd.scriptExtensions(value);
                default:
                    return null;
            }
        }
        int[] pairs = categoryPairs(ucd, expr);
        if (pairs != null) {
            return pairs;
        }
        String canonical = binaryName(expr);
        if (canonical == null) {
            return null;
        }
        switch (canonical) {
            case "ASCII":
                return new int[] {0, 0x7F};
            case "Any":
                return new int[] {0, MAX_RUNE};
            default:
                return ucd.binary(canonical);
        }
    }

    /**
     * Whether the expression between the braces of {@code \p{...}} names a property under a version of Unicode, as
     * {@link #propertySet} finds one, without building its set.
     */
    static boolean hasProperty(Ucd ucd, String expr) {
        int eq = expr.indexOf('=');
        if (eq >= 0) {
            String name = expr.substring(0, eq);
            String value = expr.substring(eq + 1);
            switch (name) {
                case "General_Category":
                case "gc":
                    return hasCategory(ucd, value);
                case "Script":
                case "sc":
                    return ucd.hasScript(value);
                case "Script_Extensions":
                case "scx":
                    return ucd.hasScriptExtensions(value);
                default:
                    return false;
            }
        }
        if (hasCategory(ucd, expr)) {
            return true;
        }
        String canonical = binaryName(expr);
        return canonical != null && (canonical.equals("ASCII") || canonical.equals("Any") || ucd.hasBinary(canonical));
    }

    private static boolean hasCategory(Ucd ucd, String value) {
        String name = categoryName(value);
        return name != null && ucd.hasCategory(name);
    }

    private static int[] categoryPairs(Ucd ucd, String value) {
        String name = categoryName(value);
        return name == null ? null : ucd.category(name);
    }

    /** The code points in both sets. */
    CharSet intersect(CharSet other) {
        int[] a = ranges;
        int[] b = other.ranges;
        int i = 0;
        int j = 0;
        IntList pairs = new IntList();
        while (i < a.length && j < b.length) {
            int lo = Math.max(a[i], b[j]);
            int hi = Math.min(a[i + 1], b[j + 1]);
            if (lo <= hi) {
                pairs.add(lo, hi);
            }
            if (a[i + 1] < b[j + 1]) {
                i += 2;
            } else {
                j += 2;
            }
        }
        return of(pairs, false);
    }

    /** The code points of this set that are not in the other. */
    CharSet subtract(CharSet other) {
        return intersect(of(other.ranges, true));
    }

    /** The code points of either set. */
    CharSet union(CharSet other) {
        int[] pairs = Arrays.copyOf(ranges, ranges.length + other.ranges.length);
        System.arraycopy(other.ranges, 0, pairs, ranges.length, other.ranges.length);
        return of(pairs, false);
    }

    /** The code points not in the set. */
    CharSet complement() {
        return of(ranges, true);
    }

    /** The value of a property of strings, which only the v flag can name: its single code points and sequences. */
    static final class StringProperty {
        final CharSet set;
        final List<int[]> strings;

        StringProperty(CharSet set, List<int[]> strings) {
            this.set = set;
            this.strings = strings;
        }
    }

    /**
     * Reads the properties of strings of a version of Unicode ({@code Strings<major>.java}), with their union last.
     * {@link Ucd#stringProperties} keeps them from their first use.
     */
    static StringProperty[] readStringProperties(String[][] properties) {
        List<StringProperty> all = new ArrayList<>();
        IntList union = new IntList();
        List<int[]> every = new ArrayList<>();
        for (String[] row : properties) {
            IntList pairs = new IntList();
            for (String item : row[1].split(" ")) {
                if (item.isEmpty()) {
                    continue;
                }
                int dots = item.indexOf("..");
                String lo = dots < 0 ? item : item.substring(0, dots);
                String hi = dots < 0 ? item : item.substring(dots + 2);
                pairs.add(Integer.parseInt(lo, 16), Integer.parseInt(hi, 16));
            }
            List<int[]> strings = new ArrayList<>();
            for (String sequence : row[2].split(";")) {
                if (sequence.isEmpty()) {
                    continue;
                }
                String[] points = sequence.split(" ");
                int[] s = new int[points.length];
                for (int k = 0; k < points.length; k++) {
                    s[k] = Integer.parseInt(points[k], 16);
                }
                strings.add(s);
            }
            union.addAll(pairs);
            every.addAll(strings);
            all.add(new StringProperty(of(pairs, false), strings));
        }
        all.add(new StringProperty(of(union, false), every));
        return all.toArray(new StringProperty[0]);
    }

    /**
     * The property of strings of the name ({@code RGI_Emoji} and the six it is the union of) under a version of
     * Unicode, or {@code null}.
     */
    static StringProperty stringProperty(Ucd ucd, String name) {
        StringProperty[] all = ucd.stringProperties.get();
        if (name.equals("RGI_Emoji")) {
            return all[all.length - 1];
        }
        for (int k = 0; k < ucd.strings.length; k++) {
            if (ucd.strings[k][0].equals(name)) {
                return all[k];
            }
        }
        return null;
    }
}
