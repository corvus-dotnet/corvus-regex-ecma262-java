import io.github.corvusdotnet.regex.ecma262.Matches;
import io.github.corvusdotnet.regex.ecma262.Regex;
import io.github.corvusdotnet.regex.ecma262.Utf8Text;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * The Industry benchmark: each workload counted by each engine, as the V8 harness of the .NET reference engine
 * (tools/benchmarks/v8/industry.mjs) counts it, in nanoseconds per count. A port of the Rust crate's
 * tools/src/bin/industry.rs, with the same protocol.
 *
 * <pre>
 *   java -cp target/classes:tools/benchmarks/out Industry &lt;workloads.json&gt;
 *        [--engines corvus-utf8,corvus-utf16,corvus-utf8-vector,corvus-utf16-vector,jdk,tregex,v8] [--v8 &lt;industry.mjs&gt;] [--tregex &lt;GraalJS node&gt;]
 *        [--rounds 3] [--seconds 0.7] [--passes 7] [--spread 0.05] [--filter &lt;substring of the id&gt;]
 *        [--out &lt;results.json&gt;]
 * </pre>
 *
 * The protocol is interleaved, so that what changes on the host over a run (its clock, its heat, its other work) falls
 * on every engine alike: the workloads are the outer loop, each workload's rounds next and the engines innermost, their
 * order turned by one each round. Each figure is a child process of its own (a JVM, or Node for V8 and GraalJS), so
 * that one engine's caches, compiled code and heap do not reach another's figures; it warms for two fifths of its
 * seconds, then times {@code passes} passes of as many counts as fit the rest and reports their median. A workload's
 * figure for an engine is the median of its rounds, with the fastest and the slowest round as its spread. Where the
 * spread, (slowest - fastest) / median, is over {@code --spread}, the figure is listed in the run's facts and on
 * stderr. A child that does not finish in two minutes is stopped and recorded as such. A count is the global search of
 * ECMA-262: after an empty match the search goes on one code point later.
 *
 * <p>The engines: this library over UTF-8 ({@link Utf8Text}) and over UTF-16 ({@code String}), with the workload's
 * flags and {@code u}, in each of its two modes: its own searches ({@code corvus-utf8}, {@code corvus-utf16}) and the
 * Vector API's, which it takes where the JVM has the module {@code jdk.incubator.vector} ({@code corvus-utf8-vector},
 * {@code corvus-utf16-vector}, run with {@code --add-modules jdk.incubator.vector}); {@code jdk}, {@code java.util.regex}, the platform's standard engine, which is given the
 * pattern as it is and the flags i (with Unicode case), m and s, and whose row is absent where the pattern does not
 * compile; {@code tregex}, TRegex, the ECMA-262 engine of GraalJS, run by GraalVM's Node ({@code --tregex}) over the
 * V8 harness; and V8 ({@code --v8}, the harness run by {@code node}), with {@code gu} added. An engine written
 * {@code name@classes} is {@code name} with the library's classes taken from the directory {@code classes} first, so
 * that two builds can be timed side by side in the same rounds (an interleaved A/B). {@code --ids <file>} times only the
 * workloads whose ids are the file's lines.
 *
 * <p>The texts are those of the reference run, in {@code CORVUS_REGEX_TEXTS} (or
 * {@code ~/.local/share/corvus-regex-benchmarks}) and fetched from dotnet/performance when missing. The run unzips each
 * once into a directory of its own ({@code CORVUS_REGEX_PLAIN}), which the children read.
 */
public final class Industry {
    private Industry() {}

    static final String SOURCE = "https://raw.githubusercontent.com/dotnet/performance/main/src/benchmarks/micro/"
            + "libraries/System.Text.RegularExpressions/TestData/";
    static final List<String> ENGINES =
            List.of("corvus-utf8", "corvus-utf16", "corvus-utf8-vector", "corvus-utf16-vector", "jdk");

    /** What the counts are summed into, so that no count is taken away as dead code. */
    static volatile long sink;

    // -----------------------------------------------------------------------------------------------------------------
    // A small reader of JSON: the workloads and the children's lines.

    static Object parse(String s) {
        int[] at = {0};
        Object v = value(s, at);
        return v;
    }

