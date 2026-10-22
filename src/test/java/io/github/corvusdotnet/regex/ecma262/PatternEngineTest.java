package io.github.corvusdotnet.regex.ecma262;

import static io.github.corvusdotnet.regex.ecma262.PatternEngineCases.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Patterns that an engine answered differently from ECMA-262 at some point, with V8's answers (pattern_engine.rs). They
 * come from the engine's first home, the Corvus JSON Schema evaluator for Rust, where a pattern is read as JSON Schema
 * reads one: with the u flag grammar, and when that refuses it, with the Annex B grammar. Every answer is asked with a
 * limit, so a match that reaches its limit fails a test here and is not taken for an answer. The tables are in
 * {@link PatternEngineCases}, written from the Rust file by tools/tests/rust_tables.py.
 */
class PatternEngineTest {
    /** A pattern read as JSON Schema reads one, with a limit on each match (one second unless the test says). */
    static final class Validator {
        final Regex re;
        final Duration limit;

        Validator(Regex re, Duration limit) {
            this.re = re;
            this.limit = limit;
        }

        /** The answer, or {@code null} when the match reached its limit. */
        Boolean tryValidate(String text) {
            try {
                return re.isMatch(text, limit);
            } catch (MatchLimitException e) {
                return null;
            }
        }

        boolean isValid(String text) {
            Boolean b = tryValidate(text);
            return b != null && b;
        }
    }

    /** Reads a pattern as JSON Schema does: the u flag grammar, then the Annex B grammar. */
    static Regex read(String pattern) {
        try {
            return Regex.compile(pattern, "u");
        } catch (RegexException e) {
            if (e.kind() != RegexException.Kind.INVALID) {
                throw e;
            }
            return Regex.compile(pattern, "");
        }
    }

    /** The validator of a pattern, or the message of the schema's compile error. */
    static Object compile(String pattern, Duration limit) {
        try {
            return new Validator(read(pattern), limit);
        } catch (RegexException e) {
            return e.kind() == RegexException.Kind.INVALID ? "Invalid regular expression '" + pattern + "' in pattern."
                    : e.getMessage();
        }
    }

    static Validator compile(String pattern) {
        Object v = compile(pattern, Duration.ofSeconds(1));
        if (v instanceof String) {
            fail(pattern + ": " + v);
        }
        return (Validator) v;
    }

    static void assertRefused(String pattern) {
        Object v = compile(pattern, Duration.ofSeconds(1));
        assertEquals("Invalid regular expression '" + pattern + "' in pattern.", v, pattern);
    }

    /** Whether the text matches. A match that reaches its limit is not an answer, and fails the test. */
    static boolean answer(Validator v, String text) {
        Boolean b = v.tryValidate(text);
        assertNotNull(b, v.re.pattern() + " on " + text + ": the match reached its limit");
        return b;
    }

    /** Every text of up to three characters over a, x and -, and seven longer ones: 47 texts. */
    static List<String> texts() {
        List<String> all = new ArrayList<>();
        all.add("");
        List<String> last = new ArrayList<>(all);
        for (int k = 0; k < 3; k++) {
            List<String> next = new ArrayList<>();
            for (String t : last) {
                for (String c : new String[] {"a", "x", "-"}) {
                    next.add(t + c);
                }
            }
            all.addAll(next);
            last = next;
        }
        all.addAll(Arrays.asList("aaaa", "xaxa", "aaax", "xxxa", "a-a-", "ax-ax-", "aa-xx-a"));
        return all;
    }

    /** The answers of a pattern on the texts, one T or F each. */
    static String answers(Validator v, List<String> texts) {
        StringBuilder out = new StringBuilder();
        for (String text : texts) {
            out.append(answer(v, text) ? 'T' : 'F');
        }
        return out.toString();
    }

