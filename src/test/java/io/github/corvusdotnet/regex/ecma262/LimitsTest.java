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
        MatchLimitException e = assertThrows(MatchLimitException.class, () -> re.isMatch(text, limit));
        Duration took = Duration.ofNanos(System.nanoTime() - start);
        assertEquals(MatchLimitException.Kind.TIME, e.kind());
        assertTrue(e.getMessage().contains("longer than its limit"));
        assertTrue(took.compareTo(limit) >= 0 && took.compareTo(limit.plus(GENEROUS)) < 0, "abandoned after " + took);
        // Every member with a limit, in both encodings.
        Utf8Text t8 = Utf8Text.of(text);
        assertThrows(MatchLimitException.class, () -> re.isMatch(t8, limit));
        assertThrows(MatchLimitException.class, () -> re.isMatch(text.getBytes(), limit));
        assertThrows(MatchLimitException.class, () -> re.find(text, 0, limit));
        assertThrows(MatchLimitException.class, () -> re.find(t8, 0, limit));
        assertThrows(MatchLimitException.class, () -> re.find(text.getBytes(), 0, limit));
        assertThrows(MatchLimitException.class, () -> re.captures(text, 0, limit));
        assertThrows(MatchLimitException.class, () -> re.captures(t8, 0, limit));
        assertThrows(MatchLimitException.class, () -> re.captures(text.getBytes(), 0, limit));
    }

    @Test
    void theMatchLimitIsForEachMatch() {
        Regex re = Regex.compile(NEVER_ANSWERS, "u");
        for (int k = 0; k < 3; k++) {
            assertTrue(re.isMatch("aaaa", Duration.ofMillis(50)));
        }
        assertEquals("aaaa", re.find("aaaa", 0, Duration.ofMillis(50)).value());
        assertEquals("aaaa", re.captures(Utf8Text.of("aaaa"), 0, Duration.ofMillis(50)).group(0));
        assertEquals(null, re.captures("b", 0, Duration.ofMillis(50)));
        assertEquals(null, re.find(Utf8Text.of("b"), 0, Duration.ofMillis(50)));
    }

    @Test
    void aMatchThatReachesItsMemoryLimitIsAnError() {
        Duration hour = Duration.ofHours(1);
        Regex re = Regex.compile("(?:a?){2147483647}", "u");
        long start = System.nanoTime();
        MatchLimitException e = assertThrows(MatchLimitException.class, () -> re.isMatch("", hour));
        assertEquals(MatchLimitException.Kind.MEMORY, e.kind());
        assertTrue(e.getMessage().contains("memory"));
        assertTrue(System.nanoTime() - start < GENEROUS.toNanos());
        // With no limit of time there is none of memory either, and the match is the engine's to finish: this one
        // gives up at once, because the text is shorter than the least the pattern consumes.
        Regex least = Regex.compile("(?:a){2147483647}", "u");
        assertFalse(least.isMatch("aaa"));
        assertFalse(least.isMatch("aaa", Duration.ZERO));
        assertFalse(least.isMatch("aaa", Duration.ofSeconds(-1)));
        assertFalse(least.isMatch("aaa", Duration.ofSeconds(Long.MAX_VALUE)));
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
                assertEquals(want, re.isMatch(text, limit), pattern + " with the limit " + limit);
                assertEquals(want, re.isMatch(Utf8Text.of(text), limit), pattern + " over UTF-8");
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
                result = re.isMatch(c[1], limit);
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

    // -----------------------------------------------------------------------------------------------------------------
    // The match timeout of a pattern.

    static void assertTime(org.junit.jupiter.api.function.Executable member, String name) {
        MatchLimitException e = assertThrows(MatchLimitException.class, member, name);
        assertEquals(MatchLimitException.Kind.TIME, e.kind(), name);
    }

    /**
     * Every member that matches gives its match the pattern's timeout, and throws at it, over a String, UTF-8 text and
     * bytes: the global searches end there, and replace and split throw for the whole call.
     */
    @Test
    void everyMemberThrowsAtTheTimeout() {
        Duration timeout = Duration.ofMillis(30);
        Regex re = Regex.compile(NEVER_ANSWERS, "u", timeout);
        assertEquals(timeout, re.matchTimeout());
        String text = neverAnswered();
        Utf8Text t8 = Utf8Text.of(text);
        byte[] b = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        long start = System.nanoTime();
        assertTime(() -> re.isMatch(text), "isMatch");
        assertTime(() -> re.isMatch(text, 0), "isMatch at");
        assertTime(() -> re.find(text), "find");
        assertTime(() -> re.find(text, 0), "find at");
        assertTime(() -> re.captures(text), "captures");
        assertTime(() -> re.captures(text, 0), "captures at");
        assertTime(() -> re.captures(re.newCaptures(), text, 0), "captures into");
        assertTime(() -> re.findAll(text).iterator().hasNext(), "findAll");
        assertTime(() -> re.capturesAll(text).iterator().next(), "capturesAll");
        assertTime(() -> re.matches(text).next(), "matches");
        assertTime(() -> re.matches(text).next(re.newCaptures()), "matches with groups");
        assertTime(() -> re.matches(text).count(), "matches count");
        assertTime(() -> re.replace(text, "x"), "replace");
        assertTime(() -> re.replaceAll(text, "x"), "replaceAll");
        assertTime(() -> re.replace(text, c -> ""), "replace with a function");
        assertTime(() -> re.split(text), "split");
        assertTime(() -> re.split(text, 2), "split with a limit");
        assertTime(() -> re.isMatch(t8), "isMatch over UTF-8");
        assertTime(() -> re.isMatch(t8, 0), "isMatch at over UTF-8");
        assertTime(() -> re.find(t8), "find over UTF-8");
        assertTime(() -> re.captures(t8), "captures over UTF-8");
        assertTime(() -> re.captures(re.newCaptures(), t8, 0), "captures into over UTF-8");
        assertTime(() -> re.findAll(t8).iterator().next(), "findAll over UTF-8");
        assertTime(() -> re.capturesAll(t8).iterator().hasNext(), "capturesAll over UTF-8");
        assertTime(() -> re.matches(t8).next(), "matches over UTF-8");
        assertTime(() -> re.replace(t8, "x"), "replace over UTF-8");
        assertTime(() -> re.replaceAll(t8, "x"), "replaceAll over UTF-8");
        assertTime(() -> re.replace(t8, c -> ""), "replace with a function over UTF-8");
        assertTime(() -> re.split(t8), "split over UTF-8");
        assertTime(() -> re.isMatch(b), "isMatch over bytes");
        assertTime(() -> re.find(b, 0), "find over bytes");
        assertTime(() -> re.captures(b), "captures over bytes");
        assertTime(() -> re.matches(b).next(), "matches over bytes");
        assertTime(() -> re.replace(b, "x"), "replace over bytes");
        assertTime(() -> re.split(b, 3), "split over bytes");
        assertTrue(System.nanoTime() - start < GENEROUS.toNanos());
        // A search that reached the timeout has ended.
        java.util.Iterator<Match> it = re.findAll(text).iterator();
        assertThrows(MatchLimitException.class, it::hasNext);
        assertFalse(it.hasNext());
        Matches m = re.matches(text);
        assertThrows(MatchLimitException.class, m::next);
        assertFalse(m.next());
        assertEquals(-1, m.start());
        // A text the pattern answers within the timeout is answered, by every member.
        assertTrue(re.isMatch("aaaa"));
        assertEquals("<aaaa>", re.replace("aaaa", "<$&>"));
        assertEquals(3, re.split("aaaa").size(), "the empty pieces and the group");
        assertEquals(1, re.matches("aaaa").count());
    }

    /** A timeout brings the cap on the memory of the backtracking with it. */
    @Test
    void aTimeoutCapsTheMemoryOfAMatch() {
        Regex re = Regex.compile("(?:a?){2147483647}", "", Duration.ofHours(1));
        long start = System.nanoTime();
        for (org.junit.jupiter.api.function.Executable member : new org.junit.jupiter.api.function.Executable[] {
            () -> re.isMatch(""), () -> re.matches("").next(), () -> re.split("")}) {
            MatchLimitException e = assertThrows(MatchLimitException.class, member);
            assertEquals(MatchLimitException.Kind.MEMORY, e.kind());
        }
        assertTrue(System.nanoTime() - start < GENEROUS.toNanos());
    }

    /** The members that take a Duration give one match a limit in place of the timeout, and zero is none. */
    @Test
    void aLimitOfItsOwnTakesThePlaceOfTheTimeout() {
        Regex re = Regex.compile(NEVER_ANSWERS, "u", Duration.ofHours(1));
        String text = neverAnswered();
        Duration shortLimit = Duration.ofMillis(30);
        assertTime(() -> re.isMatch(text, shortLimit), "isMatch within");
        assertTime(() -> re.find(text, 0, shortLimit), "find within");
        assertTime(() -> re.captures(text, 0, shortLimit), "captures within");
        assertTime(() -> re.isMatch(Utf8Text.of(text), shortLimit), "isMatch within over UTF-8");
        Regex quick = Regex.compile("^(a+)\\1$", "", Duration.ofNanos(1));
        assertTrue(quick.isMatch("aaaa", Duration.ZERO));
        for (Duration none : new Duration[] {null, Duration.ZERO}) {
            assertEquals(Duration.ZERO, Regex.compile("a", "", none).matchTimeout());
        }
        assertEquals(Duration.ZERO, Regex.compile("a").matchTimeout(), "no timeout unless one is set");
        assertEquals(Duration.ofSeconds(2), Regex.compile("a", Flags.parse("g"), Duration.ofSeconds(2)).matchTimeout());
    }
}
