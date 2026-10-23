import io.github.corvusdotnet.regex.ecma262.Captures;
import io.github.corvusdotnet.regex.ecma262.Match;
import io.github.corvusdotnet.regex.ecma262.MatchLimitException;
import io.github.corvusdotnet.regex.ecma262.Regex;
import io.github.corvusdotnet.regex.ecma262.RegexException;
import io.github.corvusdotnet.regex.ecma262.Utf8Text;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Runs the engine over the ECMA-262 regular expression test data (github.com/corvus-dotnet/ecma262-regex-test-data), as
 * the runner of the Rust crate does (tools/src/bin/dataset.rs there), with the same judging rules.
 *
 * <pre>
 *   java -cp target/classes:tools/dataset/out Dataset &lt;data directory&gt; &lt;wrong-cases output file&gt; [file filter] [utf16|utf8|both]
 * </pre>
 *
 * Each record is asked about under each grammar V8 found it valid in: the record's flags with u added (the
 * {@code unicode} answers), the record's flags alone (the {@code legacy} answers) and, in the file that has them, the
 * flags with v added (the {@code unicode_sets} answers). A text is matched as UTF-16 and as UTF-8; a text with a lone
 * surrogate has no UTF-8 form and is matched as UTF-16 alone. Records marked v8_deviation are left out. The pattern is
 * the record's own, lone surrogates and all. {@code captures.jsonl} is judged on exec, the global search, the sticky
 * flag, replace and split. {@code ill-formed-utf8.jsonl} holds bytes that are not UTF-8, each one answer over UTF-8
 * under each grammar the pattern is valid in, whose right answer is a refusal: the {@link IllegalArgumentException} of
 * the methods that take a {@code byte[]}.
 */
public final class Dataset {
    private static final Duration LIMIT = Duration.ofSeconds(2);
    private static final String REPLACEMENT = "<$&|$1|$2|$10|$<a>|$$|$`|$'>";

    static final class Counts {
        final AtomicLong right = new AtomicLong();
        final AtomicLong wrong = new AtomicLong();
        final AtomicLong refused = new AtomicLong();
        final AtomicLong accepted = new AtomicLong();
        final AtomicLong limited = new AtomicLong();
        final AtomicLong errors = new AtomicLong();

        void add(Counts o) {
            right.addAndGet(o.right.get());
            wrong.addAndGet(o.wrong.get());
            refused.addAndGet(o.refused.get());
            accepted.addAndGet(o.accepted.get());
            limited.addAndGet(o.limited.get());
            errors.addAndGet(o.errors.get());
        }
    }

    static boolean utf16;
    static boolean utf8;