    /** Checks each pattern against its answers, one T or F for each of {@link #texts()}. */
    static void check(Object[][] cases) {
        List<String> texts = texts();
        List<String> wrong = new ArrayList<>();
        for (Object[] c : cases) {
            String pattern = (String) c[0];
            String want = (String) c[1];
            assertEquals(texts.size(), want.length(), pattern);
            Validator v = compile(pattern);
            for (int k = 0; k < texts.size(); k++) {
                if (answer(v, texts.get(k)) != (want.charAt(k) == 'T')) {
                    wrong.add(pattern + " on \"" + texts.get(k) + "\": want " + want.charAt(k));
                }
            }
        }
        assertTrue(wrong.isEmpty(), wrong.size() + " wrong:\n" + String.join("\n", wrong));
    }

    /** Checks each pattern against the texts it matches and the texts it does not. */
    static void checkTexts(Object[][] cases) {
        List<String> wrong = new ArrayList<>();
        for (Object[] c : cases) {
            String pattern = (String) c[0];
            Validator v = compile(pattern);
            for (String text : (String[]) c[1]) {
                if (!answer(v, text)) {
                    wrong.add(pattern + " on \"" + text + "\": want true");
                }
            }
            for (String text : (String[]) c[2]) {
                if (answer(v, text)) {
                    wrong.add(pattern + " on \"" + text + "\": want false");
                }
            }
        }
        assertTrue(wrong.isEmpty(), wrong.size() + " wrong:\n" + String.join("\n", wrong));
    }

    static Object[] t(String pattern, String[] matches, String[] fails) {
        return new Object[] {pattern, matches, fails};
    }

    static String[] s(String... texts) {
        return texts;
    }

    @Test
    void aRepetitionInsideARepetitionKeepsItsCount() {
        check(REPETITIONS);
    }

    @Test
    void aReferenceToAGroupOfARepetitionReadsTheLastIteration() {
        check(REFERENCES_TO_A_REPETITION);
    }

    @Test
    void aRepetitionInsideARepetitionInALookbehind() {
        check(REPETITIONS_IN_A_LOOKBEHIND);
    }

    @Test
    void aRepetitionInsideARepetitionWithoutTheUFlag() {
        check(REPETITIONS_WITHOUT_THE_U_FLAG);
    }

    @Test
    void aReferenceFromInsideItsGroupMatchesNothing() {
        check(REFERENCES_FROM_INSIDE_THE_GROUP);
    }

    @Test
    void aReferenceToANameOfTwoGroupsReadsTheOneThatMatched() {
        check(GROUPS_WITH_ONE_NAME);
    }

    /** An assertion takes no quantifier. regress allows one on \b and \B. */
    @Test
    void aQuantifierOnAWordBoundaryIsNotValid() {
        for (String pattern : s("\\b+", "\\B*", "a\\b?", "^\\b{2}a", "\\B{1,}?x", "(a\\b*)", "[a]\\b+\\&", "(?=a\\B+)")) {
            assertRefused(pattern);
            assertFalse(Regex.isValid(pattern, "u"), pattern);
        }
        // A quantifier on a group that holds one, an escaped backslash before a b, and a backspace in a class.
        String[][] valid = {{"(?:\\b)+a", "a"}, {"\\\\b+", "\\bb"}, {"[\\b+]", "+"}, {"a\\b", "a"}, {"\\b{", "a{"}};
        for (String[] c : valid) {
            assertTrue(compile(c[0]).isValid(c[1]), c[0] + " on " + c[1]);
        }
        for (String pattern : s("(?:\\b)+a", "\\\\b+", "[\\b+]", "a\\b")) {
            assertTrue(Regex.isValid(pattern, "u"), pattern);
        }
    }

    @Test
    void aCaseInsensitiveGroupComparesAsItsGrammarSays() {
        checkTexts(CASE_INSENSITIVE);
    }

    @Test
    void aPatternWithoutTheUFlagMatchesUtf16CodeUnits() {
        checkTexts(BY_CODE_UNIT);
    }

    @Test
    void aGroupNameBeyondTheBmpWithoutTheUFlag() {
        checkTexts(GROUP_NAMES_BEYOND_THE_BMP);
    }

