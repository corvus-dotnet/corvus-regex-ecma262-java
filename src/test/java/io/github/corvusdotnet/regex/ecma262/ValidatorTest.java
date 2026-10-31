package io.github.corvusdotnet.regex.ecma262;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

/** The grammar-only reader, {@link Regex#isValidPattern}: the grammar's answer, without the limits of size. */
class ValidatorTest {
    private static String deep(int n, String open, String close) {
        return open.repeat(n) + "a" + close.repeat(n);
    }

    /** A pattern over a limit of size is valid when its grammar is, and not valid when it is not, however large. */
    @Test
    void theGrammarOnlyReaderAppliesNoLimitOfSize() {
        StringBuilder names = new StringBuilder();
        StringBuilder shared = new StringBuilder();
        StringBuilder twice = new StringBuilder();
        for (int n = 0; n < 5000; n++) {
            names.append("(?<n").append(n).append(">a)");
            if (n > 0) {
                shared.append('|');
            }
            shared.append("(?<n>a)");
            twice.append("(?<n>a)");
        }
        Object[][] cases = {
            {"a".repeat(Regex.MAX_PATTERN_LENGTH + 1), "u", true},
            {"a".repeat(Regex.MAX_PATTERN_LENGTH + 1) + "(", "u", false},
            {deep(Regex.MAX_NESTING + 1, "(?:", ")"), "u", true},
            {deep(200000, "(?:", ")"), "", true},
            {deep(200000, "(?:", ")") + ")", "u", false},
            {deep(200000, "(?=", ")"), "", true},
            {deep(200000, "[", "]"), "v", true},
            {deep(200000, "[^", "]"), "v", true},
            {deep(200000, "[", "]") + "]", "v", false},
            {names + "\\k<n4999>", "u", true},
            {names + "\\k<m>", "u", false},
            {shared.toString(), "u", true},
            {twice.toString(), "u", false},
            // A Java string may hold a lone surrogate, which the u flag's grammar reads as a code point of its own.
            {"a\uD800", "u", true},
            {"[\uDC00-\uD800]", "", false},
        };
        for (Object[] c : cases) {
            String pattern = (String) c[0];
            String flags = (String) c[1];
            boolean valid = (Boolean) c[2];
            String shown = pattern.length() > 40 ? pattern.substring(0, 40) : pattern;
            assertEquals(valid, Regex.isValidPattern(pattern, flags), () -> "isValidPattern(" + shown + ", " + flags + ")");
            if (!valid) {
                assertFalse(Regex.isValid(pattern, flags), () -> "isValid(" + shown + ", " + flags + ")");
            }
        }
        assertFalse(Regex.isValidPattern("a", "uv"));
        assertFalse(Regex.isValidPattern("a", "uu"));
        assertFalse(Regex.isValidPattern("a", "x"));
        assertFalse(Regex.isValidPattern("a", Flags.parse("v"), Edition.ES2020, UnicodeVersion.DEFAULT));
        assertFalse(Regex.isValidPattern("a", Flags.parse("d"), Edition.ES2020, UnicodeVersion.DEFAULT));
    }

