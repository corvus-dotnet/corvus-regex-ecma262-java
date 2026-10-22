package io.github.corvusdotnet.regex.ecma262;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The public surface: what each member gives, over UTF-8 and UTF-16, as RegExp and String.prototype give it (api.rs). */
class ApiTest {
    static Utf8Text u8(String s) {
        return Utf8Text.of(s);
    }

    static String escape(String units) {
        StringBuilder out = new StringBuilder();
        for (char u : units.toCharArray()) {
            out.append(u >= 0x20 && u < 0x7f && u != '\\' ? String.valueOf(u) : String.format("\\u%04x", (int) u));
        }
        return out.toString();
    }

    static List<Integer> starts(Iterable<Match> matches) {
        List<Integer> out = new ArrayList<>();
        for (Match m : matches) {
            out.add(m.start());
        }
        return out;
    }

    static List<int[]> spans(Iterable<Match> matches) {
        List<int[]> out = new ArrayList<>();
        for (Match m : matches) {
            out.add(new int[] {m.start(), m.end()});
        }
        return out;
    }

    static void assertSpans(List<int[]> want, List<int[]> got, String message) {
        assertEquals(want.size(), got.size(), message);
        for (int k = 0; k < want.size(); k++) {
            assertArrayEquals(want.get(k), got.get(k), message);
        }
    }

    @Test
    void flagsAreReadAsNewRegExpReadsThem() {
        Flags f = Flags.parse("dgimsuy");
        assertTrue(f.hasIndices() && f.global() && f.ignoreCase() && f.multiline() && f.dotAll() && f.unicode()
                && f.sticky());
        assertEquals("dgimsuy", f.toString());
        assertEquals("v", Flags.parse("v").toString());
        for (String bad : new String[] {"uv", "gg", "x", "u "}) {
            RegexException e = assertThrows(RegexException.class, () -> Regex.compile("a", bad), bad);
            assertEquals(RegexException.Kind.FLAGS, e.kind(), bad);
        }
        assertEquals("/a+/gi", Regex.compile("a+", "gi").toString());
        assertEquals(Flags.parse("ig"), Flags.parse("gi"));
    }

    @Test
    void aPatternIsReadByTheGrammarOfItsFlags() {
        // An identity escape is Annex B's alone, and set notation the v flag's alone.
        Regex.compile("\\&", "");
        assertEquals(RegexException.Kind.INVALID,
                assertThrows(RegexException.class, () -> Regex.compile("\\&", "u")).kind());
        assertTrue(Regex.compile("[\\w--\\d]", "v").isMatch("a"));
        assertFalse(Regex.compile("^[\\w--\\d]$", "v").isMatch("1"));
        assertThrows(RegexException.class, () -> Regex.compile("^[\\w--\\d]$", "u"));
        assertTrue(Regex.isValid("\\p{RGI_Emoji}", "v"));
        assertFalse(Regex.isValid("\\p{RGI_Emoji}", "u"));
        assertFalse(Regex.isValid("[^\\p{RGI_Emoji}]", "v"));
        assertFalse(Regex.isValid("a", "uv"));
    }

    @Test
    void findAndFindAt() {
        Regex re = Regex.compile("\\d+", "");
        Match m = re.find("ab 12 345");
        assertEquals(3, m.start());
        assertEquals(5, m.end());
        assertEquals("12", m.value());
        assertEquals(2, m.length());
        assertFalse(m.isEmpty());
        assertEquals("345", re.find("ab 12 345", 5).value());
        assertNull(re.find("ab 12 345", 10));
        assertNull(re.find("none"));
        assertEquals("345", re.find(u8("ab 12 345"), 5).value());
        assertEquals("12", new String(re.find("ab 12 345".getBytes(StandardCharsets.UTF_8)).bytes(),
                StandardCharsets.UTF_8));
        assertNull(re.find("ab 12 345".getBytes(StandardCharsets.UTF_8), 10));
        assertThrows(IndexOutOfBoundsException.class, () -> re.find("a", -1));
    }