    @Test
    void theEscapeOfALoneSurrogateWithTheUFlag() {
        checkTexts(LONE_SURROGATE_ESCAPES);
    }

    @Test
    void aCaseInsensitiveReferenceComparesAsItsGrammarSays() {
        checkTexts(CASE_INSENSITIVE_REFERENCES);
    }

    @Test
    void notAWordBoundaryBesideACharacterOfSeveralBytes() {
        checkTexts(NOT_A_WORD_BOUNDARY);
    }

    @Test
    void patternsAnotherEngineAnsweredWrongly() {
        checkTexts(ANSWERED_WRONGLY_ELSEWHERE);
    }

    /** A pattern ECMA-262 refuses in both grammars is refused, and one it accepts only without the u flag is not. */
    @Test
    void aPatternThatIsNotValidIsRefused() {
        for (String pattern : s("a{2,1}", "(?i)a", "\\b+", "a\\B{2}", "(?<\\u>a)", "(?<n>a)(?<n>b)", "[b-a]", "(?<=a)+",
                "\\k<n>(?<m>a)")) {
            assertTrue(compile(pattern, Duration.ofSeconds(1)) instanceof String, pattern);
        }
        for (String pattern : s("^\\u{110000}\\-?$", "^\\u{3}\\-?$", "\\k<n>\\-", "(?=a)+\\-?", "a{,2}", "\\c", "[\\c]",
                "\\8", "]")) {
            assertTrue(compile(pattern, Duration.ofSeconds(1)) instanceof Validator, pattern);
        }
    }

    /** Ten texts for the repetitions with a count of 300. */
    static List<String> longTexts() {
        return Arrays.asList(
                "a".repeat(300) + "-",
                "a".repeat(301) + "-",
                "ax".repeat(150) + "-" + "x".repeat(300) + "-",
                "ax".repeat(150) + "-" + "x".repeat(301) + "-",
                "a-".repeat(400),
                "a-".repeat(400) + "a",
                "ax-".repeat(200) + "a".repeat(299) + "x-",
                "a".repeat(600) + "-",
                "x".repeat(299) + "--",
                "-" + "a".repeat(10) + "-");
    }

    @Test
    void aRepetitionTooDeepToWriteInOneKeepsItsCount() {
        check(TOO_LARGE_TO_WRITE_OUT_ON_SHORT_TEXTS);
        check(TOO_LARGE_TO_WRITE_OUT);
        List<String> texts = longTexts();
        for (Object[] c : TOO_LARGE_TO_WRITE_OUT) {
            assertEquals(c[2], answers(compile((String) c[0]), texts), (String) c[0]);
        }
    }

    @Test
    void aRepetitionTooDeepToWriteInOneWhoseBodyCanBeEmptyKeepsItsCount() {
        check(TOO_LARGE_TO_WRITE_OUT_WITH_A_BODY_THAT_CAN_BE_EMPTY);
        List<String> texts = longTexts();
        for (Object[] c : TOO_LARGE_WITH_A_BODY_THAT_CAN_BE_EMPTY_ON_LONG_TEXTS) {
            assertEquals(c[1], answers(compile((String) c[0]), texts), (String) c[0]);
        }
    }

    /** Twenty texts around a count of 300. */
    static List<String> countTexts() {
        return Arrays.asList(
                "ax" + "a".repeat(599),
                "ax" + "a".repeat(600),
                "ax" + "a".repeat(601),
                "a-a" + "b".repeat(298) + "-",
                "a-a" + "b".repeat(299) + "-",
                "a-a" + "b".repeat(300) + "-",
                "a".repeat(299) + "-",
                "a".repeat(300) + "-",
                "a".repeat(301) + "-",
                "a".repeat(300) + "-" + "a".repeat(300) + "-",
                "a".repeat(300) + "-" + "a".repeat(301) + "-",
                "ab".repeat(300) + "-",
                "ab".repeat(301) + "-",
                "a".repeat(64) + "-",
                "a".repeat(65) + "-" + "b".repeat(129) + "-",
                "a".repeat(150) + "-a-a" + "b".repeat(150) + "-",
                "a-a" + "b".repeat(300) + "a-",
                "b".repeat(300) + "a-a-",
                "a".repeat(300),
                "a".repeat(301));
    }

