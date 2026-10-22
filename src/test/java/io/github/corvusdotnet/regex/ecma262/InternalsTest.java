package io.github.corvusdotnet.regex.ecma262;

import static io.github.corvusdotnet.regex.ecma262.TestJson.array;
import static io.github.corvusdotnet.regex.ecma262.TestJson.get;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The tests of the engine's parts, ported from the Rust crate's tests.rs, which ports the tests of the Go package. The
 * answers they expect are V8's, in v8_cases.json, v8_oracle.json and size_cases.json (the Rust crate's tests/data).
 * The tests of the automaton and of the searches for where a match starts come with them, in step 2 of the port.
 */
class InternalsTest {
    static final int NODE_SIZE = 32;
    static final int RANGE_SIZE = 8;
    /** A time no test should come near, for the bounds that must not depend on a quiet machine. */
    static final Duration GENEROUS = Duration.ofSeconds(20);

    // -----------------------------------------------------------------------------------------------------------------
    // Reading a pattern as JSON Schema reads one: the u flag grammar, and when that refuses it, the Annex B grammar.

    /** Compiles a pattern as JSON Schema reads one. */
    static Regex compile(String pattern) {
        try {
            return Regex.compile(pattern, "u");
        } catch (RegexException e) {
            if (e.kind() != RegexException.Kind.INVALID) {
                throw e;
            }
            return Regex.compile(pattern, "");
        }
    }

    static boolean compiles(String pattern) {
        try {
            compile(pattern);
            return true;
        } catch (RegexException e) {
            return false;
        }
    }

    /** Whether the pattern is valid with the u flag; a pattern over a limit of size is not. */
    static boolean valid(String pattern) {
        Boolean ok = Regex.onADeepStack(pattern, false, () -> {
            try {
                Parser.parse(pattern, true);
                return true;
            } catch (ParseFailure e) {
                return false;
            }
        });
        return ok != null && ok;
    }

    /** What reading the pattern as compile reads it tells: whether it took the u flag, and its size. */
    static int[] check(String pattern) {
        return Regex.onADeepStack(pattern, false, () -> {
            try {
                return new int[] {1, Parser.parse(pattern, true).parser.size};
            } catch (ParseFailure e) {
                if (e.isTooLarge()) {
                    throw RegexException.tooLarge(e.limit, e.value);
                }
            }
            try {
                return new int[] {0, Parser.parse(pattern, false).parser.size};
            } catch (ParseFailure e) {
                if (e.isTooLarge()) {
                    throw RegexException.tooLarge(e.limit, e.value);
                }
                throw RegexException.invalid(e.reason);
            }
        });
    }

    /** The program of a pattern with every counted repetition kept as a loop, which the tests of loops read. */
    static Program program(String pattern) {
        Compiler.noUnroll = true;
        try {
            return programUnrolled(pattern);
        } finally {
            Compiler.noUnroll = false;
        }
    }

    static Program programUnrolled(String pattern) {
        return Compiler.compileProgram(Parser.parse(pattern, true).root, true, -1);
    }

    /** Runs a test that walks a deep tree directly on a thread with the stack for it. */
    static void deep(Runnable test) {
        AtomicBoolean ran = new AtomicBoolean();
        Throwable[] failure = new Throwable[1];
        Thread t = new Thread(null, () -> {
            try {
                test.run();
                ran.set(true);
            } catch (Throwable e) {
                failure[0] = e;
            }
        }, "deep test", 64L << 20);
        t.start();
        join(t);
        rethrow(failure[0]);
        assertTrue(ran.get());
    }

    /** Runs a test on a thread with a small stack, as a thread of a host may have. */
    static void small(Runnable test) {
        Throwable[] failure = new Throwable[1];
        Thread t = new Thread(null, () -> {
            try {
                test.run();
            } catch (Throwable e) {
                failure[0] = e;
            }
        }, "small stack", 256L << 10);
        t.start();
        join(t);
        rethrow(failure[0]);
    }