    static void space(String s, int[] at) {
        while (at[0] < s.length() && Character.isWhitespace(s.charAt(at[0]))) {
            at[0]++;
        }
    }

    static Object value(String s, int[] at) {
        space(s, at);
        char c = s.charAt(at[0]);
        if (c == '{') {
            Map<String, Object> m = new LinkedHashMap<>();
            at[0]++;
            space(s, at);
            if (s.charAt(at[0]) == '}') {
                at[0]++;
                return m;
            }
            while (true) {
                space(s, at);
                String k = (String) value(s, at);
                space(s, at);
                at[0]++; // :
                m.put(k, value(s, at));
                space(s, at);
                if (s.charAt(at[0]++) == '}') {
                    return m;
                }
            }
        }
        if (c == '[') {
            List<Object> l = new ArrayList<>();
            at[0]++;
            space(s, at);
            if (s.charAt(at[0]) == ']') {
                at[0]++;
                return l;
            }
            while (true) {
                l.add(value(s, at));
                space(s, at);
                if (s.charAt(at[0]++) == ']') {
                    return l;
                }
            }
        }
        if (c == '"') {
            StringBuilder b = new StringBuilder();
            at[0]++;
            while (true) {
                char x = s.charAt(at[0]++);
                if (x == '"') {
                    return b.toString();
                }
                if (x == '\\') {
                    char e = s.charAt(at[0]++);
                    switch (e) {
                        case 'n': b.append('\n'); break;
                        case 't': b.append('\t'); break;
                        case 'r': b.append('\r'); break;
                        case 'b': b.append('\b'); break;
                        case 'f': b.append('\f'); break;
                        case 'u':
                            b.append((char) Integer.parseInt(s.substring(at[0], at[0] + 4), 16));
                            at[0] += 4;
                            break;
                        default: b.append(e); break;
                    }
                } else {
                    b.append(x);
                }
            }
        }
        if (s.startsWith("true", at[0])) {
            at[0] += 4;
            return Boolean.TRUE;
        }
        if (s.startsWith("false", at[0])) {
            at[0] += 5;
            return Boolean.FALSE;
        }
        if (s.startsWith("null", at[0])) {
            at[0] += 4;
            return null;
        }
        int start = at[0];
        while (at[0] < s.length() && "+-0123456789.eE".indexOf(s.charAt(at[0])) >= 0) {
            at[0]++;
        }
        return Double.parseDouble(s.substring(start, at[0]));
    }

