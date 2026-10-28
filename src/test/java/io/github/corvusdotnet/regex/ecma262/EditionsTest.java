package io.github.corvusdotnet.regex.ecma262;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The edition of the grammar and the version of Unicode ({@code tests/editions.rs} of the Rust crate): ECMAScript 2020
 * refuses what later editions added and nothing else, and each version of Unicode has its own scripts and case folding,
 * in the matcher as in the parser.
 */
class EditionsTest {
    // U+10570 and U+10597 are a capital and a small letter of Vithkuqi, a script of Unicode 14. U+A7C0 and U+A7C1 are
    // the capital and the small Old Polish O, also of Unicode 14.
    private static final String VITHKUQI_PAIR = "𐕰𐖗";
    private static final String VITHKUQI_SMALL = "𐖗";
    private static final String OLD_POLISH_PAIR = "Ꟁꟁ";

    private static CompileResult build(String pattern, String flags, Edition edition, UnicodeVersion version) {
        return Regex.builder(pattern).flags(flags).edition(edition).unicodeVersion(version).tryBuild();
    }

    private static Regex under(String pattern, String flags, UnicodeVersion version) {
        return build(pattern, flags, Edition.CURRENT, version).orElseThrow();
    }

    private static boolean matches(Regex re, String text) {
        return re.isMatch(Utf8Text.of(text));
    }

    @Test
    void theDefaultsAreTodaysGrammarAndV8sUnicode() {
        Regex re = Regex.compile("a", "");
        assertEquals(Edition.CURRENT, re.edition());
        assertEquals(UnicodeVersion.V17, re.unicodeVersion());
        Regex built = Regex.builder("a").build();
        assertEquals(UnicodeVersion.V17, built.unicodeVersion());
        assertEquals(Regex.defaultMatchTimeout(), built.matchTimeout());
    }

    @Test
    void aVersionIsResolvedWhenThePatternIsCompiled() {
        UnicodeVersion[][] cases = {
            {UnicodeVersion.DEFAULT, UnicodeVersion.V17},
            {UnicodeVersion.V13, UnicodeVersion.V13},
            {UnicodeVersion.V17, UnicodeVersion.V17},
            {UnicodeVersion.V18, UnicodeVersion.V18},
            {UnicodeVersion.LATEST, UnicodeVersion.V18},
        };
        for (UnicodeVersion[] c : cases) {
            assertEquals(c[1], under("a", "", c[0]).unicodeVersion(), c[0].toString());
            assertEquals(c[1], c[0].resolve());
        }
        assertEquals(18, UnicodeVersion.LATEST.major());
        assertEquals(17, UnicodeVersion.DEFAULT.major());
        assertEquals(13, UnicodeVersion.V13.major());
    }

    @Test
    void eachVersionHasItsOwnScripts() {
        Object[][] cases = {
            {"\\p{Script=Vithkuqi}", UnicodeVersion.V13, false},
            {"\\p{Script=Vithkuqi}", UnicodeVersion.DEFAULT, true},
            {"\\p{Script=Yezidi}", UnicodeVersion.V13, true},
            {"\\p{Script=Jurchen}", UnicodeVersion.DEFAULT, false},
            {"\\p{scx=Jurc}", UnicodeVersion.V18, true},
            {"\\p{Script=Jurchen}", UnicodeVersion.LATEST, true},
        };
        for (Object[] c : cases) {
            String pattern = (String) c[0];
            UnicodeVersion version = (UnicodeVersion) c[1];
            boolean valid = (Boolean) c[2];
            assertEquals(valid, Regex.isValid(pattern, Flags.parse("u"), Edition.CURRENT, version), pattern + version);
            assertEquals(valid, build(pattern, "u", Edition.CURRENT, version).isOk(), pattern + version);
        }
    }

    @Test
    void aPropertySetIsItsVersionsHoweverTheCacheWasFilled() {
        // The same expression under two versions, in both orders: the cache of property sets is one for each version.
        for (int k = 0; k < 2; k++) {
            assertTrue(matches(under("^\\p{Ll}$", "u", UnicodeVersion.V17), VITHKUQI_SMALL));
            assertFalse(matches(under("^\\p{Ll}$", "u", UnicodeVersion.V13), VITHKUQI_SMALL));
        }
    }

    @Test
    void aLiteralInACaseInsensitivePatternFoldsAsItsVersionFolds() {
        assertTrue(matches(under("\\u{10570}", "iu", UnicodeVersion.V17), VITHKUQI_SMALL));
        assertFalse(matches(under("\\u{10570}", "iu", UnicodeVersion.V13), VITHKUQI_SMALL));
        assertTrue(matches(under("Ꟁ", "i", UnicodeVersion.V17), "ꟁ"));
        assertFalse(matches(under("Ꟁ", "i", UnicodeVersion.V13), "ꟁ"));
    }

