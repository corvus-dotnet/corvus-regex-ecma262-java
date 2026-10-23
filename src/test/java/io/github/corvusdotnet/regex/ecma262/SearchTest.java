package io.github.corvusdotnet.regex.ecma262;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The tests of the searches for where a match starts, and of the automata they use, ported from the Rust crate's
 * tests.rs: each search finds what trying every position finds.
 */
class SearchTest {
    /** A pattern compiled as every other is, and with the search that tries every position its first set allows. */
    static Regex[] searchedAndPlain(String pattern, String flags) {
        Regex searched;
        try {
            searched = Regex.compile(pattern, flags);
        } catch (RegexException e) {
            return null;
        }
        Search.NO_SEARCH.get()[0] = true;
        Regex plain;
        try {
            plain = Regex.compile(pattern, flags);
            // The program that records the groups is built on the first use, so it is built here.
            plain.captures("");
        } finally {
            Search.NO_SEARCH.get()[0] = false;
        }
        searched.captures("");
        return new Regex[] {searched, plain};
    }

    static List<List<Integer>> captures(Iterable<Captures> all) {
        List<List<Integer>> out = new ArrayList<>();
        for (Captures c : all) {
            List<Integer> one = new ArrayList<>();
            for (int g = 0; g < c.length(); g++) {
                one.add(c.start(g));
                one.add(c.end(g));
            }
            out.add(one);
        }
        return out;
    }

    static List<Integer> spans(Iterable<Match> all) {
        List<Integer> out = new ArrayList<>();
        for (Match m : all) {
            out.add(m.start());
            out.add(m.end());
        }
        return out;
    }