    @Test
    void theStickyFlagAnchorsEachMatchAtItsStart() {
        Regex re = Regex.compile("a", "y");
        assertNull(re.find("ba"));
        assertEquals(1, re.find("ba", 1).start());
        assertFalse(re.isMatch("ba"));
        assertTrue(re.isMatch("ba", 1));
        assertEquals(Arrays.asList(0, 1), starts(re.findAll("aab")));
        assertEquals(Arrays.asList(0, 1), starts(re.findAll(u8("aab"))));
    }

    @Test
    void aGlobalSearchStepsOverAnEmptyMatchByACharacter() {
        Regex re = Regex.compile("", "u");
        assertEquals(Arrays.asList(0, 1, 5, 6), starts(re.findAll(u8("a\uD83D\uDE00b"))));
        assertEquals(Arrays.asList(0, 1, 3, 4), starts(re.findAll("a\uD83D\uDE00b")));
        // Without the u flag the step is a code unit, which over UTF-8 stands between the halves at the third byte.
        re = Regex.compile("", "");
        assertEquals(Arrays.asList(0, 1, 3, 5, 6), starts(re.findAll(u8("a\uD83D\uDE00b"))));
        assertEquals(Arrays.asList(0, 1, 2, 3, 4), starts(re.findAll("a\uD83D\uDE00b")));
    }

    @Test
    void capturesAndNames() {
        Regex re = Regex.compile("(?<y>\\d{4})-(?<m>\\d{2})(?:-(?<d>\\d{2}))?", "");
        assertEquals(4, re.capturesLength());
        assertEquals(Arrays.asList("y", "m", "d"), re.groupNames());
        Captures caps = re.captures("on 2026-10!");
        assertEquals("2026-10", caps.get(0).value());
        assertEquals("2026-10", caps.match().value());
        assertEquals("2026", caps.get("y").value());
        assertEquals("2026", caps.group("y"));
        assertEquals(8, caps.get("m").start());
        assertEquals(10, caps.get("m").end());
        assertNull(caps.get("d"));
        assertNull(caps.group("d"));
        assertNull(caps.get("nothing"));
        assertNull(caps.get(3));
        assertNull(caps.get(4));
        assertNull(caps.group(3));
        assertEquals(-1, caps.start(3));
        assertEquals("[3..10, 3..7, 8..10, None]", caps.toString());
        // Two groups of one name in separate alternatives: the one that took part.
        re = Regex.compile("(?<x>a)|(?<x>b)", "");
        assertEquals(0, re.captures("b").get("x").start());
        assertEquals(2, re.captures("b").named("x"));
        Captures c8 = re.captures(u8("ab"), 1);
        assertEquals("b", c8.group("x"));
    }

    @Test
    void theGroupsOfARepetitionAreThoseOfItsLastIteration() {
        Regex re = Regex.compile("(?:(a)|b)+", "");
        Captures caps = re.captures("ab");
        assertNull(caps.get(1), "the last iteration took b, so group 1 is cleared");
        Captures locs = re.newCaptures();
        assertTrue(re.captures(locs, "xba", 0));
        assertEquals(1, locs.start(0));
        assertEquals(3, locs.end(0));
        assertEquals(2, locs.start(1));
        assertEquals(3, locs.end(1));
        assertFalse(re.captures(locs, "xyz", 0));
        assertTrue(re.captures(locs, u8("xba"), 0));
        assertEquals("a", locs.group(1));
        assertThrows(IllegalArgumentException.class,
                () -> re.captures(Regex.compile("(a)").newCaptures(), "a", 0));
    }