    @Test
    void aRepetitionOfThreeHundredInsideARepetitionKeepsItsCount() {
        List<String> texts = countTexts();
        for (Object[] c : COUNTS) {
            assertEquals(c[1], answers(compile((String) c[0]), texts), (String) c[0]);
        }
    }

    @Test
    void manyIterationsOfACharacterThatMayBeLeftOutKeepTheirCount() {
        List<Object[]> shortCases = new ArrayList<>();
        for (Object[] c : A_CHARACTER_THAT_MAY_BE_LEFT_OUT) {
            if (!c[0].equals(NO_ENGINE_FINISHES)) {
                shortCases.add(new Object[] {c[0], c[1]});
            }
        }
        check(shortCases.toArray(new Object[0][]));
        List<String> longTexts = longTexts();
        Object[] unfinished = null;
        for (Object[] c : A_CHARACTER_THAT_MAY_BE_LEFT_OUT) {
            if (c[0].equals(NO_ENGINE_FINISHES)) {
                unfinished = c;
                continue;
            }
            if (!((String) c[2]).isEmpty()) {
                assertEquals(c[2], answers(compile((String) c[0]), longTexts), (String) c[0]);
            }
        }
        // The pattern no engine finishes: the right answer, or the error of the limit, and never a wrong answer.
        String pattern = (String) unfinished[0];
        Validator v = (Validator) compile(pattern, Duration.ofMillis(100));
        List<String> texts = new ArrayList<>(texts());
        texts.addAll(longTexts);
        String wants = (String) unfinished[1] + unfinished[2];
        int answered = 0;
        int abandoned = 0;
        for (int k = 0; k < texts.size(); k++) {
            Boolean got = v.tryValidate(texts.get(k));
            if (got != null) {
                assertEquals(wants.charAt(k) == 'T', got, pattern + " on " + texts.get(k));
                answered++;
            } else {
                assertFalse(v.isValid(texts.get(k)), "isValid is true for a match that reached its limit");
                abandoned++;
            }
        }
        assertTrue(answered > 0 && abandoned > 0, answered + " answered, " + abandoned + " abandoned");
    }

    /**
     * A pattern is never refused for what it would come to written another way. A count beyond the text there is
     * cannot match, and the matcher says so at once. Where the count can be met by iterations that match nothing, the
     * match is a search no engine finishes, and it ends at the limit of a match with an error, never with an answer.
     */
    @Test
    void aLargeRepetitionInsideARepetitionCompiles() {
        Object[][] cases = {
            {"^(?!-)(?:(?:a|x){4294967295}-)+$", "ax-", false},
            {"^(?!-)(?:(?:(?:a|x){200}-|a){200}x)+$", "ax-", false},
            {"^(?!-)(?:(?:(?:a|x){1,300}|){1,300}-)+$", "ax-xxa-", true},
            {"^(?!-)(?:(?:(?:a|x){1,300}|){1,300}-)+$", "ax-xxa", false},
            {"^(?!-)(?:(?:a|x|){4294967295}-)+$", "ax-", null},
            {"^(?!-)(?:(?:(?:a|x|){200}-|a?){200}x)+$", "ax-", null},
            {"^(?!-)(?:(?:a|x|){100000}-)+$", "ax-", null},
        };
        for (Object[] c : cases) {
            String pattern = (String) c[0];
            Object compiled = compile(pattern, Duration.ofMillis(200));
            assertTrue(compiled instanceof Validator, pattern + ": " + compiled);
            Validator v = (Validator) compiled;
            Boolean got = v.tryValidate((String) c[1]);
            if (c[2] != null) {
                assertEquals(c[2], got, pattern + " on " + c[1]);
            } else if (got == null) {
                // Whatever the engine comes to within the limit is an answer, and the limit is an error.
                assertFalse(v.isValid((String) c[1]), pattern + ": isValid is true for a match that reached its limit");
            }
        }
        // A count as large on a body that cannot be come back into.
        String[][] plain = {
            {"^(?!-)(?:[ax]{1,300}-)+$", "ax-xxa-"},
            {"^(?!-)(?:(?:ax){300}-)+$", "ax".repeat(300) + "-"},
            {"^(?!-)(?:(?:a|x){1,200}-)+$", "ax-xxa-"},
        };
        for (String[] c : plain) {
            Validator v = compile(c[0]);
            assertTrue(answer(v, c[1]), c[0]);
            assertFalse(answer(v, "ax-xxa"), c[0]);
        }
    }

