package io.github.corvusdotnet.regex.ecma262;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The limits on a regular expression (limits.rs). A pattern and a text may both come from outside, so neither may be
 * able to make a program hang or hold memory out of proportion to its input. A match asked for with a limit of time
 * ends at that limit, and with it at a cap on the memory of its backtracking; one that reaches it is an error, never a
 * match and never no match. A pattern has limits of size, and one over them is refused with an error that is not the
 * one of a pattern that is not valid. No test here depends on a quiet machine.
 */
class LimitsTest {
    /** A time no test should come near. */
    static final Duration GENEROUS = Duration.ofSeconds(20);

    /** A group read back inside a repetition, which nothing but the limit ends on {@link #neverAnswered()}. */
    static final String NEVER_ANSWERS = "^(?:(a+)\\1*)+$";

    static String neverAnswered() {
        return "a".repeat(60) + "b";
    }

    @Test
    void aMatchThatReachesItsLimitIsAnError() {
        Duration limit = Duration.ofMillis(50);
        Regex re = Regex.compile(NEVER_ANSWERS, "u");
        String text = neverAnswered();
        long start = System.nanoTime();
        MatchLimitException e = assertThrows(MatchLimitException.class, () -> re.tryIsMatch(text, limit));
        Duration took = Duration.ofNanos(System.nanoTime() - start);
        assertEquals(MatchLimitException.Kind.TIME, e.kind());
        assertTrue(e.getMessage().contains("longer than its limit"));
        assertTrue(took.compareTo(limit) >= 0 && took.compareTo(limit.plus(GENEROUS)) < 0, "abandoned after " + took);
        // Every member with a limit, in both encodings.
        Utf8Text t8 = Utf8Text.of(text);
        assertThrows(MatchLimitException.class, () -> re.tryIsMatch(t8, limit));
        assertThrows(MatchLimitException.class, () -> re.tryIsMatch(text.getBytes(), limit));
        assertThrows(MatchLimitException.class, () -> re.tryFind(text, 0, limit));
        assertThrows(MatchLimitException.class, () -> re.tryFind(t8, 0, limit));
        assertThrows(MatchLimitException.class, () -> re.tryFind(text.getBytes(), 0, limit));
        assertThrows(MatchLimitException.class, () -> re.tryCaptures(text, 0, limit));
        assertThrows(MatchLimitException.class, () -> re.tryCaptures(t8, 0, limit));
        assertThrows(MatchLimitException.class, () -> re.tryCaptures(text.getBytes(), 0, limit));
    }

    @Test
    void theMatchLimitIsForEachMatch() {
        Regex re = Regex.compile(NEVER_ANSWERS, "u");
        for (int k = 0; k < 3; k++) {
            assertTrue(re.tryIsMatch("aaaa", Duration.ofMillis(50)));
        }
        assertEquals("aaaa", re.tryFind("aaaa", 0, Duration.ofMillis(50)).value());
        assertEquals("aaaa", re.tryCaptures(Utf8Text.of("aaaa"), 0, Duration.ofMillis(50)).group(0));
        assertEquals(null, re.tryCaptures("b", 0, Duration.ofMillis(50)));
        assertEquals(null, re.tryFind(Utf8Text.of("b"), 0, Duration.ofMillis(50)));
    }

    @Test
    void aMatchThatReachesItsMemoryLimitIsAnError() {
        Duration hour = Duration.ofHours(1);
        Regex re = Regex.compile("(?:a?){2147483647}", "u");
        long start = System.nanoTime();
        MatchLimitException e = assertThrows(MatchLimitException.class, () -> re.tryIsMatch("", hour));
        assertEquals(MatchLimitException.Kind.MEMORY, e.kind());
        assertTrue(e.getMessage().contains("memory"));
        assertTrue(System.nanoTime() - start < GENEROUS.toNanos());
        // With no limit of time there is none of memory either, and the match is the engine's to finish: this one
        // gives up at once, because the text is shorter than the least the pattern consumes.
        Regex least = Regex.compile("(?:a){2147483647}", "u");
        assertFalse(least.isMatch("aaa"));
        assertFalse(least.tryIsMatch("aaa", Duration.ZERO));
        assertFalse(least.tryIsMatch("aaa", Duration.ofSeconds(-1)));
        assertFalse(least.tryIsMatch("aaa", Duration.ofSeconds(Long.MAX_VALUE)));
    }