    static void join(Thread t) {
        try {
            t.join();
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    static void rethrow(Throwable e) {
        if (e instanceof RuntimeException) {
            throw (RuntimeException) e;
        }
        if (e instanceof Error) {
            throw (Error) e;
        }
    }

    static String shortOf(String pattern) {
        return pattern.substring(0, Math.min(30, pattern.length()));
    }

    // -----------------------------------------------------------------------------------------------------------------
    // The sets of v8_cases.json: a pattern, whether it is valid with the u flag and with no flag, and its texts.

    static final Object CASES = TestJson.resource("v8_cases.json");

    static final class V8Case {
        String p;
        boolean u;
        boolean l;
        String ecma262;
        List<String> texts = new ArrayList<>();
        List<Boolean> wants = new ArrayList<>();
    }

    static List<V8Case> cases(String set) {
        List<Object> all = array(get(CASES, set));
        assertNotNull(all, "v8_cases.json has no set " + set);
        List<V8Case> out = new ArrayList<>();
        for (Object c : all) {
            V8Case v = new V8Case();
            v.p = (String) get(c, "p");
            v.u = (Boolean) get(c, "u");
            v.l = (Boolean) get(c, "l");
            Object e = get(c, "ecma262");
            v.ecma262 = e == null ? "" : (String) e;
            for (Object t : array(get(c, "tests"))) {
                v.texts.add((String) get(t, "t"));
                v.wants.add((Boolean) get(t, "want"));
            }
            out.add(v);
        }
        return out;
    }

    /** Compares the validity of a pattern with V8's; the pattern compiled, or null when it is not valid. */
    static Regex checkVerdict(String p, boolean validUnicode, boolean validLegacy) {
        assertEquals(validUnicode, valid(p), "valid(" + p + ")");
        Regex re;
        try {
            re = compile(p);
        } catch (RegexException e) {
            assertFalse(validUnicode || validLegacy, "compile(" + p + "): " + e.getMessage());
            return null;
        }
        assertTrue(validUnicode || validLegacy, "compile(" + p + ") compiles");
        assertEquals(validUnicode, re.unicode, "compile(" + p + ").unicode");
        assertArrayEquals(new int[] {validUnicode ? 1 : 0, re.size}, check(p), "check(" + p + ")");
        assertEquals(p, re.pattern());
        return re;
    }

    /** The backtracking matcher alone, over UTF-8. */
    static boolean matchBacktrack(Regex re, String text, Duration limit) {
        long found = Matcher8.exec(re.prog, text.getBytes(StandardCharsets.UTF_8), 0, false,
                limit == null ? 0 : limit.toNanos(), null, 0);
        return Regex.checked(found) >= 0;
    }

    /** The answer over UTF-8 with a limit, as the members give it. */
    static boolean matchLimit(Regex re, String text, Duration limit) {
        return re.isMatch(Utf8Text.of(text), limit == null ? Duration.ZERO : limit);
    }

    /**
     * Compares the answers of the pattern with V8's on one text: over UTF-8 and over UTF-16, on the backtracking
     * matcher alone, and with a limit of a nanosecond, where a match that reaches its first check is abandoned.
     */
    static void checkAnswer(Regex re, String text, boolean want, Duration limit) {
        String p = re.pattern();
        assertEquals(want, matchLimit(re, text, limit), p + " on " + text);
        assertEquals(want, re.isMatch(text, limit), p + " on " + text + " (UTF-16)");
        assertEquals(want, matchBacktrack(re, text, limit), p + " on " + text + " (the backtracking matcher)");
        try {
            assertEquals(want, matchLimit(re, text, Duration.ofNanos(1)), p + " on " + text + " (with a limit)");
        } catch (MatchLimitException e) {
            // Abandoned at its first check: no answer.
        }
    }

    static final class Counts {
        int answers;
        int matched;
        int legacy;
        int invalid;
    }

    /** Compares the validity of each pattern and its answers with V8's. A pattern has `limit` to answer its texts. */
    static Counts checkCases(List<V8Case> cases, Duration limit) {
        Counts counts = new Counts();
        for (V8Case c : cases) {
            Regex re = checkVerdict(c.p, c.u, c.l);
            if (re == null) {
                counts.invalid++;
                continue;
            }
            if (!c.u) {
                counts.legacy++;
            }
            long start = System.nanoTime();
            for (int k = 0; k < c.texts.size(); k++) {
                checkAnswer(re, c.texts.get(k), c.wants.get(k), limit);
                counts.answers++;
                counts.matched += c.wants.get(k) ? 1 : 0;
            }
            assertTrue(System.nanoTime() - start < limit.toNanos(),
                    c.p + " did not answer its " + c.texts.size() + " texts in " + limit);
        }
        return counts;
    }

    /** Matching by UTF-16 code unit, against V8. */
    @Test
    void codeUnits() {
        Counts c = checkCases(cases("units"), Duration.ofSeconds(60));
        assertTrue(c.legacy >= 90 && c.invalid >= 3 && c.matched >= 500, "the cases are not the ones this test is about");
    }

    /** Checks one of the sets of what is a pattern: the patterns, the invalid ones, and the departures of each kind. */
    static Object[] checkSyntax(String set) {
        List<V8Case> cases = cases(set);
        Counts c = checkCases(cases, Duration.ofSeconds(10));
        Map<String, Integer> departures = new HashMap<>();
        for (V8Case v : cases) {
            if (!v.ecma262.isEmpty()) {
                departures.merge(v.ecma262, 1, Integer::sum);
            }
        }
        return new Object[] {cases.size(), c.invalid, departures};
    }

    @SuppressWarnings("unchecked")
    static int departures(Object[] syntax, String kind) {
        return ((Map<String, Integer>) syntax[2]).getOrDefault(kind, 0);
    }

    @Test
    void escapedKInAClass() {
        Object[] s = checkSyntax("classK");
        assertTrue((Integer) s[0] >= 30 && (Integer) s[1] >= 9);
    }

    @Test
    void countsAreComparedAsWritten() {
        Object[] s = checkSyntax("counts");
        assertTrue((Integer) s[1] >= 20 && departures(s, "count-order") >= 10);
    }

    @Test
    void propertyNamesAndValues() {
        Object[] s = checkSyntax("properties");
        assertTrue(departures(s, "property-name") >= 3);
        assertTrue(departures(s, "script-value") >= 20);
    }

    @Test
    void aGroupNameUsedTwice() {
        Object[] s = checkSyntax("names");
        assertTrue((Integer) s[1] >= 15 && departures(s, "duplicate-name") >= 4);
    }

    @Test
    void repetitionsThatCannotConsume() {
        checkCases(cases("loops"), Duration.ofSeconds(60));
    }

    static int backrefs(Program p) {
        int n = 0;
        for (Inst i : p.insts) {
            n += i.op >= Inst.BACKREF && i.op <= Inst.BACKREF_UNITS_BACK ? 1 : 0;
        }
        return n;
    }

    /** The two rewrites of simplify, by the counted loops and the backreferences of the program. */
    @Test
    void simplify() {
        Object[][] table = {
            {"(?:\\b|(?=a)|){4,5}", 0, 0},
            {"(?:(?=(a))|){2}\\1", 0, 1},
            {"(?:(?=a))*b", 0, 0},
            {"(?:a|(?=b)){2,3}", 1, 0},
            {"(a\\1)+", 0, 0},
            {"(?:(a)|b\\1){2}", 1, 1},
            {"((\\1|\\2|)){3}", 0, 0},
            {"(?<n>a\\k<n>)", 0, 0},
            {"(?:(?<n>a)|(?<n>b\\k<n>)){2}", 1, 2},
            {"(?:(\\1){3}|(?!a)){2,}(?<=b)", 0, 0},
            {"(?:\\1(a)){2}", 1, 1},
        };
        for (Object[] t : table) {
            Program p = program((String) t[0]);
            assertEquals(t[1], p.loops, (String) t[0]);
            assertEquals(t[2], backrefs(p), (String) t[0]);
        }
    }

    @Test
    void loopsGiveUpWhenTheRestCannotFit() {
        List<V8Case> needs = cases("needs");
        Counts c = checkCases(needs, Duration.ofSeconds(60));
        assertTrue(needs.size() >= 20 && c.matched >= 100);
    }

    /** The least number of bytes a match consumes, for a program and for each counted loop and its iterations. */
    @Test
    void needsOfAProgram() {
        int none = (int) Compiler.NO_MATCH;
        Object[][] table = {
            {"abc", 3, new int[] {}, new int[] {}},
            {"a+b{2,}c?", 3, new int[] {}, new int[] {}},
            {"(?:a|bc)d", 2, new int[] {}, new int[] {}},
            {"(?=abc)a", 1, new int[] {}, new int[] {}},
            {"(?<=abc)d", 1, new int[] {}, new int[] {}},
            {"\\u00e9{2}\\u{10400}", 3, new int[] {}, new int[] {}},
            {"(?:a|){3}xy$", 2, new int[] {2}, new int[] {0}},
            {"(?:a|){3}", 0, new int[] {0}, new int[] {0}},
            {"(?:(?:a|){2}b){2}c", 3, new int[] {1, 2}, new int[] {1, 0}},
            {"(?=(?:a|){2}xyz)(?:b|){2}c", 1, new int[] {3, 1}, new int[] {0, 0}},
            {"(?<=(?:ab|){2}c)(?:d|){2}ef", 2, new int[] {0, 2}, new int[] {0, 0}},
            {"(?!(?:a|){2}xx)(?:(x)|){2}\\1y", 1, new int[] {2, 1}, new int[] {0, 0}},
            {"(?:a|){2}[]", none, new int[] {none}, new int[] {0}},
            {"(?:(?:a|){2}|bcd)e", 1, new int[] {1}, new int[] {0}},
            {"(?:a|b){5}c", 6, new int[] {1}, new int[] {1}},
            {"(?:ab|cde){3,}?x", 7, new int[] {1}, new int[] {2}},
            {"(?:a{1000}){1000}", 1000000, new int[] {0}, new int[] {1000}},
            {"(?:\\u00e9|xyz){2}", 4, new int[] {0}, new int[] {2}},
            {"(?:a|b){2147483647}c", none, new int[] {1}, new int[] {1}},
            {"(?:(?:a|bc){2}d){3}", 9, new int[] {0, 1}, new int[] {3, 1}},
            // A loop that cannot reach a match, with a count times a body over the cap: capped, never negative.
            {"(?:ab){2147483647}[]", none, new int[] {none}, new int[] {2}},
            {"(?:(?:ab){2147483647}[]|c)d", 2, new int[] {none}, new int[] {2}},
            // The body of a lookbehind is matched backwards and counts nothing.
            {"(?<=(?:ab|c){2}d)e", 1, new int[] {0}, new int[] {0}},
            {"(?:a|(?=(?:b|c){3}))x{2}", 2, new int[] {0}, new int[] {1}},
        };
        for (Object[] t : table) {
            String pattern = (String) t[0];
            Program p = program(pattern);
            List<Integer> loops = new ArrayList<>();
            List<Integer> iters = new ArrayList<>();
            for (Inst i : p.insts) {
                if (i.op == Inst.LOOP) {
                    loops.add(i.need);
                    iters.add(i.iter);
                }
            }
            assertEquals(t[1], p.need, pattern);
            assertArrayEquals((int[]) t[2], loops.stream().mapToInt(Integer::intValue).toArray(), pattern + " loops");
            assertArrayEquals((int[]) t[3], iters.stream().mapToInt(Integer::intValue).toArray(), pattern + " iterations");
        }
    }

    /** The matcher uses the least count of a counted loop, and gives up at once where it cannot be met. */
    @Test
    void aLoopGivesUpWhenItsIterationsCannotFit() {
        String text = "a".repeat(100000);
        for (String pattern : new String[] {"(?:a{1000}){1000}", "(?:a|b){2147483647}c", "(?=.)(?:(?:ab|a){500}){500}",
            "x?(?:[ab]c?){100001}$"}) {
            assertFalse(matchBacktrack(compile(pattern), text, Duration.ofMillis(200)), pattern);
        }
        Regex re = compile("^(?:a{10}){100}$");
        assertTrue(matchBacktrack(re, text.substring(0, 1000), Duration.ofSeconds(60)));
        assertFalse(matchBacktrack(re, text.substring(0, 999), Duration.ofSeconds(60)));
    }

    /** The two readers of a code unit on every position of a text, forwards and backwards. */
    @Test
    void codeUnitPositions() {
        byte[] text = "a\u00e9\u20ac\uD801\uDC00\uDBFF\uDFFFz".getBytes(StandardCharsets.UTF_8);
        int[] want = {0x61, 0xE9, 0x20AC, 0xD801, 0xDC00, 0xDBFF, 0xDFFF, 0x7A};
        int[] ends = new int[want.length];
        int pos = 0;
        for (int k = 0; k < want.length; k++) {
            int packed = Utf8.unitAt(text, pos);
            assertEquals(want[k], Utf8.rune(packed), "unitAt(" + pos + ")");
            pos += Utf8.width(packed);
            ends[k] = pos;
        }
        assertEquals(text.length, pos);
        for (int k = want.length - 1; k >= 0; k--) {
            int packed = Utf8.unitBefore(text, ends[k]);
            assertEquals(want[k], Utf8.rune(packed), "unitBefore(" + ends[k] + ")");
            if (k > 0) {
                assertEquals(ends[k - 1], ends[k] - Utf8.width(packed), "unitBefore(" + ends[k] + ") spans");
            }
        }
        // Text that is not UTF-8 is read a byte at a time, as it is when matching by code point.
        byte[][] bad = {{(byte) 0xf0, (byte) 0x9f}, {(byte) 0xf0, (byte) 0x9f, (byte) 0x98}, {(byte) 0x80, (byte) 0x80,
            (byte) 0x80}, {(byte) 0xf0, 'a', (byte) 0x80, (byte) 0x80}, {(byte) 0xf8, (byte) 0x88, (byte) 0x80,
            (byte) 0x80, (byte) 0x80}};
        for (byte[] b : bad) {
            for (int p = 0; p < b.length;) {
                int packed = Utf8.unitAt(b, p);
                int r = Utf8.rune(packed);
                assertTrue(Utf8.width(packed) == 1 && (r == 0xFFFD || r == (b[p] & 0xFF)), "unitAt " + p);
                p += Utf8.width(packed);
            }
            for (int p = b.length; p > 0;) {
                int packed = Utf8.unitBefore(b, p);
                int r = Utf8.rune(packed);
                assertTrue(Utf8.width(packed) == 1 && (r == 0xFFFD || r == (b[p - 1] & 0xFF)), "unitBefore " + p);
                p -= Utf8.width(packed);
            }
        }
    }

    // -----------------------------------------------------------------------------------------------------------------
    // The oracle: what V8 answers for a set of patterns and texts (gen_oracle.js of the Go package).

    static final Object ORACLE = TestJson.resource("v8_oracle.json");

    static List<String> strings(Object v) {
        List<String> out = new ArrayList<>();
        for (Object o : array(v)) {
            out.add((String) o);
        }
        return out;
    }

    static String text(Object v) {
        return v instanceof String ? (String) v : "";
    }

    static boolean bit(String hex, int k) {
        int digit = Character.digit(hex.charAt(k / 4), 16);
        return (digit >> (3 - k % 4) & 1) == 1;
    }

    /** The patterns for which V8 13.6 answers a case-insensitive modifier group differently from the i flag. */
    static final List<String> V8_MODIFIER_BUGS = List.of("^(?i:\\u212a)$", "^(?i:\\u017f)$", "^(?i:\\u1e9e)$",
            "^(?i:\\u03bc)$", "^(?i:\\u03bc)\\&?$", "(?i:\\Bs)");

    static int checkAnswers(Regex re, List<String> inputs, String bits) {
        for (int i = 0; i < inputs.size(); i++) {
            checkAnswer(re, inputs.get(i), bit(bits, i), GENEROUS);
        }
        return inputs.size();
    }

    static int checkHand(List<String> inputs) {
        int answers = 0;
        for (Object h : array(get(ORACLE, "hand"))) {
            String p = text(get(h, "p"));
            Regex re = checkVerdict(p, (Boolean) get(h, "u"), (Boolean) get(h, "l"));
            if (re != null && !V8_MODIFIER_BUGS.contains(p)) {
                answers += checkAnswers(re, inputs, text(get(h, "m")));
            }
        }
        return answers;
    }

    @Test
    void oracleHandWritten() {
        int answers = checkHand(strings(get(ORACLE, "inputs")));
        assertTrue(array(get(ORACLE, "hand")).size() > 300 && answers > 50000, answers + " answers");
    }

    @Test
    void oracleFlags() {
        List<String> inputs = strings(get(ORACLE, "inputs"));
        int answers = 0;
        for (Object f : array(get(ORACLE, "flagged"))) {
            String p = "(?" + text(get(f, "f")) + ":" + text(get(f, "p")) + ")";
            boolean u = (Boolean) get(f, "u");
            Regex re = checkVerdict(p, u, !u);
            if (re != null) {
                answers += checkAnswers(re, inputs, text(get(f, "m")));
            }
        }
        assertTrue(answers > 5000, answers + " answers");
    }

    /** The generator gen_oracle.js uses, so the test builds the patterns the oracle's verdicts are about. */
    static final class Xorshift {
        int s;

        Xorshift(int seed) {
            s = seed;
        }

        int next() {
            s ^= s << 13;
            s ^= s >>> 17;
            s ^= s << 5;
            return s;
        }

        int below(int n) {
            return Integer.remainderUnsigned(next(), n);
        }
    }

    @Test
    void oracleGenerated() {
        String alphabet = "ab0\\\\^$.*+?()[]{}|-,:=!<>dwsbpkuxc1L";
        String verdicts = text(get(ORACLE, "verdicts"));
        List<String> randomMatches = strings(get(ORACLE, "randomMatches"));
        List<String> shortInputs = strings(get(ORACLE, "shortInputs"));
        Xorshift seed = new Xorshift(0x9e3779b9);
        int[] counts = new int[3];
        int answers = 0;
        for (int i = 0; i < verdicts.length(); i++) {
            int n = 1 + seed.below(8);
            StringBuilder p = new StringBuilder();
            for (int k = 0; k < n; k++) {
                p.append(alphabet.charAt(seed.below(alphabet.length())));
            }
            char verdict = verdicts.charAt(i);
            counts[verdict - '0']++;
            Regex re = checkVerdict(p.toString(), verdict == '1', verdict == '2');
            if (re != null && i < randomMatches.size()) {
                answers += checkAnswers(re, shortInputs, randomMatches.get(i));
            }
        }
        assertTrue(counts[0] > 100 && counts[1] > 100 && counts[2] > 100 && answers > 10000, answers + " answers");
    }

    // -----------------------------------------------------------------------------------------------------------------
    // The size of a pattern.

    static int sizeOf(String pattern, boolean unicode) {
        try {
            return Parser.parse(pattern, unicode).parser.size;
        } catch (ParseFailure e) {
            return -1;
        }
    }

    /** The size the parser counts for each pattern of size_cases.json, which every port must count the same. */
    @Test
    void theSizeOfAPattern() {
        List<Object> cases = array(TestJson.resource("size_cases.json"));
        int valid = 0;
        int legacy = 0;
        int invalid = 0;
        for (Object c : cases) {
            String p = (String) get(c, "p");
            int u = ((Double) get(c, "u")).intValue();
            int l = ((Double) get(c, "l")).intValue();
            assertEquals(u, sizeOf(p, true), "the size of " + p + " with the u flag");
            assertEquals(l, sizeOf(p, false), "the size of " + p + " without it");
            if (u >= 0) {
                valid++;
            } else if (l >= 0) {
                legacy++;
            } else {
                invalid++;
            }
        }
        assertEquals(122, cases.size());
        assertTrue(valid >= 90 && legacy >= 10 && invalid >= 5, "the cases are not the ones this test is about");
    }

    // -----------------------------------------------------------------------------------------------------------------
    // The next character.

    @Test
    void theNextCharacterRulesOutAWay() {
        List<V8Case> quick = cases("quick");
        Counts c = checkCases(quick, Duration.ofSeconds(60));
        assertTrue(quick.size() >= 75 && c.matched >= 2000);
    }

    static boolean has(Program p, int pc, int b) {
        return ((b < 64 ? p.quick0[pc] >>> b : p.quick1[pc] >>> (b - 64)) & 1) != 0;
    }

    @Test
    void quickOfAProgram() {
        Object[][] table = {
            {"abc", "a", false, false, false},
            {"[a-c]x", "abc", false, false, false},
            {"a|b|\\u00e9", "ab", true, false, false},
            {"a?b", "ab", false, false, false},
            {"a*$", "a", false, false, true},
            {"(?:a|)b", "ab", false, false, false},
            {"$", "", false, false, true},
            {"(?:a|$)b", "a", false, false, false},
            {"(?=a)[a-c]", "a", false, false, false},
            {"(?=[ab])(?:b|x)", "b", false, false, false},
            {"(?!a)[a-c]", "abc", false, false, false},
            {"(?=a|$)(?:a|)$", "a", false, false, true},
            {"(?<=a)b", "b", false, false, false},
            {"(?<=a|b)$", "", false, false, true},
            {"\\bx", "x", false, false, false},
            {"^a", "a", false, false, false},
            {"(?:a|b)*c", "abc", false, false, false},
            {"(?:a?b?)*c", "abc", false, false, false},
            {"(?:a|){2}x", "ax", false, false, false},
            {"[^a]", "bcd", true, false, false},
            {"(?=)a", "a", false, false, false},
            {"[]|a", "a", false, false, false},
            {"(?!)[]", "", false, false, false},
        };
        for (Object[] t : table) {
            String pattern = (String) t[0];
            Program p = program(pattern);
            int flags = p.quickFlags[0];
            assertEquals(t[2], (flags & Program.Q_OTHER) != 0, pattern + " other");
            assertEquals(t[3], (flags & Program.Q_MID) != 0, pattern + " mid");
            assertEquals(t[4], (flags & Program.Q_END) != 0, pattern + " end");
            for (char c : ((String) t[1]).toCharArray()) {
                assertTrue(has(p, 0, c), pattern + " can start with " + c);
            }
            // A character the pattern cannot start with: none of the cases starts with a digit.
            assertEquals(pattern.equals("[^a]"), has(p, 0, '0'), pattern + " and a digit");
        }
        // A backreference and the body of a lookbehind allow anything.
        for (String pattern : new String[] {"(a)\\1x", "(?<=ab)"}) {
            Program p = program(pattern);
            boolean found = false;
            for (int pc = 0; pc < p.insts.length; pc++) {
                int op = p.insts[pc].op;
                if (op >= Inst.BACKREF && op <= Inst.BACKREF_UNITS_BACK || op == Inst.CHAR_BACK) {
                    found = true;
                    assertTrue(p.quick0[pc] == -1L && p.quick1[pc] == -1L && p.quickFlags[pc] == 7, pattern + " " + pc);
                }
            }
            assertTrue(found, pattern + " has no instruction this is about");
        }
    }

    // -----------------------------------------------------------------------------------------------------------------
    // The tables of the Go package's own tests.

    /** The Java port's hand-written patterns: whether each is valid with the u flag, and whether it is valid at all. */
    @Test
    void javaHandWrittenPatterns() {
        Object[][] table = {
            {"", true, true}, {"a", true, true}, {"^a$", true, true}, {"a|b", true, true}, {"(a)", true, true},
            {"(?:a)", true, true}, {"(?=a)", true, true}, {"(?!a)", true, true}, {"(?<=a)", true, true},
            {"(?<!a)", true, true}, {"(?<n>a)\\k<n>", true, true}, {"\\k<n>", false, true},
            {"(?<n>a)(?<n>b)", false, false}, {"(?<$x_1>a)", true, true}, {"(?<1a>a)", false, false},
            {"a{2}", true, true}, {"a{2,}", true, true}, {"a{2,3}", true, true}, {"a{3,2}", false, false},
            {"a{", false, true}, {"a{,2}", false, true}, {"{", false, true}, {"}", false, true}, {"]", false, true},
            {"[", false, false}, {"[]", true, true}, {"[^]", true, true}, {"[a-z]", true, true},
            {"[z-a]", false, false}, {"[\\d-z]", false, true}, {"[a-\\d]", false, true}, {"[\\b]", true, true},
            {"[\\-]", true, true}, {"\\-", false, true}, {"\\a", false, true}, {"\\c", false, true},
            {"\\cA", true, true}, {"\\c1", false, true}, {"\\0", true, true}, {"\\01", false, true},
            {"\\1", false, true}, {"(a)\\1", true, true}, {"(a)\\2", false, true}, {"\\x4", false, true},
            {"\\x41", true, true}, {"\\u004", false, true}, {"\\u0041", true, true}, {"\\u{1F600}", true, true},
            {"\\u{110000}", false, true}, {"\uD83D\uDE00", true, true}, {"[\uD83D\uDE00-\uD83D\uDE4F]", true, true},
            {"\\p{L}", true, true}, {"\\p{Letter}", true, true}, {"\\p{digit}", true, true},
            {"\\p{Nope}", false, true}, {"\\p{gc=Lu}", true, true}, {"\\p{Script=Greek}", true, true},
            {"\\p{sc=Grek}", true, true}, {"\\p{Script=Nope}", false, true}, {"\\P{ASCII}", true, true},
            {"\\p", false, true}, {"\\p{", false, true}, {"a**", false, false}, {"a+?", true, true},
            {"*a", false, false}, {"(?=a)*", false, true}, {"^*", false, false}, {"$+", false, false},
            {"\\b+", false, false}, {"a)", false, false}, {"(a", false, false}, {"(?a)", false, false},
            {"\\/", true, true}, {"\\.", true, true}, {"a\\", false, false}, {"[a", false, false},
            {"x{1}{2}", false, false}, {"\\w+@\\w+\\.\\w+", true, true}, {"^[a-z][a-z0-9_]*$", true, true},
            {"(?<a>.)\\k<a>", true, true}, {"[\\p{L}\\d]", true, true}, {"\\s\\S\\w\\W\\d\\D", true, true},
        };
        for (Object[] t : table) {
            String pattern = (String) t[0];
            assertEquals(t[1], valid(pattern), "valid(" + pattern + ")");
            assertEquals(t[2], compiles(pattern), "compile(" + pattern + ")");
            if (compiles(pattern)) {
                assertEquals(t[1], compile(pattern).unicode, "compile(" + pattern + ").unicode");
            }
        }
    }

    /** Every string of the JSON-Schema-Test-Suite that is a pattern, a key of patternProperties or an instance. */
    static TreeSet<String> suiteCorpus() throws IOException {
        String env = System.getenv("JSON_SCHEMA_TEST_SUITE");
        Path root = env != null ? Paths.get(env) : Paths.get(System.getProperty("user.dir"), "..", "JSON-Schema-Test-Suite");
        if (!Files.isDirectory(root.resolve("tests"))) {
            System.err.println("JSON-Schema-Test-Suite not found at " + root + "; skipping");
            return null;
        }
        TreeSet<String> seen = new TreeSet<>();
        try (Stream<Path> files = Files.walk(root.resolve("tests"))) {
            for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".json"))::iterator) {
                try {
                    walk(new TestJson(Files.readString(f)).parse(), seen);
                } catch (RuntimeException e) {
                    // A file that is not JSON the reader takes is left out, as the Rust test leaves it out.
                }
            }
        }
        return seen;
    }

    @SuppressWarnings("unchecked")
    static void walk(Object v, TreeSet<String> seen) {
        if (v instanceof List) {
            for (Object item : (List<Object>) v) {
                walk(item, seen);
            }
        } else if (v instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
                if (e.getValue() instanceof String && (e.getKey().equals("pattern") || e.getKey().equals("data"))) {
                    seen.add((String) e.getValue());
                }
                if (e.getValue() instanceof Map && e.getKey().equals("patternProperties")) {
                    seen.addAll(((Map<String, Object>) e.getValue()).keySet());
                }
                walk(e.getValue(), seen);
            }
        }
    }

    /** valid and compile agree on every string of the JSON-Schema-Test-Suite. */
    @Test
    void suiteCorpusIsReadTheSameByValidAndCompile() throws IOException {
        TreeSet<String> corpus = suiteCorpus();
        if (corpus == null) {
            return;
        }
        int validCount = 0;
        int compiled = 0;
        for (String p : corpus) {
            boolean ok = valid(p);
            Regex re = compiles(p) ? compile(p) : null;
            if (ok) {
                validCount++;
                assertTrue(re != null && re.unicode, "valid(" + p + ") but compile does not use the u flag grammar");
            }
            if (re != null) {
                compiled++;
                assertTrue(ok || !re.unicode, "compile(" + p + ") used the u flag grammar but valid rejects it");
            }
        }
        assertTrue(validCount > 500 && compiled >= validCount, corpus.size() + " strings, " + validCount + " valid");
    }

    // -----------------------------------------------------------------------------------------------------------------
    // The matcher.

    @Test
    void backtrackingSemantics() {
        Object[][] table = {
            {"^\\1(a)$", "a", true}, {"^(a)?\\1b$", "b", true}, {"^(?:(a)|b)\\1$", "b", true},
            {"^(?:(a)|b)*\\1$", "ab", true}, {"^(?:(a)|b)*\\1$", "aba", false}, {"^(?:(a)|b)*\\1$", "baa", true},
            {"^(?:a*)*$(?<=a)", "aaa", true}, {"^(?:a*)*b(?<=b)", "aaa", false}, {"^(?:(?=(a)))?\\1b", "b", true},
            {"^(?:(?=(a)))?\\1a", "a", true}, {"^(?:a?){3,5}b(?<=b)", "aab", true}, {"^(?:|a)+b(?<=b)", "aab", true},
            {"^(?=(a+))a*b\\1$", "aaaba", false}, {"^(?=(a+))a*b\\1$", "aaabaaa", true}, {"(?!(a))\\1b", "b", true},
            {"(?<=(\\d+)(\\d+))$", "1053", true}, {"(?<=\\1(a))b", "aab", true}, {"(?<=\\1(a))b", "ab", false},
            {"(?<=^a*)b", "aab", true}, {"(?<!^a*)b", "aab", false}, {"(?<=a|bc)x", "bcx", true},
            {"^(?<n>.)\\k<n>$", "\uD83D\uDC32\uD83D\uDC32", true}, {"^(?<n>.)\\k<n>$", "\uD83D\uDC32\uD83D\uDC09", false},
            {"(?<=^.)$", "\uD83D\uDC32", true}, {"(?<=[\uD83D\uDE00-\uD83D\uDE0E])x", "\uD83D\uDE03x", true},
            {"(?<!\\u{1F600})x", "\uD83D\uDE00x", false}, {"^(?:a|ab)+?b$(?<=b)", "aabb", true},
            {"^(?:(a)|b){2,}?\\1$", "abbaa", true}, {"^(?:(a)|b){2,}?\\1$", "abba", false},
            {"^(.+?)\\1+$", "abcabcabc", true}, {"^(.+?)\\1+$", "abcabcab", false}, {"^(?:(\\w)(?!\\1))+$", "abab", true},
            {"^(?:(\\w)(?!\\1))+$", "abba", false}, {"^(?!.*(.).*\\1)[a-c]+$", "abc", true},
            {"^(?!.*(.).*\\1)[a-c]+$", "abca", false},
        };
        for (Object[] t : table) {
            Regex re = compile((String) t[0]);
            assertFalse(re.regular, t[0] + " needs no backtracking");
            assertEquals(t[2], re.isMatch((String) t[1]), t[0] + " on " + t[1]);
            assertEquals(t[2], re.isMatch(Utf8Text.of((String) t[1])), t[0] + " on " + t[1] + " over UTF-8");
        }
    }

    /** A long text does not overflow the stack of the thread: the choices are on a stack of the matcher's own. */
    @Test
    void longInput() {
        String longText = "ab".repeat(1 << 20);
        Object[][] table = {
            {"^(?:a|b)*$(?<=b)", longText, true}, {"^(?:a|b)*c(?<=c)", longText, false},
            {"^(?:ab)+(?!.)", longText, true}, {"^(?=a)(?:\\w\\w)*?$", longText, true},
            {"^(a|b)*\\1$", longText + "b", true}, {"(?<=^(?:ab)*)c", longText + "c", true},
            {"^(?!b).*b$", longText, true}, {"^[ab]{2097152}(?<=b)$", longText, true},
            {"^(?:[ab]c?){2097152}(?<=b)$", longText, true},
        };
        for (Object[] t : table) {
            Regex re = compile((String) t[0]);
            assertFalse(re.regular, t[0] + " needs no backtracking");
            assertEquals(t[2], re.isMatch((String) t[1]), (String) t[0]);
            assertEquals(t[2], re.isMatch(Utf8Text.of((String) t[1])), t[0] + " over UTF-8");
        }
    }

    /** A compiled pattern is shared between threads, each of which matches with a working memory of its own. */
    @Test
    void concurrentUse() throws InterruptedException {
        for (String p : new String[] {"^(?:[a-z]+\\d)+$", "^(?:([a-z])\\1?\\d)+(?<=\\d)$"}) {
            Regex re = compile(p);
            AtomicBoolean wrong = new AtomicBoolean();
            List<Thread> threads = new ArrayList<>();
            for (int g = 0; g < 16; g++) {
                String yes = "aa1b2".repeat(50 + g);
                String no = yes + "!";
                Thread t = new Thread(() -> {
                    for (int k = 0; k < 500; k++) {
                        if (!re.isMatch(yes) || re.isMatch(no) || !re.isMatch(Utf8Text.of(yes))) {
                            wrong.set(true);
                        }
                    }
                });
                threads.add(t);
                t.start();
            }
            for (Thread t : threads) {
                t.join();
            }
            assertFalse(wrong.get(), p + " gave a wrong answer under concurrent use");
        }
    }

    /** Every member of a class of equivalent characters has the class's canonical form, and the other way round. */
    @Test
    void foldTables() {
        for (boolean unicodeMode : new boolean[] {true, false}) {
            Map<Integer, Integer> seen = new HashMap<>();
            for (int r = 0; r <= CharSet.MAX_RUNE; r++) {
                int key = Fold.canonicalize(r, unicodeMode);
                Integer other = seen.get(key);
                if (other == null && key != r) {
                    other = key;
                }
                if (other != null) {
                    int[][] pairs = {{r, other}, {other, r}};
                    for (int[] ab : pairs) {
                        Fold.Closure c = Fold.foldClosure(CharSet.of(new int[] {ab[0], ab[0]}, false), unicodeMode);
                        assertTrue(c.variants == 1 && c.set.contains(ab[1]),
                                "u flag " + unicodeMode + ": " + Integer.toHexString(ab[0]) + " and " + Integer.toHexString(ab[1]));
                    }
                }
                seen.put(key, r);
            }
            for (int r : new int[] {0x4B, 0x6B, 0x212A, 0x53, 0x17F, 0xDF, 0x1E9E, 0x3A3, 0x3C2, 0x3C3, 0x130, 0x131,
                0x1C4, 0x10400}) {
                Fold.Closure c = Fold.foldClosure(CharSet.of(new int[] {r, r}, false), unicodeMode);
                int[] ranges = c.set.ranges;
                for (int k = 0; k < ranges.length; k += 2) {
                    for (int member = ranges[k]; member <= ranges[k + 1]; member++) {
                        assertEquals(Fold.canonicalize(r, unicodeMode), Fold.canonicalize(member, unicodeMode),
                                Integer.toHexString(member));
                    }
                }
            }
        }
    }

    @Test
    void modifiers() {
        Object[][] table = {
            {"^(?i:abc)$", "aBc", true}, {"^a(?i:b)c$", "aBc", true}, {"^a(?i:b)c$", "ABc", false},
            {"^(?i:a(?-i:b)c)$", "AbC", true}, {"^(?i:a(?-i:b)c)$", "ABC", false}, {"^(?i:k)$", "\u212a", true},
            {"^(?i:k)\\&?$", "\u212a", false}, {"^(?i:s)$", "\u017f", true}, {"^(?i:s)\\&?$", "\u017f", false},
            {"^(?i:\\w)$", "\u017f", true}, {"^(?i:\\W)$", "\u017f", false}, {"(?i:\\b)\\u212a", "\u212a", true},
            {"\\b\\u212a", "\u212a", false}, {"^(?i:[^a-z])$", "K", false}, {"^(?i:[^a-z])$", "1", true},
            {"^(?i:\u00df)$", "\u1e9e", true}, {"^(?i:\u00df)$", "SS", false}, {"^(.)(?i:\\1)$", "aA", true},
            {"^(.)\\1$", "aA", false}, {"^(.)(?i:\\1)$", "k\u212a", true}, {"(?<=(?i:\\1)(.))x", "Aax", true},
            {"^(?s:.)$", "\n", true}, {"^.$", "\n", false}, {"^(?s:a.)b.$", "a\nb\n", false},
            {"(?m:^)b", "a\nb", true}, {"^b", "a\nb", false}, {"a(?m:$)", "a\u2028b", true}, {"a(?m:$)", "ab", false},
            {"(?m:^)b", "a\rb", true}, {"(?m:^)b", "a\u2029b", true},
            {"^(?:(?<a>x)|(?<a>y))\\k<a>$", "yy", true}, {"^(?:(?<a>x)|(?<a>y))\\k<a>$", "xy", false},
            {"^(?:(?<a>x)|(?<a>y))+\\k<a>$", "xyy", true}, {"^(?:(?<a>x)|(?<a>y))+\\k<a>$", "xyx", false},
            {"^(?<\\u0061b>.)\\k<ab>$", "zz", true},
        };
        for (Object[] t : table) {
            Regex re = compile((String) t[0]);
            assertEquals(t[2], re.isMatch((String) t[1]), t[0] + " on " + t[1]);
            assertEquals(t[2], matchBacktrack(re, (String) t[1], null), t[0] + " on " + t[1] + " (backtracking)");
        }
        for (String p : new String[] {"(?i)a", "(?-:a)", "(?ii:a)", "(?i-i:a)", "(?x:a)", "(?i-m-s:a)",
            "(?<a>x)(?<a>y)", "(?<a>x|(?<a>y))", "(?:(?<a>x)|b)(?:(?<a>y)|c)", "(?<a\\u0020>x)", "(?<\\u0030>x)"}) {
            assertFalse(valid(p), "valid(" + p + ")");
            assertFalse(compiles(p), "compile(" + p + ")");
        }
    }

    @Test
    void properties() {
        int expressions = 0;
        List<String> exprs = new ArrayList<>(List.of(TablesGen.BINARY_NAMES));
        exprs.addAll(List.of("ASCII", "Any", "Assigned", "Alpha", "AHex", "space", "XIDS", "L", "Letter", "LC", "digit",
                "punct", "Cn"));
        for (String name : new String[] {"L", "Lu", "Uppercase_Letter", "Cased_Letter", "Mark", "Combining_Mark", "digit",
            "punct", "cntrl", "Cn"}) {
            exprs.add("gc=" + name);
            exprs.add("General_Category=" + name);
        }
        for (String[] names : TablesGen.SCRIPT_NAMES) {
            for (String value : names) {
                for (String property : new String[] {"sc", "Script", "scx", "Script_Extensions"}) {
                    exprs.add(property + "=" + value);
                }
            }
        }
        for (String value : new String[] {"Unknown", "Zzzz", "Katakana_Or_Hiragana", "Hrkt"}) {
            exprs.add("sc=" + value);
            exprs.add("scx=" + value);
        }
        for (String expr : exprs) {
            assertNotNull(CharSet.propertySet(expr), "\\p{" + expr + "} does not resolve");
            expressions++;
        }
        assertTrue(expressions > 1000, expressions + " property expressions");
        Object[][] table = {
            {"^\\p{L}$", "\u00e9", true}, {"^\\p{L}$", "1", false}, {"^\\p{Lu}$", "\u00c9", true},
            {"^\\P{Lu}$", "\u00c9", false}, {"^\\p{Nd}$", "\u09ea", true}, {"^\\p{digit}$", "\u09ea", true},
            {"^\\p{sc=Grek}$", "\u03b1", true}, {"^\\p{Script=Greek}$", "a", false}, {"^\\p{sc=Grek}$", "\u0342", false},
            {"^\\p{scx=Grek}$", "\u0342", true}, {"^\\p{sc=Zinh}$", "\u0342", true},
            {"^\\p{Emoji}$", "\uD83D\uDC32", true}, {"^\\p{Emoji_Presentation}$", "#", false},
            {"^\\p{Emoji}$", "#", true}, {"^\\p{Alphabetic}$", "\u2177", true}, {"^\\p{Uppercase}$", "\u24b6", true},
            {"^\\p{ID_Start}$", "_", false}, {"^\\p{ID_Continue}$", "_", true}, {"^\\p{White_Space}$", "\u0085", true},
            {"^\\s$", "\u0085", false}, {"^\\p{White_Space}$", "\ufeff", false}, {"^\\s$", "\ufeff", true},
            {"^\\p{Any}$", "\uDBFF\uDFFF", true}, {"^\\p{Assigned}$", "\uDBFF\uDFFF", false},
            {"^\\p{Script=Unknown}$", "\uDB40\uDC80", true}, {"^\\p{ASCII}$", "\u00e9", false},
            {"^[\\p{Lu}\\p{Nd}]+$", "A1\u00c9\u09ea", true}, {"^[^\\p{L}]+$", "1 -", true}, {"^[^\\p{L}]+$", "1a", false},
        };
        for (Object[] t : table) {
            assertEquals(t[2], compile((String) t[0]).isMatch((String) t[1]), t[0] + " on " + t[1]);
        }
        for (String p : new String[] {"\\p{Nope}", "\\p{L=x}", "\\p{gc=Greek}", "\\p{sc=L}", "\\p{}", "\\p{Lu", "\\pL",
            "\\p{sc=}", "\\p{ascii}"}) {
            assertFalse(valid(p), "valid(" + p + ")");
        }
    }

    /** The answers of the automaton's table, from the matcher until the automaton comes (step 2). */
    @Test
    void automatonAnswers() {
        Object[][] table = {
            {"^$", "", true}, {"^$", "a", false}, {"\\bfoo\\b", "a foo b", true}, {"\\bfoo\\b", "a foob", false},
            {"\\bfoo", "afoo", false}, {"\\B", "", true}, {"\\b", "", false}, {"\\b", " ", false}, {"\\b", "a", true},
            {"a\\B", "ab", true}, {"a\\B", "a", false}, {"\\Ba", "ba", true}, {"^\\b", "a", true}, {"\\b^", " ", false},
            {"\\b$", "a", true}, {"\\B$", "a ", true}, {"x\\b|y", "xa", false}, {"$", "abc", true}, {"^", "abc", true},
            {"$^", "", true}, {"$^", "a", false}, {"a$", "ba", true}, {"a$", "ab", false}, {"^a", "ab", true},
            {"^a", "ba", false}, {"b", "aaab", true}, {"^(a|ab)(c|bcd)$", "abcd", true}, {"^(a|ab)(c|bcd)$", "abc", true},
            {"^(a|ab)(c|bcd)$", "abcc", false}, {"x{2,3}", "axxb", true}, {"^x{2,3}$", "xxxx", false},
            {"^.$", "\n", false}, {"^[^a]$", "\n", true}, {"\u00e9", "caf", false}, {"^a*$", "", true},
            {"^(a$|b)c?$", "a", true}, {"^(a$|b)c?$", "ac", false},
        };
        for (Object[] t : table) {
            Regex re = compile((String) t[0]);
            assertEquals(t[2], re.isMatch((String) t[1]), t[0] + " on " + t[1]);
            assertEquals(t[2], matchBacktrack(re, (String) t[1], null), t[0] + " on " + t[1] + " (backtracking)");
        }
        Regex re = compile("^[a-z]+$");
        assertTrue(re.isMatch("abc") && !re.isMatch("caf\u00e9"));
        re = compile("^\\p{L}+$");
        assertTrue(re.isMatch("caf\u00e9") && !re.isMatch("caf\u00e9 1") && re.isMatch("cafe"));
    }

    // -----------------------------------------------------------------------------------------------------------------
    // The limit of a match.

    /** Patterns and texts whose match takes longer than any test waits: a group read back inside a repetition. */
    static String[][] forever() {
        return new String[][] {
            {"^(?:(a+)\\1*)+$", "a".repeat(60) + "b"},
            {"(a*)*\\1b", "a".repeat(60)},
            {"^(?:(\\w+)\\s?\\1?)+$", "ab ".repeat(30) + "!"},
            {"(?=(?:(a+)\\1*)+b)a", "a".repeat(60)},
        };
    }

    @Test
    void aMatchIsAbandonedAtItsLimit() {
        for (Duration limit : new Duration[] {Duration.ofMillis(20), Duration.ofMillis(300)}) {
            for (String[] f : forever()) {
                Regex re = compile(f[0]);
                long start = System.nanoTime();
                MatchLimitException e = assertThrows(MatchLimitException.class, () -> matchLimit(re, f[1], limit));
                Duration took = Duration.ofNanos(System.nanoTime() - start);
                assertEquals(MatchLimitException.Kind.TIME, e.kind(), f[0]);
                assertTrue(took.compareTo(limit) >= 0 && took.compareTo(limit.plus(GENEROUS)) < 0,
                        f[0] + " with " + limit + " was abandoned after " + took);
            }
        }
    }

    @Test
    void aMatchWithNoLimitIsNotAbandoned() {
        Regex re = compile("^(?:(a+)\\1*)+$");
        String text = "a".repeat(14) + "b";
        assertThrows(MatchLimitException.class, () -> matchLimit(re, text, Duration.ofNanos(1)));
        assertFalse(matchLimit(re, text, null));
        assertFalse(matchLimit(re, text, Duration.ZERO), "a limit of zero is no limit");
        assertFalse(re.isMatch(text));
        assertTrue(matchLimit(re, text.substring(0, 14), GENEROUS));
    }

    @Test
    void aMatchIsAbandonedAtItsMemoryLimit() {
        String[][] table = {
            {"(?:a?){2147483647}", ""},
            {"(?:a?){100000000}", "a".repeat(100)},
            {"(?:(?:(?:a?){1000}){1000}){1000}", "a".repeat(20)},
            {"(?:(a)|b|){2147483647}\\1", ""},
        };
        for (String[] t : table) {
            Regex re = compile(t[0]);
            long start = System.nanoTime();
            MatchLimitException e = assertThrows(MatchLimitException.class, () -> matchLimit(re, t[1], Duration.ofHours(1)));
            assertEquals(MatchLimitException.Kind.MEMORY, e.kind(), t[0]);
            assertTrue(System.nanoTime() - start < GENEROUS.toNanos(), t[0]);
        }
    }

    @Test
    void aLimitDoesNotChangeAnAnswer() {
        String longText = "ab".repeat(50000);
        String accented = longText + "\u00e9";
        Object[][] table = {
            {"^[a-z]+$", "abc", true}, {"^[a-z]+$", "abc1", false}, {"^\\p{L}+$", "caf\u00e9", true},
            {"^\\p{L}+$", "caf\u00e9 ", false}, {"^(?=.*\\d)\\w+$", "abc1", true}, {"^(?=.*\\d)\\w+$", "abcd", false},
            {"^(a+)\\1$", "aaaa", true}, {"^(a+)\\1$", "aaa", false}, {"(?<=\\$)\\d+", "cost $42", true},
            {"^(?=.)(?:a|b)*$", longText, true}, {"^(?=.)(?:a|b)*c$", longText, false}, {"^[ab]*$", longText, true},
            {"^[ab]*c$", longText, false}, {"^[ab\u00e9]*$", accented, true}, {"^[ab\u00e9]*c$", accented, false},
        };
        for (Object[] t : table) {
            Regex re = compile((String) t[0]);
            for (Duration limit : new Duration[] {null, Duration.ofSeconds(60)}) {
                assertEquals(t[2], matchLimit(re, (String) t[1], limit), t[0] + " with a limit of " + limit);
                assertEquals(t[2], matchBacktrack(re, (String) t[1], limit), t[0] + " with a limit of " + limit);
            }
        }
    }

    // -----------------------------------------------------------------------------------------------------------------
    // The memory of failed places.

    @Test
    void failedPlacesAreRemembered() {
        List<V8Case> memo = cases("memo");
        Counts c = checkCases(memo, Duration.ofSeconds(60));
        int withPoints = 0;
        for (V8Case m : memo) {
            withPoints += program(m.p).points > 0 ? 1 : 0;
        }
        assertTrue(memo.size() >= 50 && withPoints >= 45 && c.matched >= 500, withPoints + " with memo points");
        boolean before = State.memoFromTheStart;
        State.memoFromTheStart = true;
        try {
            int total = 0;
            for (String set : new String[] {"memo", "quick", "loops", "needs", "units", "fast"}) {
                total += checkCases(cases(set), Duration.ofSeconds(120)).answers;
            }
            total += checkHand(strings(get(ORACLE, "inputs")));
            assertTrue(total > 60000, total + " answers with the memory in use from the first step");
        } finally {
            State.memoFromTheStart = before;
        }
    }

    @Test
    void aRepetitionInARepetitionIsAnswered() {
        String a = "a".repeat(60);
        String ab = "ab".repeat(30);
        Object[][] table = {
            {"(?=a)(a*)*b", a, false}, {"(?=a)(a*)*b", a + "b", true}, {"^(?=.)(?:a+)+$", a + "x", false},
            {"^(?=.)(?:a+)+$", a, true}, {"^(?=.)(?:a|aa)+$", a + "b", false}, {"^(?=.)(?:a|aa)+$", a, true},
            {"^(?=.)(?:a?b?)*x", ab, false}, {"^(?=.)(?:a?b?)*x", ab + "x", true},
            {"^(?=.)(?:(?:a?b?)*x?)*$", ab + "!", false}, {"^(?=.)(?:(?:a*)+b?)+$", a + "!", false},
            {"^(?=.)(?:(?:a*)+b?)+$", a + "b", true}, {"^(?=.)(?:(?:a?)+?b?)*?x", a, false},
            {"(?=.)(?:a*?b*?)*?x$", a + "b", false}, {"(?i:\\b(?:\\w+\\W?)+\\b!)", "ab ".repeat(40), false},
            {"(?i:\\b(?:\\w+\\W?)+\\b!)", "ab ".repeat(40) + "ab!", true}, {"(?m:^(?:\\w+\\s?)+$)", "a ".repeat(30) + "!", false},
            {"^(?!(?:a+)+x)a*b", a + "b", true}, {"^(?!(?:a+)+x)a*b", a, false}, {"(?<!(?:a|aa)+b)x$", a + "x", true},
            {"(?=.).*.*.*x", "a".repeat(3000), false}, {"(?=.)a*?a*?a*?b", "a".repeat(3000), false},
            {"(?=.)(?:a|b)*c", "ab".repeat(50000), false}, {"(?=.)(?:a|\\u00e9)*c", "a\u00e9".repeat(30000), false},
        };
        for (Object[] t : table) {
            Regex re = compile((String) t[0]);
            assertTrue(!re.regular && re.prog.points > 0, t[0] + " has " + re.prog.points + " memo points");
            for (Duration limit : new Duration[] {GENEROUS, null}) {
                assertEquals(t[2], matchLimit(re, (String) t[1], limit), t[0] + " on " + ((String) t[1]).length());
            }
        }
    }

    @Test
    void aBackreferenceTurnsTheMemoryOff() {
        for (String p : new String[] {"(a*)*\\1b", "^(?:(a+)\\1*)+$", "(a)(?:b|c)*\\1", "(?<n>a)(?:b+)+\\k<n>"}) {
            assertEquals(0, program(p).points, p + " has memo points");
        }
        Regex re = compile("(a*)*\\1b");
        assertThrows(MatchLimitException.class, () -> matchLimit(re, "a".repeat(60), Duration.ofMillis(50)));
    }

    @Test
    void memoPoints() {
        Object[][] table = {
            {"(?:a|b)*c", new String[] {"split", "split"}},
            {"(?:a?b?)*c", new String[] {"loop", "rep 0", "rep 0"}},
            {"(?:(?:a?)*b)*", new String[] {"split", "loop", "rep 0"}},
            {"(?:(?:a?)+b?)+x", new String[] {"loop", "loop 0", "rep 0 1", "rep 0"}},
            {"(?:(?:(?:a?)*b?)*x?)*", new String[] {"loop", "loop 0", "loop 0 1", "rep 0 1", "rep 0"}},
            {"(?:a|){2,3}(?:b|c)", new String[] {"split"}},
            {"(?:(?:a|b){2}x?)*", new String[] {"split", "rep"}},
            {"(?:(?:a?b?)*?(?:c|d)){3}", new String[] {}},
            {"(?:a?(?=(?:b|c)*x))*", new String[] {"loop", "rep 0", "split", "split"}},
            {"(?:(?:a|){2}(?=b|c))*", new String[] {"loop", "split"}},
            {"(?<=(?:a?b?)*)x", new String[] {"loop", "split 0", "split 0"}},
            {"a*b*?c{2,3}", new String[] {"rep", "lazy", "rep"}},
            {"(?:a+)+$", new String[] {"rep", "split"}},
            {"(a)\\1(?:b|c)", new String[] {}},
        };
        for (Object[] t : table) {
            String pattern = (String) t[0];
            Program p = program(pattern);
            List<String> points = new ArrayList<>();
            for (Inst i : p.insts) {
                if (i.m == 0) {
                    continue;
                }
                String s;
                switch (i.op) {
                    case Inst.SPLIT:
                        s = "split";
                        break;
                    case Inst.LOOP:
                        s = "loop";
                        break;
                    case Inst.REP:
                        s = "rep";
                        break;
                    case Inst.REP_LAZY:
                        s = "lazy";
                        break;
                    default:
                        s = "?";
                }
                for (int k = 0; k < Program.MAX_MEMO_LOOPS; k++) {
                    int l = p.pointLoops[(i.m - 1) * Program.MAX_MEMO_LOOPS + k];
                    if (l >= 0) {
                        s += " " + l;
                    }
                }
                points.add(s);
            }
            assertEquals(List.of((String[]) t[1]), points, pattern);
            assertEquals(((String[]) t[1]).length, p.points, pattern);
        }
        assertEquals(2, Program.MAX_MEMO_LOOPS);
    }

    @Test
    void aMatchTooLargeForTheMemoryGoesWithoutIt() {
        StringBuilder alternatives = new StringBuilder();
        for (int k = 0; k < 400; k++) {
            alternatives.append(k == 0 ? "" : "|").append('a').append("b".repeat(k % 7));
        }
        Regex re = compile("^(?=.)(?:" + alternatives + ")+$");
        try {
            assertFalse(matchLimit(re, "ab".repeat(50000) + "x", Duration.ofMillis(100)));
        } catch (MatchLimitException e) {
            assertEquals(MatchLimitException.Kind.TIME, e.kind());
        }
    }

    // -----------------------------------------------------------------------------------------------------------------
    // The limits of size.

    static int rangesOf(String name) {
        return CharSet.propertySet(name).ranges.length / 2;
    }

    static String nest(String open, String body, String close, int depth) {
        return open.repeat(depth) + body + close.repeat(depth);
    }

    static int fit(int each) {
        return (Parser.MAX_SIZE - 4096) / each;
    }

    /** Patterns just under the limit of size, of every shape that is costly for its size. */
    static String[][] atTheLimit() {
        int l = rangesOf("L");
        StringBuilder named = new StringBuilder();
        for (int k = 0; k < 400; k++) {
            named.append(k == 0 ? "" : "|").append("(?<n>").append(k).append(')');
        }
        String alternatives = "a|".repeat(fit(3 * NODE_SIZE));
        return new String[][] {
            {"literal", "a".repeat(fit(2 * NODE_SIZE))},
            {"alternatives", alternatives.substring(0, alternatives.length() - 1)},
            {"groups", "(a)".repeat(fit(4 * NODE_SIZE))},
            {"groups read back", "(a)".repeat(4000) + "\\1".repeat(4000) + "\\4000".repeat(300)},
            {"nested groups", nest("(", "a".repeat(fit(2 * NODE_SIZE) - 2 * Parser.MAX_NESTING), ")", Parser.MAX_NESTING - 1)},
            {"nested counted loops", nest("(?:", "a?b".repeat(2000), "){2,3}", Parser.MAX_NESTING - 1)},
            {"nested lookbehinds", nest("(?<=", "a".repeat(2000), ")", Parser.MAX_NESTING - 1)},
            {"counted loops", "(?:a|){2}".repeat(fit(7 * NODE_SIZE))},
            {"lazy loops", "(?:a|b)*?".repeat(fit(7 * NODE_SIZE))},
            {"properties", "\\p{L}".repeat(fit(2 * NODE_SIZE))},
            {"negated properties", "\\P{L}".repeat(fit(2 * NODE_SIZE + RANGE_SIZE * (l + 1)))},
            {"classes of properties", "[\\p{L}\\p{N}]".repeat(fit(2 * NODE_SIZE + RANGE_SIZE * (l + 200)))},
            {"folded negated classes", "(?i:" + "[^a]".repeat(fit(2 * NODE_SIZE + 32)) + ")"},
            {"folded dots", "(?i:" + ".".repeat(fit(2 * NODE_SIZE)) + ")"},
            {"folded wide classes", "(?i:" + "[\\u0000-\\u{10FFFF}]".repeat(fit(3200)) + ")"},
            {"folded wide negations", "(?i:" + "[^\\u0100-\\u{10FFFF}]".repeat(fit(3300)) + ")"},
            {"folded properties", "(?i:" + "\\p{L}".repeat(fit(8600)) + ")"},
            {"folded literal", "(?i:" + "k".repeat(fit(2 * NODE_SIZE)) + ")"},
            {"one large class", "[" + "\u0100\u0102".repeat(fit(2 * RANGE_SIZE)) + "]"},
            {"groups of one name", named.toString()},
            {"named groups, nested", nest("(?:x|", "(?<a>b)|(?<c>d)".repeat(40), ")", 600)},
            {"counted, for regexp", "(?:a?){1000}".repeat(fit(4 * NODE_SIZE))},
            {"quantified everything", "a{2,5}?".repeat(fit(2 * NODE_SIZE))},
            {"long escapes", "\\u{0000000000000000000000000000000000000000000041}".repeat(fit(2 * NODE_SIZE))},
        };
    }

    /** A pattern at the limit of size compiles, and matches for the first time, far below the time a test waits. */
    @Test
    void aPatternAtTheLimitOfSizeIsCheap() {
        for (String[] t : atTheLimit()) {
            long start = System.nanoTime();
            Regex re;
            try {
                re = compile(t[1]);
            } catch (RegexException e) {
                fail(t[0] + " (" + t[1].length() + " chars): " + e.getMessage());
                return;
            }
            long compiled = System.nanoTime() - start;
            start = System.nanoTime();
            try {
                matchLimit(re, "abc", Duration.ofSeconds(60));
            } catch (MatchLimitException e) {
                // An answer or the limit: the time is what is tested.
            }
            long matched = System.nanoTime() - start;
            assertTrue(compiled < GENEROUS.toNanos() && matched < GENEROUS.toNanos(),
                    t[0] + " took " + compiled / 1e6 + " ms to compile and " + matched / 1e6 + " ms to match");
        }
    }

    /** The deepest nesting the limits allow is read, compiled and matched by a thread with a small stack. */
    @Test
    void theDeepestNestingDoesNotOverflowASmallStack() {
        small(() -> {
            int d = Parser.MAX_NESTING - 1;
            Object[][] table = {
                {nest("(", "a", ")", d), "a", true},
                {nest("(?:", "a?b", "){1,2}", d), "ab", true},
                {nest("(?=", "a", ")", d) + "b", "a", false},
                {nest("(?:a|", "b", ")", d), "b", true},
                {nest("(?:x|", "a", ")?", d) + "y$", "xxay", true},
            };
            for (Object[] t : table) {
                String pattern = (String) t[0];
                assertTrue(valid(pattern), shortOf(pattern));
                check(pattern);
                Regex re = compile(pattern);
                assertEquals(t[2], matchLimit(re, (String) t[1], GENEROUS), shortOf(pattern));
                assertEquals(t[2], matchBacktrack(re, (String) t[1], GENEROUS), shortOf(pattern));
                assertEquals(t[2], re.captures((String) t[1]) != null, shortOf(pattern) + " with its groups");
            }
            // One level more is the limit of nesting, and that many open groups with nothing to close them is an error.
            RegexException e = assertThrows(RegexException.class, () -> compile(nest("((", "a", "))", d)));
            assertEquals(RegexException.Kind.TOO_LARGE, e.kind());
            e = assertThrows(RegexException.class, () -> compile("(".repeat(900)));
            assertEquals(RegexException.Kind.INVALID, e.kind());
            assertFalse(valid("(".repeat(100000)));
        });
    }

    /** The classes of the v flag's grammar nest as groups do, and are read on a stack of their own too. */
    @Test
    void theDeepestNestingOfClassesDoesNotOverflowASmallStack() {
        small(() -> {
            int max = Parser.MAX_NESTING;
            for (String flags : new String[] {"v", "iv"}) {
                Object[][] table = {
                    {nest("[", "a", "]", max), "a", true},
                    {nest("[^", "a", "]", max), "b", max % 2 == 1},
                    {nest("[a--", "[b]", "]", max - 1), "a", true},
                    {nest("[a&&", "[a]", "]", max - 1), "a", true},
                    {nest("[\\q{ab|c}", "a", "]", max), "ab", true},
                    {nest("(?:", nest("[", "a", "]", max / 2), ")", max / 2 - 1), "a", true},
                };
                for (Object[] t : table) {
                    String pattern = (String) t[0];
                    assertTrue(Regex.isValid(pattern, flags), shortOf(pattern));
                    Regex re = Regex.compile(pattern, flags);
                    assertEquals(t[2], re.isMatch((String) t[1]), shortOf(pattern) + " on " + t[1]);
                    assertEquals(t[2], re.matches((String) t[1]).count() > 0, shortOf(pattern) + " on " + t[1]);
                }
                for (String pattern : new String[] {nest("[", "a", "]", max + 1), nest("[", "a", "]", 200_000),
                    "[".repeat(200_000)}) {
                    assertFalse(Regex.isValid(pattern, flags));
                    RegexException e = assertThrows(RegexException.class, () -> Regex.compile(pattern, flags));
                    assertEquals(RegexException.Kind.TOO_LARGE, e.kind(), shortOf(pattern));
                }
            }
        });
    }

    /** Without the v flag a class holds no class, so a pattern of many classes is read where it is asked. */
    @Test
    void manyClassesWithoutTheVFlagAreReadWhereTheyAreAsked() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            sb.append("[a-").append((char) ('b' + i % 20)).append(']');
        }
        String classes = sb.toString();
        assertTrue(Regex.shallow(classes, false));
        assertFalse(Regex.shallow(classes, true));
        small(() -> {
            for (String flags : new String[] {"", "u", "i", "iu"}) {
                Regex re = Regex.compile(classes, flags);
                assertTrue(re.isMatch("a".repeat(400)), flags);
                assertFalse(re.isMatch("a".repeat(399)), flags);
                assertTrue(Regex.isValid(classes, flags), flags);
            }
        });
    }

    @Test
    void aPatternOverALimitOfSizeIsTooLarge() {
        int terms = Parser.MAX_SIZE / (2 * NODE_SIZE);
        java.util.function.IntFunction<String> nestOf = depth -> "(?:".repeat(depth) + "a" + ")".repeat(depth);
        java.util.function.IntFunction<String> escape = zeros -> "\\u{" + "0".repeat(zeros) + "41}";
        Object[][] table = {
            {"length", escape.apply(Parser.MAX_PATTERN_LENGTH - 6), escape.apply(Parser.MAX_PATTERN_LENGTH - 5), Limit.LENGTH,
                Parser.MAX_PATTERN_LENGTH},
            {"nesting", nestOf.apply(Parser.MAX_NESTING - 1), nestOf.apply(Parser.MAX_NESTING), Limit.NESTING,
                Parser.MAX_NESTING},
            {"size of terms", "a".repeat(terms), "a".repeat(terms + 1), Limit.SIZE, Parser.MAX_SIZE},
            {"size of classes", "[" + "\u0100\u0102".repeat(60000) + "]", "[" + "\u0100\u0102".repeat(70000) + "]",
                Limit.SIZE, Parser.MAX_SIZE},
            {"size without the u flag", "\\-".repeat(terms), "\\-".repeat(terms + 1), Limit.SIZE, Parser.MAX_SIZE},
            {"nesting without the u flag", "\\-" + nestOf.apply(Parser.MAX_NESTING - 1), "\\-" + nestOf.apply(Parser.MAX_NESTING),
                Limit.NESTING, Parser.MAX_NESTING},
            {"nesting, and not valid", nestOf.apply(Parser.MAX_NESTING - 1) + "x", nestOf.apply(Parser.MAX_NESTING) + "*+)",
                Limit.NESTING, Parser.MAX_NESTING},
            {"size, and not valid", "a".repeat(terms), "a".repeat(terms + 1) + "(", Limit.SIZE, Parser.MAX_SIZE},
        };
        for (Object[] t : table) {
            String name = (String) t[0];
            String at = (String) t[1];
            String over = (String) t[2];
            assertTrue(compiles(at), name + ": the pattern at the limit");
            check(at);
            for (Runnable r : new Runnable[] {() -> compile(over), () -> check(over)}) {
                RegexException e = assertThrows(RegexException.class, r::run, name);
                assertEquals(RegexException.Kind.TOO_LARGE, e.kind(), name);
                assertEquals(t[3], e.limit(), name);
                assertEquals((int) (Integer) t[4], e.limitValue(), name);
                assertTrue(e.getMessage().contains(String.valueOf(t[4])), e.getMessage());
            }
            assertFalse(valid(over), name + ": valid of the pattern over the limit");
        }
        for (String pattern : new String[] {"(", "a**", "(?<n>a)\\k<m>", "[b-a]", nestOf.apply(Parser.MAX_NESTING - 1) + ")"}) {
            assertEquals(RegexException.Kind.INVALID, assertThrows(RegexException.class, () -> compile(pattern)).kind(),
                    "compile(" + shortOf(pattern) + ")");
            assertEquals(RegexException.Kind.INVALID, assertThrows(RegexException.class, () -> check(pattern)).kind(),
                    "check(" + shortOf(pattern) + ")");
        }
    }

    /** The needs settle in two passes over the instructions and the next characters in three, however deep. */
    @Test
    void theNeedsSettleInFewPasses() {
        deep(() -> {
            String[] patterns = {
                "abc", "(?:a|b)*c", "(?:a?b?)*c", "(?:(?:a+)+b?)*", nest("(?:", "a?b", "){2,3}", 999),
                nest("(?:", "a?b", ")*", 999), nest("(?:", "a?b?", ")*", 999), nest("(?:", "a?b", ")+?", 999),
                nest("(?:x|", "a?b", ")*y?", 500), nest("(?:(?=", "a", ")b?)*", 400), nest("(?:", "a|b", "){0,5}?c", 999),
            };
            for (String pattern : patterns) {
                Program p = program(pattern);
                int needPasses = Compiler.needs(p.insts)[1];
                int quickPasses = Compiler.setQuick(p);
                assertTrue(needPasses <= 2 && quickPasses <= 3,
                        shortOf(pattern) + ": " + needPasses + " and " + quickPasses + " passes");
            }
        });
    }
}
