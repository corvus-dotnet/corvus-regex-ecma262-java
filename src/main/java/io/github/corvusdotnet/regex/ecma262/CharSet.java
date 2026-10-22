package io.github.corvusdotnet.regex.ecma262;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

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

    /** The short name of a General_Category value, from its long name, an alias or the short name itself. */
    private static String categoryName(String value) {
        switch (value) {
            case "Letter": case "L": return "L";
            case "Lowercase_Letter": case "Ll": return "Ll";
            case "Uppercase_Letter": case "Lu": return "Lu";
            case "Titlecase_Letter": case "Lt": return "Lt";
            case "Cased_Letter": case "LC": return "LC";
            case "Modifier_Letter": case "Lm": return "Lm";
            case "Other_Letter": case "Lo": return "Lo";
            case "Mark": case "Combining_Mark": case "M": return "M";
            case "Nonspacing_Mark": case "Mn": return "Mn";
            case "Spacing_Mark": case "Mc": return "Mc";
            case "Enclosing_Mark": case "Me": return "Me";
            case "Number": case "N": return "N";
            case "Decimal_Number": case "digit": case "Nd": return "Nd";
            case "Letter_Number": case "Nl": return "Nl";
            case "Other_Number": case "No": return "No";
            case "Punctuation": case "punct": case "P": return "P";
            case "Connector_Punctuation": case "Pc": return "Pc";
            case "Dash_Punctuation": case "Pd": return "Pd";
            case "Open_Punctuation": case "Ps": return "Ps";
            case "Close_Punctuation": case "Pe": return "Pe";
            case "Initial_Punctuation": case "Pi": return "Pi";
            case "Final_Punctuation": case "Pf": return "Pf";
            case "Other_Punctuation": case "Po": return "Po";
            case "Symbol": case "S": return "S";
            case "Math_Symbol": case "Sm": return "Sm";
            case "Currency_Symbol": case "Sc": return "Sc";
            case "Modifier_Symbol": case "Sk": return "Sk";
            case "Other_Symbol": case "So": return "So";
            case "Separator": case "Z": return "Z";
            case "Space_Separator": case "Zs": return "Zs";
            case "Line_Separator": case "Zl": return "Zl";
            case "Paragraph_Separator": case "Zp": return "Zp";
            case "Other": case "C": return "C";
            case "Control": case "cntrl": case "Cc": return "Cc";
            case "Format": case "Cf": return "Cf";
            case "Surrogate": case "Cs": return "Cs";
            case "Private_Use": case "Co": return "Co";
            case "Unassigned": case "Cn": return "Cn";
            default: return null;
        }
    }

    /** The canonical name of a binary property ECMA-262 lists, from the name or an alias. */
    private static String binaryName(String name) {
        switch (name) {
            case "ASCII": return "ASCII";
            case "ASCII_Hex_Digit": case "AHex": return "ASCII_Hex_Digit";
            case "Alphabetic": case "Alpha": return "Alphabetic";
            case "Any": return "Any";
            case "Assigned": return "Assigned";
            case "Bidi_Control": case "Bidi_C": return "Bidi_Control";
            case "Bidi_Mirrored": case "Bidi_M": return "Bidi_Mirrored";
            case "Case_Ignorable": case "CI": return "Case_Ignorable";
            case "Cased": return "Cased";
            case "Changes_When_Casefolded": case "CWCF": return "Changes_When_Casefolded";
            case "Changes_When_Casemapped": case "CWCM": return "Changes_When_Casemapped";
            case "Changes_When_Lowercased": case "CWL": return "Changes_When_Lowercased";
            case "Changes_When_NFKC_Casefolded": case "CWKCF": return "Changes_When_NFKC_Casefolded";
            case "Changes_When_Titlecased": case "CWT": return "Changes_When_Titlecased";
            case "Changes_When_Uppercased": case "CWU": return "Changes_When_Uppercased";
            case "Dash": return "Dash";
            case "Default_Ignorable_Code_Point": case "DI": return "Default_Ignorable_Code_Point";
            case "Deprecated": case "Dep": return "Deprecated";
            case "Diacritic": case "Dia": return "Diacritic";
            case "Emoji": return "Emoji";
            case "Emoji_Component": case "EComp": return "Emoji_Component";
            case "Emoji_Modifier": case "EMod": return "Emoji_Modifier";
            case "Emoji_Modifier_Base": case "EBase": return "Emoji_Modifier_Base";
            case "Emoji_Presentation": case "EPres": return "Emoji_Presentation";
            case "Extended_Pictographic": case "ExtPict": return "Extended_Pictographic";
            case "Extender": case "Ext": return "Extender";
            case "Grapheme_Base": case "Gr_Base": return "Grapheme_Base";
            case "Grapheme_Extend": case "Gr_Ext": return "Grapheme_Extend";
            case "Hex_Digit": case "Hex": return "Hex_Digit";
            case "IDS_Binary_Operator": case "IDSB": return "IDS_Binary_Operator";
            case "IDS_Trinary_Operator": case "IDST": return "IDS_Trinary_Operator";
            case "ID_Continue": case "IDC": return "ID_Continue";
            case "ID_Start": case "IDS": return "ID_Start";
            case "Ideographic": case "Ideo": return "Ideographic";
            case "Join_Control": case "Join_C": return "Join_Control";
            case "Logical_Order_Exception": case "LOE": return "Logical_Order_Exception";
            case "Lowercase": case "Lower": return "Lowercase";
            case "Math": return "Math";
            case "Noncharacter_Code_Point": case "NChar": return "Noncharacter_Code_Point";
            case "Pattern_Syntax": case "Pat_Syn": return "Pattern_Syntax";
            case "Pattern_White_Space": case "Pat_WS": return "Pattern_White_Space";
            case "Quotation_Mark": case "QMark": return "Quotation_Mark";
            case "Radical": return "Radical";
            case "Regional_Indicator": case "RI": return "Regional_Indicator";
            case "Sentence_Terminal": case "STerm": return "Sentence_Terminal";
            case "Soft_Dotted": case "SD": return "Soft_Dotted";
            case "Terminal_Punctuation": case "Term": return "Terminal_Punctuation";
            case "Unified_Ideograph": case "UIdeo": return "Unified_Ideograph";
            case "Uppercase": case "Upper": return "Uppercase";
            case "Variation_Selector": case "VS": return "Variation_Selector";
            case "White_Space": case "space": return "White_Space";
            case "XID_Continue": case "XIDC": return "XID_Continue";
            case "XID_Start": case "XIDS": return "XID_Start";
            default: return null;
        }
    }

    /**
     * The sets already built for property expressions. Only an expression ECMA-262 defines is kept, and those are a
     * fixed few hundred, so the cache is bounded.
     */
    private static final ConcurrentHashMap<String, CharSet> PROPERTY_CACHE = new ConcurrentHashMap<>();

    /** Resolves the expression between the braces of {@code \p{...}} to its set; {@code null} for an unknown one. */
    static CharSet propertySet(String expr) {
        CharSet cached = PROPERTY_CACHE.get(expr);
        if (cached != null) {
            return cached;
        }
        int[] pairs = propertyPairs(expr);
        if (pairs == null) {
            return null;
        }
        CharSet set = of(pairs, false);
        PROPERTY_CACHE.putIfAbsent(expr, set);
        return set;
    }

    private static int[] propertyPairs(String expr) {
        int eq = expr.indexOf('=');
        if (eq >= 0) {
            String name = expr.substring(0, eq);
            String value = expr.substring(eq + 1);
            switch (name) {
                case "General_Category":
                case "gc":
                    return categoryPairs(value);
                case "Script":
                case "sc":
                    return Ucd.script(value);
                case "Script_Extensions":
                case "scx":
                    return Ucd.scriptExtensions(value);
                default:
                    return null;
            }
        }
        int[] pairs = categoryPairs(expr);
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
                return Ucd.binary(canonical);
        }
    }

    private static int[] categoryPairs(String value) {
        String name = categoryName(value);
        return name == null ? null : Ucd.category(name);
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

    private static final class StringProperties {
        static final List<StringProperty> ALL = build();

        private static List<StringProperty> build() {
            List<StringProperty> all = new ArrayList<>();
            IntList union = new IntList();
            List<int[]> every = new ArrayList<>();
            for (String[] row : StringsGen.PROPERTIES) {
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
            return all;
        }
    }

    /** The property of strings of the name ({@code RGI_Emoji} and the six it is the union of), or {@code null}. */
    static StringProperty stringProperty(String name) {
        List<StringProperty> all = StringProperties.ALL;
        if (name.equals("RGI_Emoji")) {
            return all.get(all.size() - 1);
        }
        for (int k = 0; k < StringsGen.PROPERTIES.length; k++) {
            if (StringsGen.PROPERTIES[k][0].equals(name)) {
                return all.get(k);
            }
        }
        return null;
    }
}