    /**
     * \k in a class is the letter in a pattern with no named group, and is not valid in one with a named group, with
     * the u flag or without it.
     */
    @Test
    void kInAClassIsNotValidInAPatternWithANamedGroup() {
        for (String pattern : s("(?<n>a)[\\k]", "[\\k](?<n>a)", "(?<n>a)[\\k<a>]", "[\\k<a>](?<n>a)", "(?<n>a)^[\\k<a>]$",
                "^[\\k<a>]$(?<n>a)", "(?<a>x)[\\k]", "(?<a>x)[\\k<a>]", "(?<n>a)[^\\k]", "(?<n>a)[a-\\k]",
                "(?<n>a)[\\cK\\k]", "(?<n>a)\\[\\k]")) {
            assertRefused(pattern);
        }
        checkTexts(new Object[][] {
            t("^[\\k]$", s("k"), s("", "\\", "kk", "a")),
            t("^[\\k<a>]+$", s("k", "<a>", "ka"), s("", "\\", "n")),
            // The backslash is the member, and the k after it another.
            t("^(?<n>a)[\\\\k]$", s("ak", "a\\"), s("a", "k", "aa")),
            // No named group: the class holds the text of one.
            t("^[(?<n>]\\k$", s("nk", "(k", "<k"), s("k", "n", "ak")),
        });
    }

    /** The two numbers of a count are compared as they are written, however long. */
    @Test
    void theNumbersOfACountAreComparedAsTheyAreWritten() {
        for (String pattern : s("a{99999999999999999999,99999999999999999998}", "a{2147483648,2147483647}",
                "a{4294967296,4294967295}", "a{00000000000000000000000002,1}", "a{010,9}", "a{3,2}\\-",
                "x{1,2}{99999999999999999999,99999999999999999998}", "^{99999999999999999999,99999999999999999998}",
                "(?:a|b{99999999999999999999,99999999999999999998})", "(?<=a{99999999999999999999,99999999999999999998})",
                "\\-{99999999999999999999,99999999999999999998}")) {
            assertRefused(pattern);
        }
        checkTexts(new Object[][] {
            t("^a{9,010}$", s("aaaaaaaaa", "aaaaaaaaaa"), s("aaaaaaaa", "aaaaaaaaaaa")),
            t("^a{00000000000000000000000001,2}$", s("a", "aa"), s("", "aaa")),
            t("^a{99999999999999999998,99999999999999999999}$", s(), s("", "a", "aaaa")),
            t("^a{0,99999999999999999999}$", s("", "a", "aaaa"), s("b")),
            // In a class, after a backslash and without its closing brace it is no count.
            t("^[{99999999999999999999,99999999999999999998}]+$", s("{9,8}", "9"), s("", "a")),
            t("^\\{3,2}$", s("{3,2}"), s("", "{{{")),
            t("^a{3,2$", s("a{3,2"), s("", "aaa")),
        });
    }