    static String quote(String s) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                out.append("\\\"");
            } else if (c == '\\') {
                out.append("\\\\");
            } else if (c < 0x20 || c > 0x7e) {
                out.append(String.format("\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.append('"').toString();
    }

    // -----------------------------------------------------------------------------------------------------------------
    // The workloads and their texts.

    static final class Workload {
        final String id;
        final String pattern;
        final String flags;
        final String file;
        /** V8's count, which the central run writes into the workloads, for a run without V8 (V8=0) to check against. */
        final Double v8Count;

        @SuppressWarnings("unchecked")
        Workload(Object o) {
            Map<String, Object> m = (Map<String, Object>) o;
            id = (String) m.get("id");
            pattern = (String) m.get("pattern");
            flags = (String) m.get("flags");
            file = (String) m.get("file");
            v8Count = (Double) m.get("v8Count");
        }
    }

    @SuppressWarnings("unchecked")
    static List<Workload> workloads(String path) throws IOException {
        List<Workload> out = new ArrayList<>();
        for (Object o : (List<Object>) parse(Files.readString(Paths.get(path)))) {
            out.add(new Workload(o));
        }
        return out;
    }

    static Path textDir() {
        String d = System.getenv("CORVUS_REGEX_TEXTS");
        return d != null ? Paths.get(d) : Paths.get(System.getProperty("user.home"), ".local/share/corvus-regex-benchmarks");
    }

    /** The bytes of a text: gunzipped, without a byte order mark (as unzipped in {@code CORVUS_REGEX_PLAIN}). */
    static byte[] text(String file) throws IOException, InterruptedException {
        String plain = System.getenv("CORVUS_REGEX_PLAIN");
        if (plain != null) {
            Path p = Paths.get(plain, file.replaceAll("\\.gz$", ""));
            if (Files.exists(p)) {
                return Files.readAllBytes(p);
            }
        }
        Path path = textDir().resolve(file);
        if (!Files.exists(path)) {
            Files.createDirectories(textDir());
            Process curl = new ProcessBuilder("curl", "-sSfL", "-o", path.toString(), SOURCE + file).inheritIO().start();
            if (curl.waitFor() != 0) {
                throw new IOException("could not fetch " + file);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = new GZIPInputStream(Files.newInputStream(path))) {
            in.transferTo(out);
        }
        byte[] b = out.toByteArray();
        if (b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) {
            b = Arrays.copyOfRange(b, 3, b.length);
        }
        return b;
    }

    /** The workload's flags with {@code u} added, each once. */
    static String flagsU(String flags) {
        return flags.contains("u") || flags.contains("v") ? flags : flags + "u";
    }

    // -----------------------------------------------------------------------------------------------------------------
    // One figure, in a child process.

    interface Count {
        int run();
    }

    /**
     * Times a count: warm, then passes. Returns the median, the fastest and the slowest pass in nanoseconds per count,
     * the count, and the counts timed.
     */
    static double[] measure(double seconds, int passes, Count count) {
        int expected = count.run();
        long warmEnd = System.nanoTime() + (long) (seconds * 0.4e9);
        long reps = 0;
        while (System.nanoTime() < warmEnd) {
            if (count.run() != expected) {
                throw new IllegalStateException("the count changed");
            }
            reps++;
        }
        long perPass = Math.max(1, Math.round(reps * 0.6 / passes));
        double[] times = new double[passes];
        for (int p = 0; p < passes; p++) {
            long t0 = System.nanoTime();
            long s = 0;
            for (long k = 0; k < perPass; k++) {
                s += count.run();
            }
            times[p] = (double) (System.nanoTime() - t0) / perPass;
            sink += s;
        }
        Arrays.sort(times);
        return new double[] {times[passes / 2], times[0], times[passes - 1], expected, perPass * passes};
    }

    /** Runs one workload on one engine in this process and prints its figures as one line of JSON. */
    static void one(String path, int index, String engine, double seconds, int passes) throws Exception {
        Workload w = workloads(path).get(index);
        byte[] bytes = text(w.file);
        String flags = flagsU(w.flags);
        double[] f;
        try {
            switch (engine) {
                case "corvus-utf8": {
                    Utf8Text t = Utf8Text.of(bytes);
                    Matches m = Regex.compile(w.pattern, flags).matches(t);
                    f = measure(seconds, passes, () -> m.reset(t).count());
                    break;
                }
                case "corvus-utf16": {
                    String s = new String(bytes, StandardCharsets.UTF_8);
                    Matches m = Regex.compile(w.pattern, flags).matches(s);
                    f = measure(seconds, passes, () -> m.reset(s).count());
                    break;
                }
                case "jdk": {
                    String s = new String(bytes, StandardCharsets.UTF_8);
                    int jf = (w.flags.contains("i") ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0)
                            | (w.flags.contains("m") ? Pattern.MULTILINE : 0) | (w.flags.contains("s") ? Pattern.DOTALL : 0);
                    Matcher m = Pattern.compile(w.pattern, jf).matcher(s);
                    f = measure(seconds, passes, () -> {
                        m.reset();
                        int n = 0;
                        while (m.find()) {
                            n++;
                        }
                        return n;
                    });
                    break;
                }
                default:
                    throw new IllegalArgumentException("unknown engine " + engine);
            }
        } catch (RuntimeException e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage().lines().findFirst().orElse("");
            System.out.println("{\"error\":" + quote(message) + "}");
            return;
        }
        System.out.printf("{\"median\":%.1f,\"min\":%.1f,\"max\":%.1f,\"count\":%d,\"reps\":%d}%n", f[0], f[1], f[2],
                (long) f[3], (long) f[4]);
    }

    // -----------------------------------------------------------------------------------------------------------------
    // The run.

    static String firstLine(String... command) {
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            String line;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                line = r.readLine();
            }
            p.waitFor();
            return line == null ? "" : line;
        } catch (IOException | InterruptedException e) {
            return "";
        }
    }

    static String field(String file, String prefix) {
        try {
            for (String l : Files.readAllLines(Paths.get(file))) {
                if (l.startsWith(prefix)) {
                    return l.substring(l.indexOf(':') + 1).trim();
                }
            }
        } catch (IOException e) {
            // Not Linux.
        }
        return "";
    }

    static String env(String name) {
        String v = System.getenv(name);
        return v == null ? "" : v;
    }

    /** What the run is: the host, the runtime, the affinity, the load and the commit, read from the run itself. */
    static String facts(String seconds, String passes, Map<String, String> extra) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("cpu", field("/proc/cpuinfo", "model name"));
        f.put("cores", String.valueOf(Runtime.getRuntime().availableProcessors()));
        f.put("affinity", field("/proc/self/status", "Cpus_allowed_list"));
        f.put("os", firstLine("uname", "-sr"));
        String load = "";
        try {
            load = Files.readString(Paths.get("/proc/loadavg")).split(" ")[0];
        } catch (IOException e) {
            // Not Linux.
        }
        f.put("load", load);
        f.put("java", System.getProperty("java.vm.name") + " " + System.getProperty("java.runtime.version"));
        String commit = env("CORVUS_COMMIT");
        f.put("commit", commit.isEmpty() ? firstLine("git", "rev-parse", "HEAD") : commit);
        f.put("date", firstLine("date", "-u", "+%Y-%m-%dT%H:%M:%SZ"));
        f.put("seconds", seconds);
        f.put("passes", passes);
        // On a GitHub-hosted runner, what the runner says it is.
        f.put("runner", env("RUNNER_NAME"));
        f.put("runner_os", env("RUNNER_OS"));
        f.put("image", (env("ImageOS") + " " + env("ImageVersion")).trim());
        f.put("corvus-regex-ecma262", System.getProperty("corvus.version", ""));
        f.put("jdk", "java.util.regex (Java " + System.getProperty("java.runtime.version") + ")");
        f.putAll(extra);
        StringBuilder b = new StringBuilder("{");
        for (Map.Entry<String, String> e : f.entrySet()) {
            if (b.length() > 1) {
                b.append(',');
            }
            b.append(quote(e.getKey())).append(':').append(quote(e.getValue()));
        }
        return b.append('}').toString();
    }

    /** One figure: {@code engine} over workload {@code index} in a child process: its line of JSON, or one with the error. */
    static String figure(String v8, String tregex, String path, int index, String engine, String seconds, String passes) {
        List<String> command = new ArrayList<>();
        if (engine.equals("v8") || engine.equals("tregex")) {
            command.add(engine.equals("v8") ? "node" : tregex);
            command.add(v8);
            command.addAll(List.of("--one", path, String.valueOf(index), seconds, passes));
        } else {
            // An engine named name@classes runs with the library's classes from that directory first.
            String classPath = System.getProperty("java.class.path");
            int at = engine.indexOf('@');
            if (at >= 0) {
                classPath = engine.substring(at + 1) + java.io.File.pathSeparator + classPath;
                engine = engine.substring(0, at);
            }
            command.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
            // The library's second mode: the Vector API's searches, which the JVM takes with the module added.
            if (engine.endsWith("-vector")) {
                command.add("--add-modules");
                command.add("jdk.incubator.vector");
                engine = engine.substring(0, engine.length() - "-vector".length());
            }
            command.add("-cp");
            command.add(classPath);
            command.add("Industry");
            command.addAll(List.of("--one", path, String.valueOf(index), engine, seconds, passes));
        }
        Process child;
        try {
            child = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        } catch (IOException e) {
            return "{\"error\":" + quote(e.toString()) + "}";
        }
        CompletableFuture<String> line = CompletableFuture.supplyAsync(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
                return r.readLine();
            } catch (IOException e) {
                return null;
            }
        });
        String out;
        try {
            out = line.get(120, TimeUnit.SECONDS);
            if (out == null) {
                out = "{\"error\":\"the process ended without figures\"}";
            }
        } catch (TimeoutException e) {
            out = "{\"error\":\"did not finish in two minutes\"}";
        } catch (Exception e) {
            out = "{\"error\":" + quote(e.toString()) + "}";
        }
        child.destroyForcibly();
        try {
            child.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return out;
    }

    static double median(double[] sorted) {
        int n = sorted.length;
        return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2;
    }

    static String number(double v) {
        return String.format("%.1f", v);
    }

    @SuppressWarnings("unchecked")
    static Double countOf(String line) {
        if (line == null) {
            return null;
        }
        try {
            Object c = ((Map<String, Object>) parse(line)).get("count");
            return c instanceof Double ? (Double) c : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--one")) {
            one(args[1], Integer.parseInt(args[2]), args[3], Double.parseDouble(args[4]), Integer.parseInt(args[5]));
            System.exit(0);
        }
        Map<String, String> opt = new LinkedHashMap<>();
        for (int k = 1; k + 1 < args.length; k += 2) {
            opt.put(args[k], args[k + 1]);
        }
        String path = args[0];
        String v8 = opt.get("--v8");
        String tregex = opt.get("--tregex");
        List<String> engines = new ArrayList<>();
        if (opt.containsKey("--engines")) {
            engines.addAll(List.of(opt.get("--engines").split(",")));
        } else {
            engines.addAll(ENGINES);
            if (tregex != null) {
                engines.add("tregex");
            }
            if (v8 != null) {
                engines.add("v8");
            }
        }
        if ((engines.contains("v8") || engines.contains("tregex")) && v8 == null) {
            throw new IllegalArgumentException("V8 and TRegex are run by the V8 harness: give --v8 <industry.mjs>");
        }
        int rounds = Integer.parseInt(opt.getOrDefault("--rounds", "3"));
        String seconds = opt.getOrDefault("--seconds", "0.7");
        String passes = opt.getOrDefault("--passes", "7");
        double limit = Double.parseDouble(opt.getOrDefault("--spread", "0.05"));
        String filter = opt.get("--filter");
        List<String> ids = opt.containsKey("--ids") ? Files.readAllLines(Paths.get(opt.get("--ids"))) : null;
        List<Workload> all = workloads(path);
        List<Integer> chosen = new ArrayList<>();
        for (int k = 0; k < all.size(); k++) {
            if ((filter == null || all.get(k).id.contains(filter)) && (ids == null || ids.contains(all.get(k).id))) {
                chosen.add(k);
            }
        }
        if (System.getenv("CORVUS_REGEX_PLAIN") == null) {
            throw new IllegalStateException("run.sh sets CORVUS_REGEX_PLAIN, the directory of the unzipped texts");
        }
        // The texts unzipped once, for every child.
        Path plain = Paths.get(System.getenv("CORVUS_REGEX_PLAIN"));
        Files.createDirectories(plain);
        for (Workload w : all) {
            Path p = plain.resolve(w.file.replaceAll("\\.gz$", ""));
            if (!Files.exists(p)) {
                Files.write(p, text(w.file));
            }
        }
        int ne = engines.size();
        List<List<List<Double>>> figures = new ArrayList<>();
        String[][] lines = new String[ne][all.size()];
        for (int e = 0; e < ne; e++) {
            List<List<Double>> per = new ArrayList<>();
            for (int k = 0; k < all.size(); k++) {
                per.add(new ArrayList<>());
            }
            figures.add(per);
        }
        Map<String, String> names = new LinkedHashMap<>();
        int done = 0;
        for (int k : chosen) {
            for (int round = 0; round < rounds; round++) {
                for (int turn = 0; turn < ne; turn++) {
                    int e = (turn + round) % ne;
                    String line = figure(v8, tregex, path, k, engines.get(e), seconds, passes);
                    Object parsed = null;
                    try {
                        parsed = parse(line);
                    } catch (RuntimeException ex) {
                        // Not JSON: recorded as it is.
                    }
                    Object median = parsed instanceof Map ? ((Map<String, Object>) parsed).get("median") : null;
                    if (median instanceof Double) {
                        figures.get(e).get(k).add((Double) median);
                    } else {
                        System.err.printf("%-13s %-62s %s%n", engines.get(e), all.get(k).id, line);
                    }
                    if (parsed instanceof Map && ((Map<String, Object>) parsed).get("engine") instanceof String) {
                        names.put(engines.get(e), (String) ((Map<String, Object>) parsed).get("engine"));
                    }
                    lines[e][k] = line;
                }
            }
            System.err.println("workload " + (++done) + " of " + chosen.size() + " done");
        }
        List<String> over = new ArrayList<>();
        List<String> differ = new ArrayList<>();
        // V8 is the oracle of the counts: an ECMA-262 engine that counts otherwise is wrong on the row, and
        // java.util.regex, which does not claim ECMA-262, is marked where its count differs.
        int v8e = engines.indexOf("v8");
        Double[] oracle = new Double[all.size()];
        for (int k = 0; k < all.size(); k++) {
            oracle[k] = v8e >= 0 ? countOf(lines[v8e][k]) : all.get(k).v8Count;
        }
        List<String> results = new ArrayList<>();
        for (int e = 0; e < ne; e++) {
            String engine = engines.get(e);
            List<String> rows = new ArrayList<>();
            for (int k : chosen) {
                String line = lines[e][k] == null ? "" : lines[e][k];
                List<Double> times = figures.get(e).get(k);
                String entry;
                if (times.size() < rounds) {
                    // A round with no figure (an error, a limit) makes the figure an error.
                    entry = line.contains("\"error\"") ? line : "{\"error\":\"a round gave no figure\"}";
                } else {
                    List<String> inOrder = new ArrayList<>();
                    for (double t : times) {
                        inOrder.add(number(t));
                    }
                    double[] sorted = times.stream().mapToDouble(Double::doubleValue).sorted().toArray();
                    double m = median(sorted);
                    double lo = sorted[0];
                    double hi = sorted[sorted.length - 1];
                    double spread = m > 0 ? (hi - lo) / m : 0;
                    String id = all.get(k).id;
                    if (spread > limit) {
                        over.add(String.format("%s %s %.1f%%", engine, id, spread * 100));
                        System.err.printf("SPREAD %-13s %-62s %.1f%% over %.1f%%%n", engine, id, spread * 100, limit * 100);
                    }
                    Double c = countOf(line);
                    double count = c == null ? 0 : c;
                    String fig = String.format("\"median\":%s,\"min\":%s,\"max\":%s,\"count\":%s,\"rounds\":%d,"
                            + "\"spread\":%.4f,\"each\":[%s]", number(m), number(lo), number(hi), (long) count, times.size(),
                            spread, String.join(",", inOrder));
                    if (oracle[k] != null && oracle[k] != count && !engine.equals("v8")) {
                        differ.add(String.format("%s %s %d vs V8 %d", engine, id, (long) count, oracle[k].longValue()));
                        System.err.printf("COUNT  %-13s %-62s %d against V8's %d%n", engine, id, (long) count,
                                oracle[k].longValue());
                        entry = engine.equals("jdk")
                                ? "{" + fig + ",\"differs\":" + quote("java.util.regex " + (long) count + ", V8 "
                                        + oracle[k].longValue()) + "}"
                                : "{\"error\":" + quote("counted " + (long) count + " where V8 counts "
                                        + oracle[k].longValue()) + "}";
                    } else {
                        entry = "{" + fig + "}";
                    }
                }
                rows.add(quote(all.get(k).id) + ":" + entry);
            }
            results.add(quote(engine) + ":{" + String.join(",", rows) + "}");
        }
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("rounds", String.valueOf(rounds));
        extra.put("protocol", "interleaved");
        extra.put("spread_limit", String.valueOf(limit));
        extra.put("spread_over", String.join("; ", over));
        extra.put("counts_differ", String.join("; ", differ));
        extra.put("v8", names.getOrDefault("v8", ""));
        extra.put("tregex", names.getOrDefault("tregex", ""));
        String end = firstLine("date", "-u", "+%Y-%m-%dT%H:%M:%SZ");
        String out = "{\"facts\":" + facts(seconds, passes, extra) + ",\"end\":" + quote(end) + ",\"results\":{"
                + String.join(",", results) + "}}\n";
        if (opt.containsKey("--out")) {
            Files.writeString(Paths.get(opt.get("--out")), out);
        } else {
            System.out.print(out);
        }
    }
}