    public static void main(String[] args) throws Exception {
        Path directory = Paths.get(args[0]);
        Path wrongPath = Paths.get(args[1]);
        String filter = args.length > 2 ? args[2] : "*.jsonl";
        String encodings = args.length > 3 ? args[3] : "both";
        utf16 = encodings.equals("both") || encodings.equals("utf16");
        utf8 = encodings.equals("both") || encodings.equals("utf8");
        List<Path> files;
        try (Stream<Path> s = Files.list(directory)) {
            files = s.filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .filter(p -> glob(filter, p.getFileName().toString())).sorted().collect(Collectors.toList());
        }
        Counts total = new Counts();
        List<String> wrongCases = Collections.synchronizedList(new ArrayList<>());
        AtomicLong records = new AtomicLong();
        AtomicLong skipped = new AtomicLong();
        long clock = System.nanoTime();
        int threads = Integer.getInteger("threads", 4);
        for (Path file : files) {
            String name = file.getFileName().toString();
            Counts counts = new Counts();
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            AtomicInteger next = new AtomicInteger();
            List<Thread> workers = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                Thread w = new Thread(() -> {
                    List<String> wrong = new ArrayList<>();
                    while (true) {
                        int k = next.getAndIncrement();
                        if (k >= lines.size()) {
                            break;
                        }
                        Object r;
                        try {
                            r = new Json(lines.get(k)).parse();
                        } catch (RuntimeException e) {
                            throw new RuntimeException(name + ":" + (k + 1) + ": " + e.getMessage(), e);
                        }
                        if (Boolean.TRUE.equals(get(r, "v8_deviation"))) {
                            skipped.incrementAndGet();
                            continue;
                        }
                        records.incrementAndGet();
                        judgeRecord(name, r, counts, wrong);
                    }
                    wrongCases.addAll(wrong);
                });
                workers.add(w);
                w.start();
            }
            for (Thread w : workers) {
                w.join();
            }
            System.out.printf("%-24s right %10d  wrong %6d  refused-valid %4d  accepted-invalid %4d  limit %4d  %6.1fs%n",
                    name, counts.right.get(), counts.wrong.get(), counts.refused.get(), counts.accepted.get(),
                    counts.limited.get(), (System.nanoTime() - clock) / 1e9);
            total.add(counts);
        }
        List<String> cases = new ArrayList<>(wrongCases);
        Collections.sort(cases);
        Files.write(wrongPath, (String.join("\n", cases) + (cases.isEmpty() ? "" : "\n")).getBytes(StandardCharsets.UTF_8));
        System.out.println();
        System.out.printf("records %d (skipped %d deviations)  right %d  wrong %d  refused-valid %d  accepted-invalid %d  limit %d  errors %d  in %.1fs%n",
                records.get(), skipped.get(), total.right.get(), total.wrong.get(), total.refused.get(),
                total.accepted.get(), total.limited.get(), total.errors.get(), (System.nanoTime() - clock) / 1e9);
        long bad = total.wrong.get() + total.refused.get() + total.accepted.get() + total.errors.get() + total.limited.get();
        System.exit(bad == 0 ? 0 : 1);
    }

    /** Whether a file name matches a filter with {@code *} wildcards. */
    static boolean glob(String pattern, String name) {
        String[] parts = pattern.split("\\*", -1);
        if (parts.length == 1) {
            return pattern.equals(name);
        }
        String rest = name;
        for (int k = 0; k < parts.length; k++) {
            String part = parts[k];
            if (k == 0) {
                if (!rest.startsWith(part)) {
                    return false;
                }
                rest = rest.substring(part.length());
            } else if (k == parts.length - 1) {
                return rest.endsWith(part);
            } else {
                int at = rest.indexOf(part);
                if (at < 0) {
                    return false;
                }
                rest = rest.substring(at + part.length());
            }
        }
        return true;
    }

    static boolean hasLoneSurrogate(String s) {
        for (int k = 0; k < s.length(); k++) {
            char u = s.charAt(k);
            if (Character.isHighSurrogate(u)) {
                if (k + 1 < s.length() && Character.isLowSurrogate(s.charAt(k + 1))) {
                    k++;
                    continue;
                }
                return true;
            }
            if (Character.isLowSurrogate(u)) {
                return true;
            }
        }
        return false;
    }

    /** The string with each lone surrogate written as U+FFFD. */
    static String lossy(String s) {
        if (!hasLoneSurrogate(s)) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length());
        for (int k = 0; k < s.length(); k++) {
            char u = s.charAt(k);
            if (Character.isHighSurrogate(u) && k + 1 < s.length() && Character.isLowSurrogate(s.charAt(k + 1))) {
                out.append(u).append(s.charAt(k + 1));
                k++;
            } else if (Character.isSurrogate(u)) {
                out.append('�');
            } else {
                out.append(u);
            }
        }
        return out.toString();
    }

    static String escape(String units) {
        StringBuilder out = new StringBuilder();
        for (int k = 0; k < units.length(); k++) {
            char u = units.charAt(k);
            if (u >= 0x20 && u < 0x7f && u != '\\') {
                out.append(u);
            } else {
                out.append(String.format("\\u%04x", (int) u));
            }
        }
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    static Object get(Object o, String name) {
        return o instanceof Map ? ((Map<String, Object>) o).get(name) : null;
    }

    @SuppressWarnings("unchecked")
    static List<Object> array(Object o) {
        return o instanceof List ? (List<Object>) o : null;
    }

    /** The modes a record is asked about: the flags to add, the name of the answers, and whether V8 found it valid. */
    static List<Object[]> modes(Object r) {
        Object valid = get(r, "valid");
        List<Object[]> modes = new ArrayList<>();
        modes.add(new Object[] {"u", "unicode", get(valid, "unicode")});
        modes.add(new Object[] {"", "legacy", get(valid, "legacy")});
        Object v = get(valid, "unicode_sets");
        if (v != null) {
            modes.add(new Object[] {"v", "unicode_sets", v});
        }
        return modes;
    }

    static void judgeRecord(String name, Object r, Counts counts, List<String> wrong) {
        String pattern = (String) get(r, "pattern");
        if (name.equals("captures.jsonl")) {
            if (utf16) {
                judgeCaptures(r, pattern, counts, wrong);
            }
            return;
        }
        if (name.equals("ill-formed-utf8.jsonl")) {
            if (utf8) {
                judgeIllFormed(r, pattern, counts, wrong);
            }
            return;
        }
        judgeTests(name, r, pattern, counts, wrong);
    }

    static void judgeIllFormed(Object r, String pattern, Counts counts, List<String> wrong) {
        String name = "ill-formed-utf8.jsonl";
        String flags = (String) get(r, "flags");
        String shown = escape(pattern);
        for (Object[] mode : modes(r)) {
            String modeFlags = flags + mode[0];
            boolean expectedValid = (Boolean) mode[2];
            String head = name + "\t/" + shown + "/" + modeFlags;
            Regex re;
            try {
                re = Regex.compile(pattern, modeFlags);
            } catch (Throwable e) {
                if (!(e instanceof RegexException)) {
                    counts.errors.incrementAndGet();
                }
                if (expectedValid) {
                    counts.refused.incrementAndGet();
                    wrong.add("REFUSED\t" + head + "\t" + firstLine(String.valueOf(e.getMessage())));
                }
                continue;
            }
            if (!expectedValid) {
                counts.accepted.incrementAndGet();
                wrong.add("ACCEPTED-INVALID\t" + head);
                continue;
            }
            List<Object> tests = array(get(r, "tests"));
            if (tests == null) {
                continue;
            }
            for (Object t : tests) {
                String hex = (String) get(t, "bytes");
                byte[] bytes = new byte[hex.length() / 2];
                for (int k = 0; k < bytes.length; k++) {
                    bytes[k] = (byte) Integer.parseInt(hex, 2 * k, 2 * k + 2, 16);
                }
                try {
                    boolean got = re.isMatch(bytes, LIMIT);
                    counts.wrong.incrementAndGet();
                    wrong.add("WRONG\t" + head + "\t" + hex + "\tutf8\tread as UTF-8 (" + got + "), not refused");
                } catch (MatchLimitException e) {
                    counts.limited.incrementAndGet();
                    wrong.add("LIMIT\t" + head + "\t" + hex + "\tutf8");
                } catch (RegexException e) {
                    counts.errors.incrementAndGet();
                    wrong.add("ERROR\t" + head + "\t" + hex + "\tutf8\t" + e);
                } catch (IllegalArgumentException e) {
                    counts.right.incrementAndGet();
                } catch (Throwable e) {
                    counts.errors.incrementAndGet();
                    wrong.add("ERROR\t" + head + "\t" + hex + "\tutf8\t" + e);
                }
            }
        }
    }

    static String firstLine(String s) {
        int k = s.indexOf('\n');
        return k < 0 ? s : s.substring(0, k);
    }

    interface Test {
        boolean run() throws MatchLimitException;
    }

    static void judgeTests(String name, Object r, String pattern, Counts counts, List<String> wrong) {
        String flags = (String) get(r, "flags");
        String shown = escape(pattern);
        for (Object[] mode : modes(r)) {
            String modeFlags = flags + mode[0];
            String answerName = (String) mode[1];
            boolean expectedValid = (Boolean) mode[2];
            Regex re;
            try {
                re = Regex.compile(pattern, modeFlags);
            } catch (RegexException e) {
                boolean error = e.kind() == RegexException.Kind.FLAGS;
                String message = (e.kind() == RegexException.Kind.INVALID ? "PARSE: "
                        : e.kind() == RegexException.Kind.TOO_LARGE ? "TOO-LARGE: " : "ERROR: ") + e.getMessage();
                if (error) {
                    counts.errors.incrementAndGet();
                }
                if (expectedValid) {
                    counts.refused.incrementAndGet();
                    wrong.add("REFUSED\t" + name + "\t/" + shown + "/" + modeFlags + "\t" + firstLine(message));
                }
                continue;
            } catch (Throwable e) {
                counts.errors.incrementAndGet();
                if (expectedValid) {
                    counts.refused.incrementAndGet();
                    wrong.add("REFUSED\t" + name + "\t/" + shown + "/" + modeFlags + "\tERROR: " + e);
                }
                continue;
            }
            if (!expectedValid) {
                counts.accepted.incrementAndGet();
                wrong.add("ACCEPTED-INVALID\t" + name + "\t/" + shown + "/" + modeFlags);
                continue;
            }
            List<Object> tests = array(get(r, "tests"));
            if (tests == null) {
                continue;
            }
            for (Object t : tests) {
                Object answer = get(t, answerName);
                if (!(answer instanceof Boolean)) {
                    continue;
                }
                boolean expected = (Boolean) answer;
                String text = (String) get(t, "text");
                if (utf16) {
                    check(name, shown, modeFlags, text, "utf16", expected, () -> re.isMatch(text, LIMIT), counts, wrong);
                }
                if (utf8 && !hasLoneSurrogate(text)) {
                    Utf8Text u = Utf8Text.of(text);
                    check(name, shown, modeFlags, text, "utf8", expected, () -> re.isMatch(u, LIMIT), counts, wrong);
                }
            }
        }
    }

    static void check(String name, String shown, String modeFlags, String text, String encoding, boolean expected,
            Test test, Counts counts, List<String> wrong) {
        try {
            boolean actual = test.run();
            if (actual == expected) {
                counts.right.incrementAndGet();
            } else {
                counts.wrong.incrementAndGet();
                wrong.add("WRONG\t" + name + "\t/" + shown + "/" + modeFlags + "\t" + escape(text) + "\t" + encoding
                        + "\texpected " + expected);
            }
        } catch (MatchLimitException e) {
            counts.limited.incrementAndGet();
            wrong.add("LIMIT\t" + name + "\t/" + shown + "/" + modeFlags + "\t" + escape(text) + "\t" + encoding + "\t"
                    + (e.kind() == MatchLimitException.Kind.MEMORY ? "memory" : "time"));
        } catch (Throwable e) {
            counts.errors.incrementAndGet();
            wrong.add("ERROR\t" + name + "\t/" + shown + "/" + modeFlags + "\t" + escape(text) + "\t" + encoding + "\t"
                    + e);
        }
    }

    // -----------------------------------------------------------------------------------------------------------------
    // captures.jsonl

    /** The groups of an exec answer: null for no match, else the bounds of each group, null for one not taking part. */
    static int[][] execOf(Object e) {
        List<Object> groups = array(e);
        if (groups == null) {
            return null;
        }
        int[][] out = new int[groups.size()][];
        for (int k = 0; k < out.length; k++) {
            List<Object> b = array(groups.get(k));
            out[k] = b == null ? null : new int[] {((Double) b.get(0)).intValue(), ((Double) b.get(1)).intValue()};
        }
        return out;
    }

    /** For each UTF-16 index of a text (and the index past its end), the byte offset in UTF-8. */
    static int[] byteOffsets(String s) {
        int[] at = new int[s.length() + 1];
        int b = 0;
        int k = 0;
        while (k < s.length()) {
            at[k] = b;
            char c = s.charAt(k);
            if (Character.isHighSurrogate(c) && k + 1 < s.length() && Character.isLowSurrogate(s.charAt(k + 1))) {
                at[k + 1] = b + 2;
                b += 4;
                k += 2;
            } else {
                b += c < 0x80 ? 1 : c < 0x800 ? 2 : 3;
                k++;
            }
        }
        at[s.length()] = b;
        return at;
    }

    interface Map1 {
        int apply(int k);
    }

    static int[][] groupsOf(Captures caps) {
        if (caps == null) {
            return null;
        }
        int[][] out = new int[caps.length()][];
        for (int g = 0; g < out.length; g++) {
            out[g] = caps.matched(g) ? new int[] {caps.start(g), caps.end(g)} : null;
        }
        return out;
    }

    static boolean sameSlots(int[][] expected, int[][] got, Map1 map) {
        if (expected == null || got == null) {
            return expected == null && got == null;
        }
        if (expected.length != got.length) {
            return false;
        }
        for (int k = 0; k < expected.length; k++) {
            int[] a = expected[k];
            int[] b = got[k];
            if (a == null || b == null) {
                if (a != b) {
                    return false;
                }
            } else if (map.apply(a[0]) != b[0] || map.apply(a[1]) != b[1]) {
                return false;
            }
        }
        return true;
    }

    static Regex make(Map<String, Regex> cache, String pattern, String flags) {
        return cache.computeIfAbsent(flags, f -> {
            try {
                return Regex.compile(pattern, f);
            } catch (RegexException e) {
                return null;
            }
        });
    }

    static boolean judgeAll(List<int[][]> all, Iterator<Match> found, Map1 map) {
        int n = 0;
        while (found.hasNext()) {
            Match m = found.next();
            if (n >= all.size()) {
                // One match more than the data has: wrong, unless the data kept only the first twenty.
                return all.size() == 20;
            }
            int[][] e = all.get(n);
            if (e == null) {
                return false;
            }
            if (map.apply(e[0][0]) != m.start() || map.apply(e[0][1]) != m.end()) {
                return false;
            }
            n++;
        }
        return !((all.size() < 20 && n != all.size()) || (all.size() == 20 && n < 20));
    }

    static boolean sameSplit(Object expected, List<String> got) {
        List<Object> e = array(expected);
        if (e.size() != got.size()) {
            return false;
        }
        for (int k = 0; k < e.size(); k++) {
            Object a = e.get(k);
            String b = got.get(k);
            if (a == null ? b != null : !a.equals(b)) {
                return false;
            }
        }
        return true;
    }

    static void judgeCaptures(Object r, String pattern, Counts counts, List<String> wrong) {
        String flags = (String) get(r, "flags");
        Object valid = get(r, "valid");
        String shown = escape(pattern);
        List<Object> tests = array(get(r, "tests"));
        if (tests == null) {
            return;
        }
        Map<String, Regex> cache = new HashMap<>();
        for (Object t : tests) {
            if (Boolean.TRUE.equals(get(t, "v8_deviation"))) {
                continue;
            }
            String text = (String) get(t, "text");
            for (String mode : new String[] {"unicode", "legacy"}) {
                if (!Boolean.TRUE.equals(get(valid, mode))) {
                    continue;
                }
                Object a = get(t, mode);
                if (a == null) {
                    continue;
                }
                String modeFlags = mode.equals("unicode") ? flags + "u" : flags;
                String where = "/" + shown + "/" + modeFlags + "\t" + escape(text);
                Regex re = make(cache, pattern, modeFlags);
                Regex g = make(cache, pattern, modeFlags + "g");
                Regex y = make(cache, pattern, modeFlags + "y");
                if (re == null || g == null || y == null) {
                    counts.refused.incrementAndGet();
                    wrong.add("REFUSED\tcaptures.jsonl\t" + where);
                    continue;
                }
                List<String> bad = new ArrayList<>();
                boolean lone = hasLoneSurrogate(text);
                Utf8Text u8 = lone ? null : Utf8Text.of(text);
                int[] byteAt = lone ? null : byteOffsets(text);
                Map1 identity = k -> k;
                Map1 toByte = k -> byteAt[k];

                // exec
                int[][] exec = execOf(get(a, "exec"));
                Captures got;
                try {
                    got = re.captures(text, 0, LIMIT);
                } catch (MatchLimitException e) {
                    counts.limited.incrementAndGet();
                    wrong.add("LIMIT\tcaptures.jsonl\t" + where);
                    continue;
                }
                verdict(counts, wrong, where, "exec", sameSlots(exec, groupsOf(got), identity));
                if (!lone) {
                    verdict(counts, wrong, where, "exec-utf8", sameSlots(exec, groupsOf(re.captures(u8)), toByte));
                }

                // all
                List<int[][]> all = new ArrayList<>();
                for (Object e : array(get(a, "all"))) {
                    all.add(execOf(e));
                }
                boolean allOk = true;
                int n = 0;
                for (Captures caps : g.capturesAll(text)) {
                    if (n >= all.size()) {
                        allOk &= all.size() == 20;
                        break;
                    }
                    if (!sameSlots(all.get(n), groupsOf(caps), identity)) {
                        allOk = false;
                        break;
                    }
                    n++;
                }
                if ((all.size() < 20 && n != all.size()) || (all.size() == 20 && n < 20)) {
                    allOk = false;
                }
                verdict(counts, wrong, where, "all", allOk && judgeAll(all, g.findAll(text).iterator(), identity));
                if (!lone) {
                    verdict(counts, wrong, where, "all-utf8", judgeAll(all, g.findAll(u8).iterator(), toByte));
                }

                // sticky at 1
                if (!text.isEmpty()) {
                    int[][] sticky = execOf(get(a, "sticky"));
                    verdict(counts, wrong, where, "sticky", sameSlots(sticky, groupsOf(y.captures(text, 1)), identity));
                    if (!lone && !Character.isHighSurrogate(text.charAt(0))) {
                        verdict(counts, wrong, where, "sticky-utf8",
                                sameSlots(sticky, groupsOf(y.captures(u8, byteAt[1])), toByte));
                    }
                }

                // replace
                String replace = (String) get(a, "replace");
                verdict(counts, wrong, where, "replace", re.replace(text, REPLACEMENT).equals(replace));
                String replaceAll = (String) get(a, "replaceAll");
                verdict(counts, wrong, where, "replaceAll", g.replace(text, REPLACEMENT).equals(replaceAll));
                if (!lone) {
                    // Over UTF-8 a half character is written as U+FFFD, which is what a lossy conversion of V8's
                    // answer writes for it.
                    verdict(counts, wrong, where, "replace-utf8",
                            re.replace(u8, REPLACEMENT).toString().equals(lossy(replace)));
                    verdict(counts, wrong, where, "replaceAll-utf8",
                            g.replace(u8, REPLACEMENT).toString().equals(lossy(replaceAll)));
                }

                // split
                verdict(counts, wrong, where, "split", sameSplit(get(a, "split"), re.split(text)));
                verdict(counts, wrong, where, "split3", sameSplit(get(a, "split3"), re.split(text, 3)));
            }
        }
    }

    static void verdict(Counts counts, List<String> wrong, String where, String what, boolean ok) {
        if (ok) {
            counts.right.incrementAndGet();
        } else {
            counts.wrong.incrementAndGet();
            wrong.add("WRONG\tcaptures.jsonl\t" + where + "\t" + what);
        }
    }

    // -----------------------------------------------------------------------------------------------------------------
    // A small JSON reader whose strings keep lone surrogates, as Java strings can.

    static final class Json {
        private final String s;
        private int i;

        Json(String s) {
            this.s = s;
        }

        Object parse() {
            Object v = value();
            space();
            if (i != s.length()) {
                throw new IllegalArgumentException("unexpected text at " + i);
            }
            return v;
        }

        private void space() {
            while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t' || s.charAt(i) == '\n'
                    || s.charAt(i) == '\r')) {
                i++;
            }
        }

        private Object value() {
            space();
            char c = s.charAt(i);
            switch (c) {
                case '{': {
                    i++;
                    Map<String, Object> m = new LinkedHashMap<>();
                    space();
                    if (s.charAt(i) == '}') {
                        i++;
                        return m;
                    }
                    while (true) {
                        space();
                        String key = string();
                        space();
                        expect(':');
                        m.put(key, value());
                        space();
                        if (s.charAt(i) == ',') {
                            i++;
                            continue;
                        }
                        expect('}');
                        return m;
                    }
                }
                case '[': {
                    i++;
                    List<Object> a = new ArrayList<>();
                    space();
                    if (s.charAt(i) == ']') {
                        i++;
                        return a;
                    }
                    while (true) {
                        a.add(value());
                        space();
                        if (s.charAt(i) == ',') {
                            i++;
                            continue;
                        }
                        expect(']');
                        return a;
                    }
                }
                case '"':
                    return string();
                case 't':
                    i += 4;
                    return Boolean.TRUE;
                case 'f':
                    i += 5;
                    return Boolean.FALSE;
                case 'n':
                    i += 4;
                    return null;
                default: {
                    int start = i;
                    while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
                        i++;
                    }
                    return Double.parseDouble(s.substring(start, i));
                }
            }
        }

        private void expect(char c) {
            if (s.charAt(i) != c) {
                throw new IllegalArgumentException("expected " + c + " at " + i);
            }
            i++;
        }

        private String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n':
                        out.append('\n');
                        break;
                    case 't':
                        out.append('\t');
                        break;
                    case 'r':
                        out.append('\r');
                        break;
                    case 'b':
                        out.append('\b');
                        break;
                    case 'f':
                        out.append('\f');
                        break;
                    case 'u':
                        out.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default:
                        out.append(e);
                }
            }
        }
    }
}