    /**
     * The Script value Hrkt (Katakana_Or_Hiragana) is in Unicode's list of property value aliases, so ECMA-262 takes
     * it, for Script and for Script_Extensions. No character has it, so it matches nothing and its negation matches
     * every character.
     */
    @Test
    void theScriptNoCharacterHas() {
        String[] all = {"\u3042", "\u30ab", "\u30fc", "a", "\uD83D\uDE00"};
        for (String name : s("Script", "sc", "Script_Extensions", "scx")) {
            for (String value : s("Hrkt", "Katakana_Or_Hiragana")) {
                java.util.function.UnaryOperator<String> p = text -> text.replace("NAME", name).replace("VALUE", value);
                checkTexts(new Object[][] {
                    t(p.apply("^\\p{NAME=VALUE}$"), s(), all),
                    t(p.apply("^\\P{NAME=VALUE}$"), all, s("", "aa")),
                    t(p.apply("^[\\p{NAME=VALUE}a]$"), s("a"), s("\u3042", "\u30ab", "")),
                    t(p.apply("^[^\\p{NAME=VALUE}a]$"), s("\u3042", "\u30ab", "b"), s("a", "")),
                    t(p.apply("^[\\P{NAME=VALUE}]+$"), s("\u3042a\u30ab"), s("")),
                    t(p.apply("^[^\\P{NAME=VALUE}]$"), s(), all),
                    t(p.apply("^(?i:\\P{NAME=VALUE})\\p{NAME=VALUE}*$"), all, s("", "aa")),
                    // After a backslash that is a character it is text, and the pattern is valid only without the
                    // flag.
                    t(p.apply("^\\\\p{NAME=VALUE}$"), s(p.apply("\\p{NAME=VALUE}")), s("", "a")),
                });
            }
        }
        // A name or a value that is not in the list is not valid with the flag, and is the text without it.
        checkTexts(new Object[][] {
            t("^\\p{sc=hrkt}$", s("p{sc=hrkt}"), s("", "a", "\u3042")),
            t("^\\p{gc=Hrkt}$", s("p{gc=Hrkt}"), s("", "a", "\u3042")),
            t("^\\p{Hrkt}$", s("p{Hrkt}"), s("", "a", "\u3042")),
        });
    }

    /** Where V8 13.6 and ECMA-262 differ in what is a pattern, the answer is ECMA-262's. */
    @Test
    void whatIsAPatternIsWhatEcma262Says() {
        checkTexts(new Object[][] {
            t("^\\p{WSpace}$", s("p{WSpace}"), s(" ", "", "\t")),
            t("^\\p{White_Space}$", s(" ", "\t"), s("p{White_Space}", "")),
            t("^\\p{space}$", s(" ", "\t"), s("p{space}", "")),
            t("^(?:(?<m>a)|(?<m>b))\\k<m>$", s("aa", "bb"), s("ab", "a", "")),
            t("^\\p{sc=Sidt}$", s("\uD802\uDD40"), s("a", "p{sc=Sidt}")),
            t("^\\p{Script=Sidetic}$", s("\uD802\uDD40"), s("a", "")),
            t("^\\p{sc=Tols}+$", s("\uD807\uDDB0\uD807\uDDB1"), s("a", "")),
            t("^\\p{scx=Tolong_Siki}$", s("\uD807\uDDB0"), s("a", "")),
            t("^\\p{sc=Berf}$", s("\uD81B\uDEA0"), s("a", "")),
            t("^[\\p{Script_Extensions=Beria_Erfe}]$", s("\uD81B\uDEA0"), s("a", "")),
            t("^\\p{sc=Tayo}$", s("\uD839\uDEC0"), s("a", "")),
            t("^\\P{sc=Tai_Yo}$", s("a", "\uD802\uDD40"), s("\uD839\uDEC0", "")),
        });
        for (String pattern : s("(|((?<m>))(?<m>|))", "(?<m>a)(?<m>b)", "(?:(?<m>a)|b)(?<m>c)", "((?<m>a)|b)+(?<m>c)")) {
            assertRefused(pattern);
        }
    }
}