    @Test
    void replaceSubstitutesAsGetSubstitutionDoes() {
        Regex re = Regex.compile("(\\w)(\\w)?", "");
        assertEquals("[ba] cd", re.replace("ab cd", "[$2$1]"));
        assertEquals("[ba] [c]", re.replaceAll("ab c", "[$2$1]"));
        assertEquals("[ba] [c]", Regex.compile("(\\w)(\\w)?", "g").replace("ab c", "[$2$1]"));
        re = Regex.compile("b", "");
        assertEquals("a<b|a|c|$|$1|$<n>>c", re.replace("abc", "<$&|$`|$'|$$|$1|$<n>>"));
        re = Regex.compile("(?<n>b)", "");
        assertEquals("a<b||b|b0|$<nc", re.replace("abc", "<$<n>|$<m>|$01|$10|$<n"));
        // The name runs to the next `>`, wherever it is.
        assertEquals("a<c", re.replace("abc", "<$<n|$1>"));
        Regex names = Regex.compile("(?<first>\\w+) (?<last>\\w+)", "");
        assertEquals("LOVELACE", names.replace("Ada Lovelace", c -> c.group("last").toUpperCase()));
        // Nothing matched: the text itself.
        String x = "x";
        assertSame(x, names.replace(x, "y"));
        assertSame(x, names.replace(x, c -> "y"));
        // Over UTF-8.
        assertEquals("[ba] [c]", Regex.compile("(\\w)(\\w)?", "g").replace(u8("ab c"), "[$2$1]").toString());
        assertEquals("[ba] [c]", Regex.compile("(\\w)(\\w)?").replaceAll(u8("ab c"), "[$2$1]").toString());
        assertEquals("LOVELACE", names.replace(u8("Ada Lovelace"), c -> c.group("last").toUpperCase()).toString());
        assertEquals("LOVELACE", new String(names.replace("Ada Lovelace".getBytes(StandardCharsets.UTF_8),
                c -> c.group("last").toUpperCase()), StandardCharsets.UTF_8));
        byte[] bytes = {'x'};
        byte[] same = names.replace(bytes, "y");
        assertArrayEquals(bytes, same);
        assertFalse(bytes == same, "a new array even when nothing matched");
        Utf8Text t = u8("x");
        assertSame(t, names.replace(t, "y"));
        assertEquals("<é>", new String(Regex.compile("é", "u").replaceAll("é".getBytes(StandardCharsets.UTF_8), "<$&>"),
                StandardCharsets.UTF_8));
    }

    @Test
    void splitSplicesTheGroupsIn() {
        Regex re = Regex.compile("(-)|(\\+)", "");
        assertEquals(Arrays.asList("a", "-", null, "b", null, "+", "c"), re.split("a-b+c"));
        assertEquals(2, re.split("a-b+c", 2).size());
        assertEquals(0, re.split("a-b+c", 0).size());
        List<byte[]> pieces = re.split("a-b+c".getBytes(StandardCharsets.UTF_8));
        assertEquals(7, pieces.size());
        assertNull(pieces.get(2));
        assertEquals("+", new String(pieces.get(5), StandardCharsets.UTF_8));
        assertEquals(3, re.split("a-b+c".getBytes(StandardCharsets.UTF_8), 3).size());
        List<Utf8Text> texts = re.split(u8("a-b+c"));
        assertEquals("c", texts.get(6).toString());
        assertEquals(1, re.split(u8("a-b+c"), 1).size());
        // An empty match at the start or the end of the text splits nothing, and the empty text is one piece unless
        // the pattern matches it.
        re = Regex.compile("", "");
        assertEquals(2, re.split("ab").size());
        assertEquals(0, re.split("").size());
        assertEquals(1, Regex.compile("x", "").split("").size());
    }

    @Test
    void utf16TextWithLoneSurrogates() {
        String text = "a\uD800b\uD83D\uDE00";
        // With the u flag a lone surrogate is a code point of its own, and a pair is one character.
        assertTrue(Regex.compile("^a\\ud800b.$", "u").isMatch(text));
        assertTrue(Regex.compile("^a.b..$", "").isMatch(text), "without the u flag a pair is two units");
        assertFalse(Regex.compile("\\udc00", "u").isMatch("\uD801\uDC00"),
                "the half of a whole pair is not a lone surrogate");
        Regex re = Regex.compile("(?<x>\\w)", "");
        assertEquals("a", re.captures(text).group("x"));
        assertEquals("<a>\uD800<b>\uD83D\uDE00", re.replaceAll(text, "<$1>"));
        // A lone surrogate in the pattern itself.
        assertTrue(Regex.compile("\uD800", "u").isMatch(text));
        assertThrows(IllegalArgumentException.class, () -> Utf8Text.of(text));
    }