    @Test
    void aBackreferenceInACaseInsensitivePatternFoldsAsItsVersionFolds() {
        String[][] cases = {{"iu", VITHKUQI_PAIR}, {"i", OLD_POLISH_PAIR}};
        for (String[] c : cases) {
            String flags = c[0];
            String text = c[1];
            String first = text.substring(0, Character.charCount(text.codePointAt(0)));
            String twice = first + first;
            for (String pattern : new String[] {"(.)\\1", "(?<=\\1(.))", "(?:x|(.))+\\1$"}) {
                for (UnicodeVersion version : new UnicodeVersion[] {UnicodeVersion.V17, UnicodeVersion.V18}) {
                    Regex re = under(pattern, flags, version);
                    assertTrue(matches(re, text), pattern + " /" + flags + " under " + version);
                    assertTrue(re.isMatch(text), pattern + " /" + flags + " over UTF-16 under " + version);
                }
                Regex re = under(pattern, flags, UnicodeVersion.V13);
                assertFalse(matches(re, text), pattern + " /" + flags + " under 13");
                assertFalse(re.isMatch(text), pattern + " /" + flags + " over UTF-16 under 13");
                assertTrue(matches(re, twice), pattern + " /" + flags + " under 13, the same letter twice");
                // The program that records every group folds as the one that finds the match.
                assertNotNull(re.captures(twice), pattern + " /" + flags + " captures under 13");
                assertNull(re.captures(text), pattern + " /" + flags + " captures under 13");
            }
        }
    }

    @Test
    void theGrammarOf2020RefusesWhatLaterEditionsAdded() {
        String[][] cases = {
            {"(?i:a)", ""},
            {"(?-i:a)", "i"},
            {"(?s:.)", "u"},
            {"(?<a>x)|(?<a>y)", ""},
            {"(?<a>x)|(?<a>y)", "u"},
            {"\\p{Script=Yezidi}", "u"},
            {"\\p{sc=Vith}", "u"},
            {"\\p{Script_Extensions=Toto}", "u"},
        };
        for (String[] c : cases) {
            CompileResult in2020 = build(c[0], c[1], Edition.ES2020, UnicodeVersion.LATEST);
            assertFalse(in2020.isOk(), c[0] + " /" + c[1] + " in 2020");
            assertEquals(RegexException.Kind.INVALID, in2020.error().orElseThrow().kind(), c[0] + " /" + c[1]);
            assertTrue(build(c[0], c[1], Edition.CURRENT, UnicodeVersion.LATEST).isOk(), c[0] + " /" + c[1] + " today");
        }
    }

    @Test
    void theGrammarOf2020AcceptsWhatItHad() {
        String[][] cases = {
            {"\\p{Script=Greek}", "u"},
            {"\\p{L}\\p{gc=Lu}\\p{Lowercase}", "u"},
            {"\\P{scx=Latn}", "iu"},
            {"\\p{Script=Yezidi}", ""},
            {"(?<a>x)(?<b>y)\\k<a>", "u"},
            {"(?<=a)(?<!b)", "s"},
            {"(?:i)", ""},
        };
        for (String[] c : cases) {
            assertTrue(build(c[0], c[1], Edition.ES2020, UnicodeVersion.V13).isOk(), c[0] + " /" + c[1]);
        }
    }

    @Test
    void theGrammarOf2020HasNoDOrVFlag() {
        for (String flags : new String[] {"d", "v"}) {
            CompileResult r = build("a", flags, Edition.ES2020, UnicodeVersion.DEFAULT);
            assertEquals(RegexException.Kind.FLAGS, r.error().orElseThrow().kind(), flags);
            assertFalse(Regex.isValid("a", Flags.parse(flags), Edition.ES2020, UnicodeVersion.DEFAULT));
            assertTrue(build("a", flags, Edition.CURRENT, UnicodeVersion.DEFAULT).isOk(), flags);
        }
    }

    @Test
    void theBuilderTakesAMatchTimeoutAndTheDefaultWithoutOne() {
        Regex re = Regex.builder("a+").flags("u").matchTimeout(Duration.ofMillis(50)).build();
        assertEquals(Duration.ofMillis(50), re.matchTimeout());
        assertEquals(Edition.CURRENT, re.edition());
        assertEquals(Duration.ZERO, Regex.builder("a").matchTimeout(null).build().matchTimeout());
        assertEquals(RegexException.Kind.FLAGS, Regex.builder("a").flags("gg").tryBuild().error().orElseThrow().kind());
        assertEquals(RegexException.Kind.INVALID,
                Regex.builder("a{2,1}").flags("u").tryBuild().error().orElseThrow().kind());
        assertThrows(RegexException.class, () -> Regex.builder("a{2,1}").flags("u").build());
    }

    @Test
    void tryCompileGivesTheRegexOrWhyAndThrowsNothing() {
        CompileResult ok = Regex.tryCompile("a+", "u", Duration.ofSeconds(1));
        assertTrue(ok.isOk());
        assertEquals(Duration.ofSeconds(1), ok.regex().orElseThrow().matchTimeout());
        assertTrue(ok.error().isEmpty());
        CompileResult bad = Regex.tryCompile("(", "u", Duration.ofSeconds(1));
        assertFalse(bad.isOk());
        assertTrue(bad.regex().isEmpty());
        assertEquals(RegexException.Kind.INVALID, bad.error().orElseThrow().kind());
        assertThrows(RegexException.class, bad::orElseThrow);
        assertEquals(RegexException.Kind.FLAGS, Regex.tryCompile("a", "x", null).error().orElseThrow().kind());
    }
}