    /**
     * Wherever the parser reaches no limit, the reader decides as it does, in each grammar, edition and version of
     * Unicode.
     */
    @Test
    void theGrammarOnlyReaderAgreesWithTheParser() {
        String[] patterns = {
            "(?i:a)", "(?<a>x)|(?<a>y)", "(?<a>x)(?<a>y)", "((?<a>x)|(?<a>y))(?<a>z)", "(?<a>x)|((?<a>y)|(?<a>z))",
            "\\k<a>(?<a>x)", "\\k<a>", "\\k", "(?<a>x)\\k", "\\1(x)", "\\2(x)", "\\8", "\\08", "[\\8]", "\\c", "[\\c_]",
            "\\u{10FFFF}", "\\u{110000}", "😀", "(?<\\u{1d4d0}>x)", "(?<𝓐>x)", "(?<\\ud835\\udcd0>x)",
            "\\p{Script=Greek}", "\\p{sc=Yezi}", "\\p{Script=Sidetic}", "\\P{RGI_Emoji}", "\\p{RGI_Emoji}",
            "[^\\p{RGI_Emoji}]", "\\p{L}", "\\p{Lu}", "\\p{Letter}", "\\p{ASCII}", "\\p{Any}", "\\p{Assigned}",
            "\\p{gc=LC}", "\\p{scx=Hrkt}", "\\p{é}", "\\p{", "\\p{}", "a{2,1}", "a{99999999999999999999,1}",
            "a{1,99999999999999999999}", "a{,1}", "x{", "{1}", "]", "}", "[a-]", "[\\d-a]", "[a-\\d]", "[z-a]",
            "[\\b-\\n]", "(?=a)*", "(?<=a)*", "(?!a){2}", "[[a]&&[b]]", "[a&&b&&c]", "[a--b--c]", "[a&&b--c]",
            "[a-z&&b]", "[\\q{abc}]", "[^\\q{abc}]", "[^\\q{a|b}]", "[^[\\q{ab}]]", "[^[^\\q{ab}]]", "[[\\q{ab}]&&a]",
            "[^[\\q{ab}]&&a]", "[^a--\\q{ab}]", "[\\q{ab}--a]", "[^\\q{ab}--a]", "[a&&]", "[&&a]", "[a!!b]", "[\\!]",
            "[(]", "[a-[b]]", "[\\q{a", "(?i-i:a)", "(?-:a)", "(?ii:a)", "(?x:a)", "(?i", "(?<", "(?<a", "(?<a>",
            "(?<=", "a|*", "\\", "[\\", "(", ")", "a)", "\\b+", "^*", "$+", "\\B{2}", "\\xg", "\\x4", "\\-", "[\\-]",
            "\\/", "\\a", "\\_", "😀{2}", "[😀-😁]", "[😁-😀]", "\uD800", "[\uDC00-\uD800]", "(?<a\uD800>x)",
            "\\p{General_Category=Lu}", "\\p{Script_Extensions=Latin}", "\\p{ID_Compat_Math_Start}", "\\p{Lowercase}",
            "\\p{Script=Katakana_Or_Hiragana}", "\\p{Basic_Emoji}", "\\P{Basic_Emoji}",
        };
        for (String p : patterns) {
            for (String flags : new String[] {"", "u", "v", "i", "iu"}) {
                Flags f = Flags.parse(flags);
                for (Edition edition : new Edition[] {Edition.CURRENT, Edition.ES2020}) {
                    for (UnicodeVersion version : new UnicodeVersion[] {UnicodeVersion.DEFAULT, UnicodeVersion.V13,
                        UnicodeVersion.V18}) {
                        boolean want = Regex.isValid(p, f, edition, version);
                        assertEquals(want, Regex.isValidPattern(p, f, edition, version),
                                () -> "/" + p + "/" + flags + " " + edition + " " + version);
                    }
                }
            }
        }
    }

    /** Every expression the reader's table holds is one the parser finds a set for, and only those. */
    @Test
    void theTableOfPropertyNamesHoldsWhatTheParserKnows() {
        for (UnicodeVersion version : new UnicodeVersion[] {UnicodeVersion.V13, UnicodeVersion.V17,
            UnicodeVersion.V18}) {
            Ucd ucd = version.ucd();
            for (String[] row : CharSet.BINARY_PROPERTY_NAMES) {
                for (int k = 1; k < row.length; k++) {
                    String name = row[k];
                    assertEquals(CharSet.propertySet(ucd, name) != null,
                            ucd.propertyNames.get().find(name, 0, name.length()) >= 0, () -> version + " " + name);
                }
            }
            for (String[] row : ucd.scriptNames) {
                for (String name : row) {
                    for (String prefix : new String[] {"Script=", "sc=", "Script_Extensions=", "scx="}) {
                        String expr = prefix + name;
                        assertEquals(CharSet.propertySet(ucd, expr) != null,
                                ucd.propertyNames.get().find(expr, 0, expr.length()) >= 0, () -> version + " " + expr);
                    }
                }
            }
            for (String[] row : CharSet.CATEGORY_NAMES) {
                for (int k = 1; k < row.length; k++) {
                    for (String prefix : new String[] {"", "gc=", "General_Category="}) {
                        String expr = prefix + row[k];
                        assertEquals(CharSet.propertySet(ucd, expr) != null,
                                ucd.propertyNames.get().find(expr, 0, expr.length()) >= 0, () -> version + " " + expr);
                    }
                }
            }
        }
    }
}
