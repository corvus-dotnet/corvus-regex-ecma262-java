import io.github.corvusdotnet.regex.ecma262.Captures;
import io.github.corvusdotnet.regex.ecma262.Match;
import io.github.corvusdotnet.regex.ecma262.MatchLimitException;
import io.github.corvusdotnet.regex.ecma262.Regex;
import io.github.corvusdotnet.regex.ecma262.RegexException;
import io.github.corvusdotnet.regex.ecma262.Utf8Text;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * Asks the engine about patterns built to overflow the stack (the Rust crate's tools/src/bin/stack-probe.rs): deep
 * (groups, lookarounds, modifier groups, quantified groups and the classes of the v flag, each nested to the limit, one
 * past it and far past it), wide (many groups, classes, alternatives, backreferences, set operations and string
 * alternatives side by side) and long (the longest pattern allowed, and one past it), in every grammar, through every
 * member over UTF-8 and UTF-16.
 *
 * <pre>
 *   java -cp target/classes:tools/stackprobe/out StackProbe [--stacks 8M,1M,256K] [--jobs 4] [--filter text]
 * </pre>
 *
 * Each probe runs on a thread of its own with each of the stacks. A probe is answered (compiled and matched), refused
 * (not valid, or too large, which a caller can catch), or a fault: a {@link StackOverflowError} or any other error or
 * exception a caller is not told to expect, or no answer within two minutes. A JVM, unlike a native process, survives a
 * stack overflow, so the probes share one process. Any fault makes the exit status 1.
 */
public final class StackProbe {
    private StackProbe() {}

    /** A probe, whose pattern and text are built only when it is asked. */
    static final class Probe {
        final String name;
        final String flags;
        final Supplier<String[]> build;

        Probe(String name, String flags, Supplier<String[]> build) {
            this.name = name;
            this.flags = flags;
            this.build = build;
        }
    }

    static String nest(String open, String body, String close, int depth) {
        return open.repeat(depth) + body + close.repeat(depth);
    }

    static String text(int n) {
        return "a".repeat(Math.max(64, Math.min(n, 1 << 20))) + "b";
    }

    static List<Probe> probes() {
        List<Probe> all = new ArrayList<>();
        int[] depths = {32, 33, 200, 1000, 1001, 5000, 200_000};
        String[] grammars = {"", "u", "v", "i", "iv"};

        // Deep: each kind of group nested.
        String[][] groups = {
            {"group", "(", ")"}, {"non-capturing", "(?:", ")"}, {"lookahead", "(?=", ")"},
            {"negative lookahead", "(?!", ")"}, {"lookbehind", "(?<=", ")"}, {"negative lookbehind", "(?<!", ")"},
            {"modifier i", "(?i:", ")"}, {"modifier -i", "(?-i:", ")"}, {"star", "(?:", ")*"}, {"counted", "(?:", "){2}"},
            {"lazy plus", "(?:", ")+?"}, {"alternative", "(?:b|", ")"},
        };
        for (String[] g : groups) {
            for (int depth : depths) {
                for (String flags : grammars) {
                    all.add(new Probe("deep " + g[0] + " x" + depth + " /" + flags, flags,
                            () -> new String[] {nest(g[1], "a", g[2], depth), text(depth)}));
                }
            }
        }
        for (int depth : depths) {
            for (String flags : grammars) {
                all.add(new Probe("deep named x" + depth + " /" + flags, flags, () -> {
                    StringBuilder named = new StringBuilder();
                    for (int i = 0; i < depth; i++) {
                        named.append("(?<g").append(i).append('>');
                    }
                    return new String[] {named + "a" + ")".repeat(depth), text(depth)};
                }));
                all.add(new Probe("deep backreference x" + depth + " /" + flags, flags,
                        () -> new String[] {nest("(", "a", ")\\1", depth), text(depth)}));
            }
        }

        // Deep: the classes of the v flag nested, asked of every grammar (the others refuse them as not valid).
        String[][] classes = {
            {"class", "[", "a", "]"}, {"negated class", "[^", "a", "]"}, {"difference", "[a--", "[b]", "]"},
            {"intersection", "[a&&", "[a]", "]"}, {"string alternatives", "[\\q{ab|c}", "a", "]"},
            {"class in a group", "(?:[", "a", "])"},
        };
        for (String[] c : classes) {
            for (int depth : depths) {
                for (String flags : grammars) {
                    all.add(new Probe("deep " + c[0] + " x" + depth + " /" + flags, flags,
                            () -> new String[] {nest(c[1], c[2], c[3], depth), text(depth)}));
                }
            }
        }
        for (int depth : depths) {
            all.add(new Probe("deep [ unclosed x" + depth + " /v", "v", () -> new String[] {"[".repeat(depth), text(64)}));
            all.add(new Probe("deep ( unclosed x" + depth + " /v", "v", () -> new String[] {"(".repeat(depth), text(64)}));
        }

        // Wide: many of one thing side by side.
        List<Object[]> wide = new ArrayList<>();
        wide.add(new Object[] {"alternatives", (IntFunction<String>) w -> "a|".repeat(w) + "b"});
        wide.add(new Object[] {"empty groups", (IntFunction<String>) w -> "()".repeat(w)});
        wide.add(new Object[] {"groups", (IntFunction<String>) w -> "(a)".repeat(w)});
        wide.add(new Object[] {"named groups", (IntFunction<String>) w -> {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < w; i++) {
                b.append("(?<g").append(i).append(">a)");
            }
            return b.toString();
        }});
        wide.add(new Object[] {"classes", (IntFunction<String>) w -> "[a]".repeat(w)});
        wide.add(new Object[] {"lookaheads", (IntFunction<String>) w -> "(?=a)".repeat(w) + "a"});
        wide.add(new Object[] {"backreferences", (IntFunction<String>) w -> "(a)" + "\\1".repeat(w)});
        wide.add(new Object[] {"optional", (IntFunction<String>) w -> "a?".repeat(w)});
        wide.add(new Object[] {"optional groups", (IntFunction<String>) w -> "(?:a?)".repeat(w)});
        wide.add(new Object[] {"ranges in a class", (IntFunction<String>) w -> "[" + "a-b".repeat(w) + "]"});
        wide.add(new Object[] {"properties", (IntFunction<String>) w -> "\\p{L}".repeat(w)});
        wide.add(new Object[] {"stars", (IntFunction<String>) w -> "a*".repeat(w)});
        wide.add(new Object[] {"alternatives in a star", (IntFunction<String>) w -> "(?:" + "a|".repeat(w) + "b)*"});
        wide.add(new Object[] {"differences", (IntFunction<String>) w -> "[a" + "--b".repeat(w) + "]"});
        wide.add(new Object[] {"intersections", (IntFunction<String>) w -> "[a" + "&&a".repeat(w) + "]"});
        wide.add(new Object[] {"string alternatives", (IntFunction<String>) w -> "[\\q{" + "a|".repeat(w) + "b}]"});
        for (int width : new int[] {1000, 100_000, 300_000}) {
            for (Object[] k : wide) {
                @SuppressWarnings("unchecked")
                IntFunction<String> pattern = (IntFunction<String>) k[1];
                for (String flags : grammars) {
                    all.add(new Probe("wide " + k[0] + " x" + width + " /" + flags, flags,
                            () -> new String[] {pattern.apply(width), text(width)}));
                }
            }
        }

        // Long: the longest pattern allowed, and one past it.
        for (int length : new int[] {Regex.MAX_PATTERN_LENGTH, Regex.MAX_PATTERN_LENGTH + 1}) {
            for (String flags : grammars) {
                all.add(new Probe("long literal x" + length + " /" + flags, flags,
                        () -> new String[] {"a".repeat(length), text(length)}));
            }
        }
        return all;
    }

    /** What a member answered, or the limit it reached, which is an answer too. */
    static String answer(Supplier<Object> member) {
        try {
            return String.valueOf(member.get());
        } catch (MatchLimitException e) {
            return "limit " + e.kind();
        }
    }

    /** Asks the engine everything about one probe; the answer is a line. */
    static String ask(Probe probe) {
        String[] built = probe.build.get();
        String pattern = built[0];
        String text = built[1];
        Regex re;
        boolean valid = Regex.isValid(pattern, probe.flags);
        try {
            re = Regex.compile(pattern, probe.flags);
        } catch (RegexException e) {
            if (valid && e.kind() != RegexException.Kind.TOO_LARGE) {
                return "FAULT isValid accepted a pattern compile refused: " + e.getMessage();
            }
            return e.kind() == RegexException.Kind.TOO_LARGE ? "refused too large" : "refused not valid";
        }
        if (!valid) {
            return "FAULT isValid refused a pattern compile accepted";
        }
        Utf8Text text8 = Utf8Text.of(text);
        Duration limit = Duration.ofSeconds(5);
        List<String> answers = new ArrayList<>();
        answers.add(answer(() -> re.isMatch(text8, limit)));
        answers.add(answer(() -> re.isMatch(text, limit)));
        answers.add(answer(() -> {
            Match m = re.find(text8, 0, limit);
            return m == null ? null : m.start() + ".." + m.end();
        }));
        answers.add(answer(() -> {
            Captures c = re.captures(text8, 0, limit);
            return c == null ? null : c.length();
        }));
        answers.add(answer(() -> {
            Captures c = re.captures(text, 0, limit);
            return c == null ? null : c.length();
        }));
        answers.add(answer(() -> count(re.findAll(text8), 1000)));
        answers.add(answer(() -> count(re.findAll(text), 1000)));
        answers.add(answer(() -> count(re.capturesAll(text8), 100)));
        answers.add(answer(() -> {
            Captures into = re.newCaptures();
            return re.captures(into, text8, 0) ? into.start(0) + ".." + into.end(0) : null;
        }));
        answers.add(answer(() -> re.replaceAll(text8, "$1x").length()));
        answers.add(answer(() -> re.replaceAll(text, "x").length()));
        answers.add(answer(() -> re.split(text8).size()));
        answers.add(answer(() -> re.split(text).size()));
        return "answered " + String.join(" ", answers);
    }

    static int count(Iterable<?> all, int most) {
        int n = 0;
        for (Object o : all) {
            if (++n == most) {
                break;
            }
        }
        return n;
    }

    static long size(String text) {
        long number = Long.parseLong(text.substring(0, text.length() - 1));
        switch (text.charAt(text.length() - 1)) {
            case 'K':
                return number << 10;
            case 'M':
                return number << 20;
            default:
                throw new IllegalArgumentException("a stack size such as 256K or 8M");
        }
    }

    /** Runs a probe on a thread with the stack: its answer, or the fault. */
    static String run(Probe probe, long stack) throws InterruptedException {
        String[] out = new String[1];
        Thread t = new Thread(null, () -> {
            try {
                out[0] = ask(probe);
            } catch (Throwable e) {
                out[0] = "FAULT " + e;
            }
        }, "probe", stack);
        t.setDaemon(true);
        t.start();
        t.join(120_000);
        if (t.isAlive()) {
            return "FAULT no answer in two minutes";
        }
        return out[0];
    }

    public static void main(String[] args) throws InterruptedException {
        String stacks = "8M,1M,256K";
        int jobs = 4;
        String filter = "";
        for (int k = 0; k + 1 < args.length; k += 2) {
            switch (args[k]) {
                case "--stacks":
                    stacks = args[k + 1];
                    break;
                case "--jobs":
                    jobs = Integer.parseInt(args[k + 1]);
                    break;
                case "--filter":
                    filter = args[k + 1];
                    break;
                default:
                    throw new IllegalArgumentException("unknown argument " + args[k]);
            }
        }
        String[] names = stacks.split(",");
        List<Probe> all = probes();
        List<int[]> work = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).name.contains(filter)) {
                for (int s = 0; s < names.length; s++) {
                    work.add(new int[] {i, s});
                }
            }
        }
        String[] outcomes = new String[work.size()];
        AtomicInteger next = new AtomicInteger();
        long started = System.nanoTime();
        List<Thread> workers = new ArrayList<>();
        for (int j = 0; j < jobs; j++) {
            Thread w = new Thread(() -> {
                int n;
                while ((n = next.getAndIncrement()) < work.size()) {
                    int[] item = work.get(n);
                    try {
                        outcomes[n] = run(all.get(item[0]), size(names[item[1]]));
                    } catch (InterruptedException e) {
                        outcomes[n] = "FAULT interrupted";
                    }
                }
            });
            w.start();
            workers.add(w);
        }
        for (Thread w : workers) {
            w.join();
        }
        int answered = 0;
        int refused = 0;
        int faults = 0;
        for (int n = 0; n < work.size(); n++) {
            String o = outcomes[n];
            if (o.startsWith("answered")) {
                answered++;
            } else if (o.startsWith("refused")) {
                refused++;
            } else {
                faults++;
                System.out.println(all.get(work.get(n)[0]).name + " on " + names[work.get(n)[1]] + ": " + o);
            }
        }
        System.out.printf("%d probes on %d stacks: %d answered, %d refused, %d faults, in %.0f s%n",
                work.size() / names.length, names.length, answered, refused, faults,
                (System.nanoTime() - started) / 1e9);
        System.exit(faults > 0 ? 1 : 0);
    }
}