    @Test
    void theSearchesFindWhatEveryPositionFinds() {
        String[][] patterns = {
            {"Sherlock", ""}, {"Sherlock|Street", ""}, {"Sherlock|Holmes|Watson|Irene|Adler|John|Baker", "i"},
            {"Sher[a-z]+|Hol[a-z]+", "i"}, {"[a-q][^u-z]{13}x", ""}, {"[a-zA-Z]+ing", ""},
            {"\\s[a-zA-Z]{0,12}ing\\s", ""}, {"\\w+\\s+Holmes", ""}, {"\\w+\\s+Holmes\\s+\\w+", ""}, {"\\b\\w+n\\b", ""},
            {"\\w+n\\b", ""}, {".*$", ""}, {"(?:.)??$", ""}, {".*\\s", ""}, {"abcd|bc", ""}, {"(?:x|y)*z|q", ""},
            {"\\b(?:the|he)\\b", "i"}, {"Holmes.{0,25}Watson|Watson.{0,25}Holmes", ""}, {"Tom.{1,5}er|er.{1,5}Tom", ""},
            {"\\w+n(?=\\s)", ""}, {"[a-z]+n\\B", "i"}, {"\\w+\\s+Holmes$", "m"}, {"[a-z]+ing\\b|x", ""},
            {".{0,2}(Tom|Sawyer|Huckleberry|Finn)", ""}, {".{2,4}(Tom|Sawyer|Huckleberry|Finn)", ""},
            {"Tom.{10,25}river|river.{10,25}Tom", ""}, {"([A-Za-z]awyer|[A-Za-z]inn)\\s", ""},
            {"[\\w\\.+-]+@[\\w\\.-]+\\.[\\w\\.-]+", ""}, {"\\p{Sm}", ""},
            {"(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9])\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9])", ""},
            {"\u0428\u0435\u0440\u043b\u043e\u043a \u0425\u043e\u043b\u043c\u0441|\u0414\u0436\u043e\u043d"
                    + " \u0423\u043e\u0442\u0441\u043e\u043d", "i"},
            {"\u590f\u6d1b\u514b|\u798f\u5c14\u6469\u65af", ""}, {"sk", "i"}, {"ks", "i"},
            {"\\u{1F600}a|b\\u{10400}", ""}, {"a\\u{1F600}", ""}, {"\\ud83d", ""}, {"x*y", ""}, {"[a-c]*b+c", ""},
            {"(?:ab)?c", ""}, {"\\b\u00e9\\w*", ""}, {"(?<=a)b|c", ""}, {"[\\p{L}--[a-z]]+t", "v"},
            {"[\\q{abc|de}x]y", "v"}, {"\\w+", ""}, {".*", ""}, {".+", "s"}, {"[^\\n]*", ""}, {"[^\\n.]+", ""},
            {"\\p{L}", ""}, {"[\\ud800-\\udfff]", ""}, {"Tom|river|Tomas", ""}, {"ab|a", ""},
            {"sherlock|holmes|k", "i"}, {"\\u{1F600}|\u0428", ""},
            // A start that one occurrence's terms do not allow, and a later occurrence's do, is tried first.
            {"(|([^a]|[^a][^a]){2,4}){2}(\\3){3,5}?x", ""}, {"[^a]{0,3}(?:xx|x)", ""}, {"\\s[a-zA-Z]{0,12}ing\\s", ""},
            // A window of positions read one unit at a time (Adfa.Skip).
            {"\\d{3}-\\d{4}", ""}, {"[0-9]+\\.[0-9]+", ""}, {"(?:\\d+\\.){3}\\d+", ""}, {"\\b\\d\\d:\\d\\d\\b", ""},
            {"[A-Z]{2}\\d{2}", ""}, {"(?:1|22|333)\\.5\\.\\d", ""}, {"\\d{2}[\u00e95]\\d", "u"},
            {"\\d{3}[\u00e9\u00e8\u00ea\u00eb5]", "u"},
            // An occurrence of the one literal every match starts with, which the automaton goes on after.
            {"^abc", "m"}, {"x{0}^abc", ""}, {"\\babc\\b", ""}, {"abc\\s+\\w+", ""},
        };
        String[] alphabet = {"a", "b", "c", "e", "i", "k", "n", "s", "t", "x", "y", "g", "S", "K", " ", "\n", ".", "@",
            "1", "2", "5", "+", "=", "\u017f", "\u212a", "\u00e9", "\u0428", "\u0435", "\u0440", "\u043b", "\u043e",
            "\u043a", "\u0425", "\u590f", "\u6d1b", "\u514b", "\ud83d\ude00", "\ud801\udc00", "ing", "Tom", "river",
            "Holmes", "Sherlock", "sherLOCK", "awyer", "inn", "Watson",
            "\u0428\u0435\u0440\u043b\u043e\u043a \u0425\u043e\u043b\u043c\u0441", "\u590f\u6d1b\u514b", "abc", "de",
            "0", "25", "255.", "192.168.", "333.5.", "12:34", "555-1234", "AB12", "123\u00e9"};
        long[] seed = {0x2545_F491_4F6C_DD1DL};
        java.util.function.LongSupplier next = () -> {
            long s = seed[0];
            s ^= s << 13;
            s ^= s >>> 7;
            s ^= s << 17;
            seed[0] = s;
            return s;
        };
        List<String> texts = new ArrayList<>();
        for (int k = 0; k < 60; k++) {
            int n = 1 + (int) Long.remainderUnsigned(next.getAsLong(), 300);
            StringBuilder t = new StringBuilder();
            for (int i = 0; i < n; i++) {
                t.append(alphabet[(int) Long.remainderUnsigned(next.getAsLong(), alphabet.length)]);
            }
            texts.add(t.toString());
        }
        for (String[] pf : patterns) {
            for (String mode : new String[] {"", "u"}) {
                String flags = pf[1].equals("v") ? "v" : pf[1] + mode;
                Regex[] both = searchedAndPlain(pf[0], flags);
                if (both == null) {
                    continue;
                }
                Regex searched = both[0];
                Regex plain = both[1];
                assertEquals(Search.Plan.SCAN, plain.prog.search.plan);
                for (String text : texts) {
                    Utf8Text u8 = Utf8Text.of(text);
                    String where = "/" + pf[0] + "/" + flags + " on " + text;
                    assertEquals(captures(plain.capturesAll(u8)), captures(searched.capturesAll(u8)),
                            where + " over UTF-8");
                    assertEquals(spans(plain.findAll(u8)), spans(searched.findAll(u8)),
                            where + " over UTF-8, without groups");
                    assertEquals(captures(plain.capturesAll(text)), captures(searched.capturesAll(text)),
                            where + " over UTF-16");
                    assertEquals(spans(plain.findAll(text)), spans(searched.findAll(text)), where + " over UTF-16");
                }
            }
        }
    }

