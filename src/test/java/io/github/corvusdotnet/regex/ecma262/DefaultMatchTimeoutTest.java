package io.github.corvusdotnet.regex.ecma262;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The process-wide default of the match timeout. Surefire runs the test classes one after another in one JVM, and this
 * test puts the default back however it ends, so no other test compiles a pattern while it has a default set.
 */
class DefaultMatchTimeoutTest {
    @Test
    void aPatternTakesTheDefaultTimeoutWhenItIsCompiled() {
        assertEquals(Duration.ZERO, Regex.defaultMatchTimeout());
        Regex before = Regex.compile("^(?:(a+)\\1*)+$", "u");
        Regex re;
        Regex flagged;
        Regex own;
        Regex none;
        try {
            Regex.setDefaultMatchTimeout(Duration.ofMillis(30));
            assertEquals(Duration.ofMillis(30), Regex.defaultMatchTimeout());
            re = Regex.compile("^(?:(a+)\\1*)+$", "u");
            flagged = Regex.compile("^(?:(a+)\\1*)+$", Flags.parse("u"));
            own = Regex.compile("a", "", Duration.ofSeconds(5));
            none = Regex.compile("a", "", Duration.ZERO);
        } finally {
            Regex.setDefaultMatchTimeout(null);
        }
        assertEquals(Duration.ZERO, Regex.defaultMatchTimeout());
        String text = "a".repeat(60) + "b";
        assertEquals(Duration.ofMillis(30), re.matchTimeout());
        assertThrows(MatchLimitException.class, () -> re.isMatch(text));
        assertThrows(MatchLimitException.class, () -> flagged.isMatch(text));
        // A pattern compiled before keeps its own, and one given a timeout, or none, keeps that.
        assertEquals(Duration.ZERO, before.matchTimeout());
        assertEquals(Duration.ofSeconds(5), own.matchTimeout());
        assertEquals(Duration.ZERO, none.matchTimeout());
        try {
            Regex.setDefaultMatchTimeout(Duration.ofSeconds(Long.MAX_VALUE));
            assertEquals(Duration.ofNanos(Long.MAX_VALUE), Regex.defaultMatchTimeout());
        } finally {
            Regex.setDefaultMatchTimeout(Duration.ZERO);
        }
        assertEquals(Duration.ZERO, Regex.defaultMatchTimeout());
    }

    @Test
    void theSystemPropertyIsMillisecondsOrAnIsoDuration() {
        assertEquals(0, Regex.timeoutFromProperty(null));
        assertEquals(0, Regex.timeoutFromProperty(""));
        assertEquals(1_500_000_000L, Regex.timeoutFromProperty("1500"));
        assertEquals(2_000_000_000L, Regex.timeoutFromProperty("PT2S"));
        assertEquals(0, Regex.timeoutFromProperty("PT0S"));
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> Regex.timeoutFromProperty("one second"));
        assertEquals(true, e.getMessage().contains("corvus.regex.defaultMatchTimeout"));
    }
}