    @Test
    void anOrdinaryPatternIsNotAffectedByTheLimit() {
        String longText = "ab".repeat(20000) + "é";
        Object[][] cases = {
            {"^[a-z]+$", "abc", true},
            {"^[a-z]+$", "abc1", false},
            {"^x-", "x-a", true},
            {"^\\p{L}+$", "café", true},
            {"^\\p{L}+$", "café ", false},
            {"^(?=.*\\d)\\w+$", "abc1", true},
            {"^(?=.*\\d)\\w+$", "abcd", false},
            {"^(a+)\\1$", "aaaa", true},
            {"^(a+)\\1$", "aaa", false},
            {"(?<=\\$)\\d+", "cost $42", true},
            {"^(?:[a-z]+\\.)+[a-z]+$", "a.b.c", true},
            {"^[abé]*c$", longText, false},
        };
        for (Object[] c : cases) {
            String pattern = (String) c[0];
            String text = (String) c[1];
            boolean want = (Boolean) c[2];
            Regex re = Regex.compile(pattern, "u");
            for (Duration limit : new Duration[] {Duration.ofSeconds(1), Duration.ofSeconds(10), Duration.ZERO}) {
                assertEquals(want, re.tryIsMatch(text, limit), pattern + " with the limit " + limit);
                assertEquals(want, re.tryIsMatch(Utf8Text.of(text), limit), pattern + " over UTF-8");
            }
            assertEquals(want, re.isMatch(text), pattern);
        }
    }

    /** A search may not read a text from every position for each position. Each of these answers, or ends at the limit. */
    @Test
    void noSearchReadsTheTextFromEveryPosition() {
        String items = "ab,".repeat(33333);
        Duration limit = Duration.ofMillis(200);
        String[][] cases = {
            {"[a-z]{50000}[0-9]|x", "a".repeat(100000)},
            {"^(?:[a-z]+,)*[a-z,]+[0-9]$", items},
            {"^(?:[a-z]+,)*[a-z]{2}[0-9]$", items},
            {"^(?:[a-zé]+,)*[a-z]{2}[0-9]$", "é," + items},
        };
        for (String[] c : cases) {
            Regex re = Regex.compile(c[0], "u");
            long start = System.nanoTime();
            boolean answered;
            boolean result = false;
            try {
                result = re.tryIsMatch(c[1], limit);
                answered = true;
            } catch (MatchLimitException e) {
                answered = false;
            }
            Duration took = Duration.ofNanos(System.nanoTime() - start);
            assertTrue((!answered || !result) && took.compareTo(limit.plus(GENEROUS)) < 0,
                    c[0] + ": " + (answered ? result : "the limit") + " after " + took);
        }
    }

    // -----------------------------------------------------------------------------------------------------------------
    // The limits of size.

    static String nest(int depth) {
        return "(?:".repeat(depth) + "a" + ")".repeat(depth);
    }

    static String escape(int zeros) {
        return "\\u{" + "0".repeat(zeros) + "41}";
    }

    static String classOf(int members) {
        return "[" + "ĀĂ".repeat(members) + "]";
    }

    @Test
    void aPatternOverALimitOfSizeIsRefused() {
        String sizeReason = "the pattern has a size of more than 1048576 bytes when it is read";
        Object[][] cases = {
            {"length", escape((1 << 20) - 6), escape((1 << 20) - 5), Limit.LENGTH, 1 << 20,
                "the pattern is longer than 1048576 bytes"},
            {"nesting", nest(999), nest(1000), Limit.NESTING, 1000, "the groups of the pattern nest more than 1000 deep"},
            {"size", "a".repeat(16384), "a".repeat(16385), Limit.SIZE, 1 << 20, sizeReason},
            {"size of a class", classOf(60000), classOf(70000), Limit.SIZE, 1 << 20, sizeReason},
            {"nesting, and not valid", nest(999), nest(1000) + "*+)", Limit.NESTING, 1000,
                "the groups of the pattern nest more than 1000 deep"},
        };
        for (Object[] c : cases) {
            String name = (String) c[0];
            long start = System.nanoTime();
            try {
                Regex.compile((String) c[1], "u");
            } catch (RegexException e) {
                fail(name + ": the pattern at the limit: " + e.getMessage());
            }
            RegexException e = assertThrows(RegexException.class, () -> Regex.compile((String) c[2], "u"), name);
            assertTrue(System.nanoTime() - start < GENEROUS.toNanos(), name);
            assertEquals(RegexException.Kind.TOO_LARGE, e.kind(), name);
            assertEquals(c[3], e.limit(), name);
            assertEquals((int) (Integer) c[4], e.limitValue(), name);
            assertEquals(c[5], e.getMessage(), name);
            assertFalse(Regex.isValid((String) c[2], "u"), name);
        }
    }

    @Test
    void aPatternThatIsNotValidIsNotTooLarge() {
        String[] patterns = {"(", "a**", "[b-a]", "(?<n>a)\\k<m>", "(?:".repeat(999) + "a" + ")".repeat(1000)};
        for (String pattern : patterns) {
            for (String flags : new String[] {"", "u"}) {
                RegexException e = assertThrows(RegexException.class, () -> Regex.compile(pattern, flags));
                assertEquals(RegexException.Kind.INVALID, e.kind(), pattern.substring(0, Math.min(20, pattern.length())));
            }
        }
    }
}