    /**
     * The faults the start-searches family of the test data and its fuzzer (fuzz.js there) found, each pattern over the
     * text that showed it: the search finds what every position finds, over UTF-8 and over UTF-16.
     */
    @Test
    void theSearchesFindTheStartsTheFuzzerFound() {
        String[][] cases = {
            {"\\b\\w+n\\b", "", "a nation"}, {"\\b\\w+n\\b", "", "an nation nn nan noon"},
            {"\\w+\\s+Holmes", "", "Holmes Holmes"}, {"\\b\\d*\\s*a\\b", "", " a"},
            {"\\b\\d*\\W*x\\b", "s", " \u00e9\u00e9x"}, {"\\b\\d*\\W*x", "", "a1 x"},
            {"\\b\\d*\\W+\\w+ab", "", "ab0 aab"},
            {"\\b\\w*[\\u{1F600}-\\u{1F64F}]*S\\B", "u", "\ud83d\ude00S_n\ud83d\ude00S"},
            {"\\W+[a-z]*\\W*a", "m", " a"}, {"[a-z\\u00e9\\u00e8]+\\d*[a-z]+on", "i", " ONaON"},
            {"\\w+?[^\\w\\s]*\\d+?n", "im", "000n"}, {"\\w*\\W+-$", "", " a\u00a0-a--"},
            {"\\w*[^\\w\\s]+k$", "", "---_-ka.k"}, {"\\b[0-9a-f]*\\u00e9t", "", "\n\u00e9t0a\u00e9t"},
            {".{1,3}(?:on|\\.).", "", "\ud83d\ude00\ud83d\ude00\ud83d\ude00ona on"},
            {"\\b[^\\n]{1,3}(?:k|\\.)\\b", "im", "k \ud83d\ude00nk"},
            {"[^\\n]{1,3}(?:\\u00e9|a)", "", "\u4e2d\u4e2d\u4e2da"}, {"\\B[^\\w\\s]{1,3}(?:a|n)", "", "a\ud83d\ude00a"},
        };
        for (String[] c : cases) {
            for (String mode : new String[] {"", "u"}) {
                Regex[] both = searchedAndPlain(c[0], c[1] + mode);
                if (both == null) {
                    continue;
                }
                String where = "/" + c[0] + "/" + c[1] + mode + " on " + c[2];
                assertEquals(spans(both[1].findAll(c[2])), spans(both[0].findAll(c[2])), where + " over UTF-16");
                Utf8Text u8 = Utf8Text.of(c[2]);
                assertEquals(spans(both[1].findAll(u8)), spans(both[0].findAll(u8)), where + " over UTF-8");
            }
        }
    }

    /** A literal with a byte rare in text is found by the search for that byte, wherever in the literal it is. */
    @Test
    void aLiteralWithARareByteIsFoundAsAnyOther() {
        SearchPrimitivesTest.Xorshift seed = new SearchPrimitivesTest.Xorshift(0x2f6b1c3d);
        for (String literal : new String[] {"zqj", "aqj", "qq", "Xa", "abZ", "jj j"}) {
            Regex re = Regex.compile(literal, "g");
            for (int round = 0; round < 200; round++) {
                StringBuilder t = new StringBuilder();
                for (int k = seed.below(200); k > 0; k--) {
                    t.append("abqjzZX ".charAt(seed.below(8)));
                }
                String text = t.toString();
                List<Integer> want = new ArrayList<>();
                for (int i = text.indexOf(literal); i >= 0; i = text.indexOf(literal, i + literal.length())) {
                    want.add(i);
                }
                List<Integer> got = new ArrayList<>();
                List<Integer> got8 = new ArrayList<>();
                for (Match m : re.findAll(text)) {
                    got.add(m.start());
                }
                for (Match m : re.findAll(Utf8Text.of(text))) {
                    got8.add(m.start());
                }
                assertEquals(want, got, "/" + literal + "/ in " + text);
                assertEquals(want, got8, "/" + literal + "/ in " + text + " over UTF-8");
            }
        }
    }

