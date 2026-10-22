package io.github.corvusdotnet.regex.ecma262;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Test;

/**
 * A match allocates nothing once the thread's working memory has grown to what the pattern and the text need: not
 * isMatch, not a global search through a {@link Matches}, and not a read of the groups into a {@link Captures}
 * (allocations.rs). Only the allocations of the thread that measures are counted, so a thread of the test harness
 * cannot make it fail. Surefire runs it with escape analysis off as well, so that the answer does not depend on the JIT
 * taking allocations away.
 */
class AllocationTest {
    private static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    /**
     * The bytes the thread allocates in a window of runs of the work in the steady state. The work is run many times
     * first, so that the JIT has compiled it. The JVM allocates on this thread once in a while for its own purposes (it
     * loads a class when newly compiled code first reaches a reference to it), which the windows after it do not
     * repeat; code that allocates does so in every window, so the least of three windows is the answer.
     */
    static long allocations(Runnable work) {
        for (int r = 0; r < 300; r++) {
            work.run();
        }
        // What the counter itself allocates, measured alone and subtracted.
        long overhead = -THREADS.getCurrentThreadAllocatedBytes() + THREADS.getCurrentThreadAllocatedBytes();
        long bytes = Long.MAX_VALUE;
        for (int window = 0; window < 3 && bytes > 0; window++) {
            long before = THREADS.getCurrentThreadAllocatedBytes();
            for (int r = 0; r < 50; r++) {
                work.run();
            }
            bytes = THREADS.getCurrentThreadAllocatedBytes() - before - overhead;
        }
        return bytes;
    }

    @Test
    void aWarmMatchAllocatesNothing() {
        String text = "The year 2026, the month 10; Ünïcödé text, 😀 and more letters after it. "
                .repeat(20);
        Utf8Text text8 = Utf8Text.of(text);
        String[][] cases = {
            {"\\d+", ""},
            {"(?<y>\\d{4})\\D+(\\d+)", "u"},
            {"text|month", "i"},
            {"(?<=the )\\w+", "u"},
            {"(t|e)\\1", ""},
            {"\\p{L}+", "u"},
            {"[\\p{L}--[a-z]]+", "v"},
            {"^.*$", "m"},
            {"\\bt\\w*\\b", "gi"},
        };
        for (String[] c : cases) {
            // With no timeout, and with one, whose clock is read as the match goes.
            for (java.time.Duration timeout : new java.time.Duration[] {java.time.Duration.ZERO,
                java.time.Duration.ofSeconds(60)}) {
                Regex re = Regex.compile(c[0], c[1], timeout);
                String where = "/" + c[0] + "/" + c[1] + " with a timeout of " + timeout;
                Matches m16 = re.matches(text);
                Matches m8 = re.matches(text8);
                Captures caps = re.newCaptures();
                long n = allocations(() -> {
                    assertTrue(re.isMatch(text));
                    assertTrue(re.isMatch(text8));
                    assertTrue(m16.reset(text).count() > 0);
                    assertTrue(m8.reset(text8).count() > 0);
                });
                assertEquals(0, n, where + ": " + n + " bytes in 50 runs");
                n = allocations(() -> {
                    m16.reset(text);
                    while (m16.next(caps)) {
                        assertTrue(caps.start(0) >= 0);
                    }
                    m8.reset(text8);
                    while (m8.next(caps)) {
                        assertTrue(caps.start(0) >= 0);
                    }
                    assertTrue(re.captures(caps, text, 0));
                    assertTrue(re.captures(caps, text8, 0));
                });
                assertEquals(0, n, where + ": " + n + " bytes in 50 runs reading the groups");
            }
        }
    }
}