    @Test
    void aBackreferenceDoesNotEndInsideASurrogatePairWithTheUFlag() {
        // The group holds a lone high surrogate; what follows is a pair, one code point that is not it.
        assertFalse(Regex.compile("^(.)\\1", "u").isMatch("\uD800\uD800\uDC00"));
        assertTrue(Regex.compile("^(.)\\1", "").isMatch("\uD800\uD800\uDC00"));
    }

    @Test
    void aHalfCharacterOverUtf8IsWrittenAsAReplacementCharacter() {
        // Without the u flag the pattern matches the high half of the character, which UTF-8 cannot hold.
        Regex re = Regex.compile("\\ud83d", "");
        Match m = re.find(u8("a\uD83D\uDE00"));
        assertEquals(1, m.start());
        assertEquals(3, m.end());
        assertEquals("\uFFFD", m.value());
        assertEquals("ax\uFFFD", re.replace(u8("a\uD83D\uDE00"), "x").toString());
        assertEquals("\uFFFD", Regex.compile("\\ude00", "").find(u8("\uD83D\uDE00")).value());
    }

    @Test
    void modifiersAndFlagsMeanTheSame() {
        String[][] cases = {{"i", "k"}, {"", "(?i:k)"}};
        for (String[] c : cases) {
            Regex re = Regex.compile("^" + c[1] + "$", c[0] + "u");
            assertTrue(re.isMatch("K") && re.isMatch("\u212A"), "/" + c[1] + "/" + c[0] + "u");
        }
        assertTrue(Regex.compile("^b$", "m").isMatch("a\nb\nc"));
        assertTrue(Regex.compile("a.b", "s").isMatch("a\nb"));
        assertFalse(Regex.compile("a.b", "").isMatch("a\nb"));
    }

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
            for (String text : texts) {
                for (int start = 0; start <= text.length(); start++) {
                    boolean midPair = start > 0 && start < text.length() && Character.isLowSurrogate(text.charAt(start))
                            && Character.isHighSurrogate(text.charAt(start - 1));
                    int from = unicode && midPair ? start - 1 : start;
                    String where = "/.*/" + flags + " over " + escape(text) + " from " + start;
                    Match got = shortcut.find(text, start);
                    Match want = matcher.find(text, start);
                    assertEquals(want.start(), got.start(), where);
                    assertEquals(want.end(), got.end(), where);
                    assertEquals(from, got.start(), where);
                    assertEquals(text.length(), got.end(), where);
                    got = plus.find(text, start);
                    want = plusMatcher.find(text, start);
                    if (from < text.length()) {
                        assertEquals(from, got.start(), where);
                        assertEquals(text.length(), got.end(), where);
                        assertEquals(want.start(), got.start(), where);
                    } else {
                        assertNull(got, where);
                        assertNull(want, where);
                    }
                }
                // The global search: the rest of the text, then the empty match at its end.
                List<int[]> all = spans(shortcut.findAll(text));
                assertSpans(spans(matcher.findAll(text)), all, flags);
                if (!flags.contains("y")) {
                    List<int[]> expected = text.isEmpty() ? Arrays.asList(new int[] {0, 0})
                            : Arrays.asList(new int[] {0, text.length()}, new int[] {text.length(), text.length()});
                    assertSpans(expected, all, flags);
                }
            }
        }
    }

    @Test
    void theIteratorsEndAndCanBeAskedAgain() {
        Regex re = Regex.compile("\\d", "g");
        java.util.Iterator<Match> it = re.findAll("1a2").iterator();
        assertTrue(it.hasNext());
        assertTrue(it.hasNext());
        assertEquals(0, it.next().start());
        assertEquals(2, it.next().start());
        assertFalse(it.hasNext());
        assertThrows(java.util.NoSuchElementException.class, it::next);
        int n = 0;
        for (Captures c : re.capturesAll(u8("1a2"))) {
            assertEquals(String.valueOf(n + 1), c.group(0));
            n++;
        }
        assertEquals(2, n);
        n = 0;
        for (Captures c : re.capturesAll("1a2".getBytes(StandardCharsets.UTF_8))) {
            n += c.length();
        }
        assertEquals(2, n);
        assertEquals(2, spans(re.findAll("1a2".getBytes(StandardCharsets.UTF_8))).size());
    }

    @Test
    void bytesThatAreNotUtf8AreRefused() {
        Regex re = Regex.compile("a");
        byte[][] bad = {{(byte) 0xC0, (byte) 0x80}, {'a', (byte) 0xED, (byte) 0xA0, (byte) 0x80},
            {(byte) 0xF4, (byte) 0x90, (byte) 0x80, (byte) 0x80}, {(byte) 0xE2, (byte) 0x82}, {(byte) 0x80},
            {(byte) 0xF8, (byte) 0x88, (byte) 0x80, (byte) 0x80, (byte) 0x80}, {(byte) 0xE0, (byte) 0x80, (byte) 0x80},
            {(byte) 0xF0, (byte) 0x80, (byte) 0x80, (byte) 0x80}, {(byte) 0xC3, 'a'}, {(byte) 0xE2, (byte) 0x82, 'a'}};
        for (byte[] b : bad) {
            assertThrows(IllegalArgumentException.class, () -> re.isMatch(b), Arrays.toString(b));
        }
        String s = "0123456789abcdef\u00e9\u20ac\uD83D\uDE00 and some ASCII past eight bytes";
        assertTrue(re.isMatch(s.getBytes(StandardCharsets.UTF_8)));
        assertEquals(s, Utf8Text.of(s.getBytes(StandardCharsets.UTF_8)).toString());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Utf8Text.of(new byte[] {'0', '1', '2', '3', '4', '5', '6', '7', '8', (byte) 0xFF}));
        assertTrue(e.getMessage().contains("at byte 9"), e.getMessage());
    }

    @Test
    void theErrorsSayWhatIsWrong() {
        RegexException e = assertThrows(RegexException.class, () -> Regex.compile("(", "u"));
        assertEquals(RegexException.Kind.INVALID, e.kind());
        assertNull(e.limit());
        assertEquals(0, e.limitValue());
        assertTrue(e.getMessage().startsWith("ecmaregex: "), e.getMessage());
        e = assertThrows(RegexException.class, () -> Flags.parse("gg"));
        assertEquals("the flag 'g' is given twice in \"gg\"", e.getMessage());
        e = assertThrows(RegexException.class, () -> Flags.parse("\n"));
        assertEquals("invalid flag '\\n' in \"\\n\"", e.getMessage());
        e = assertThrows(RegexException.class, () -> Regex.compile("a".repeat(Regex.MAX_PATTERN_LENGTH + 1)));
        assertEquals(RegexException.Kind.TOO_LARGE, e.kind());
        assertEquals(Limit.LENGTH, e.limit());
        assertEquals("length", e.limit().label());
        assertEquals(Regex.MAX_PATTERN_LENGTH, e.limitValue());
    }

    @Test
    void thePatternAndItsFlagsAreKept() {
        Regex re = Regex.compile("a(b)", "gi");
        assertEquals("a(b)", re.pattern());
        assertEquals("gi", re.flags().toString());
        assertEquals(2, re.capturesLength());
        assertEquals(0, re.groupNames().size());
        assertEquals("", Regex.compile("x").flags().toString());
        assertEquals("Match(0..2)", re.find("ab", 0).toString());
        assertNull(re.find("ab").bytes());
    }
}