    /**
     * {@code .*} with the s flag is answered without reading the text: from where the search starts (a code point's
     * start with the u or v flag, so a start between the halves of a pair moves back to the pair's start; any code unit
     * without them) to the end. Each answer is compared with the matcher's, which a group in the pattern makes the
     * search use, and with that rule written out, over UTF-16 text with lone halves anywhere, the last unit among them,
     * and every start ({@code a_run_of_the_dot_with_the_s_flag_is_the_rest_of_the_text} of the crate's api.rs).
     */
    @Test
    void aRunOfTheDotWithTheSFlagIsTheRestOfTheText() {
        String[] texts = {"", "a\uD800", "a\uDC00", "\uDC00a\uD83D\uDE00", "\uD83D\uDE00",
            "\uDC00b\uD83D\uDE00\uD800c\uDBFF"};
        for (String flags : new String[] {"s", "su", "sv", "sy", "suy"}) {
            boolean unicode = flags.contains("u") || flags.contains("v");
            Regex shortcut = Regex.compile(".*", flags);
            Regex matcher = Regex.compile("(.*)", flags);
            Regex plus = Regex.compile(".+", flags);
            Regex plusMatcher = Regex.compile("(.+)", flags);
            assertEquals(Search.Shape.LOOP, shortcut.prog.search.shape);
            for (String text : texts) {
                for (int start = 0; start <= text.length(); start++) {
                    boolean midPair = start > 0 && start < text.length() && Character.isLowSurrogate(text.charAt(start))
                            && Character.isHighSurrogate(text.charAt(start - 1));
                    int from = unicode && midPair ? start - 1 : start;
                    String where = "/.*/" + flags + " over " + text + " from " + start;
                    assertEquals(spanOf(matcher.find(text, start)), spanOf(shortcut.find(text, start)), where);
                    assertEquals(List.of(from, text.length()), spanOf(shortcut.find(text, start)), where);
                    assertEquals(spanOf(plusMatcher.find(text, start)), spanOf(plus.find(text, start)), where);
                    assertEquals(from < text.length() ? List.of(from, text.length()) : null,
                            spanOf(plus.find(text, start)), where);
                }
                // The global search: the rest of the text, then the empty match at its end.
                List<Integer> all = spans(shortcut.findAll(text));
                assertEquals(spans(matcher.findAll(text)), all, "/.*/" + flags + " global over " + text);
                if (!flags.contains("y")) {
                    assertEquals(text.isEmpty() ? List.of(0, 0) : List.of(0, text.length(), text.length(), text.length()),
                            all, "/.*/" + flags + " global over " + text);
                }
            }
        }
    }

    static List<Integer> spanOf(Match m) {
        return m == null ? null : List.of(m.start(), m.end());
    }

    /** The forward automaton and the window of the skip are taken where the plan is the scan, and not without it. */
    @Test
    void theForwardAutomatonIsKeptForTheScan() {
        Regex re = Regex.compile("(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9])\\.){3}"
                + "(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9])");
        assertNotEquals(null, re.prog.forward());
        assertNotEquals(null, re.prog.skip);
        Search.NO_SEARCH.get()[0] = true;
        try {
            assertEquals(null, Regex.compile("\\d{3}-\\d{4}").prog.forward());
        } finally {
            Search.NO_SEARCH.get()[0] = false;
        }
    }
}
