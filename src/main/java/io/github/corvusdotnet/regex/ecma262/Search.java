package io.github.corvusdotnet.regex.ecma262;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Finding where a match can start (section 4.3 of the design of the .NET reference engine), worked out once from the
 * syntax tree when a pattern is compiled ({@code search.rs}).
 *
 * <p>A search on a long text spends most of its time looking for where to start, so before the matcher runs the
 * program applies what it learned at compile time. What is searched for (an "anchor") is a literal, each of whose
 * characters may have several forms (a letter's cases under the i flag, or the members of a small class), that every
 * match holds at a known number of characters from its start: one number, or a bounded range. A pattern that is an
 * alternation, or that holds one after a bounded prefix ({@code .{0,2}(Tom|Sawyer)}), has an anchor in each
 * alternative, and all of them are searched for at once ({@link Literals}). Each occurrence opens the window of starts
 * from which some alternative could reach it; the starts are tried in order and never twice (the alternation windows).
 * Where every alternative starts with its literal, the matcher runs at the occurrence alone.
 *
 * <p>A pattern that starts with loops with no upper bound, then a literal ({@code [a-zA-Z]+ing}, {@code \w+\s+Holmes}),
 * has the literal searched for and the runs of the loops' sets before it walked back to the earliest start, which is
 * the only one tried for that occurrence: a later start in the same run can only reach a subset of the places the
 * loops can end at.
 */
final class Search {
    /** How the search finds where a match can start, beyond the first set. */
    enum Plan {
        /** Every position the first set allows is tried. */
        SCAN,
        /**
         * Every match holds one of the literals {@code lo} to {@code hi} characters after its start. {@code rest} is the
         * anchored automaton of the anchors and the terms after each, run at an occurrence first where a window has
         * more than one start: where nothing from the occurrence on can match, its window is not tried.
         */
        WINDOWS,
        /**
         * Every match is runs of the loops' sets, then the literal. {@code rest} is the anchored automaton of the literal
         * and the terms after it, run at each occurrence first.
         */
        AFTER_LOOPS,
    }

    /**
     * The shape of a pattern whose matches the search finds without running the matcher (the .NET reference engine's
     * simple shapes). Only for a pattern with no group.
     */
    enum Shape {
        NONE,
        /**
         * Literals and nothing else, an alternation of them or one, with every form of each written out: the search's
         * own match, which is the first literal in the pattern's order of those that start leftmost.
         */
        LITERALS,
        /**
         * An alternation of literals each of whose characters has several forms: at each occurrence the search finds,
         * the alternatives are compared in order.
         */
        FORMS,
        /** One class and nothing else: every member is a match. */
        SET,
        /**
         * One greedy loop of a class with no upper bound and a least count of zero or one: a match is the run of members
         * from where it starts.
         */
        LOOP,
    }

    /** How the end of a run of a loop's members is found ({@code RunEnd} of {@code search.rs}). */
    enum RunEnd {
        /** A member at a time. */
        EACH,
        /** The set holds every character: the run is the rest of the text. */
        ALL,
        /** The set holds every character but up to three ASCII ones, found by the searches for bytes. */
        STOPS,
        /**
         * The set holds every character but the four line terminators (the dot): LF and CR are found by the search for
         * bytes, with the lead byte of U+2028 and U+2029, which is looked at further where it is found.
         */
        LINE,
    }

    /** The most literals the forms of one anchor are expanded to. */
    static final int MAX_FORMS = 64;
    /** The most literals of all the anchors of a pattern. */
    static final int MAX_LITERALS = 512;
    /** The most characters a window of starts may span. */
    static final int MAX_WINDOW = 64;

    Plan plan = Plan.SCAN;
    Shape shape = Shape.NONE;
    /** The literals of the windows and of the search after loops. */
    Literals literals;
    /** The distances of the windows. */
    int lo;
    int hi;
    /** The reverse automaton of the windows' prefixes (see {@link Adfa#reverse}). */
    Adfa.Reverse reverse;
    /** The check of what follows an occurrence (see {@link RestCheck}). */
    RestCheck rest;
    /** The loops of the search after loops, and their least counts. */
    CharSet[] sets;
    int[] mins;
    /** The alternatives of {@link Shape#FORMS}: for each, the forms of each of its characters. */
    int[][][] forms;
    /** The class of {@link Shape#SET} and {@link Shape#LOOP}, the loop's least count, and how its run ends. */
    CharSet shapeSet;
    int shapeMin;
    RunEnd runEnd = RunEnd.EACH;
    /** The stops of {@link RunEnd#STOPS}, and how many. */
    int stop0;
    int stop1;
    int stop2;
    int stops;
    /** The units that end a run over UTF-16, for {@link RunEnd#STOPS} and {@link RunEnd#LINE}. */
    UnitSearch.UnitSet stops16;
    /**
     * The set of a loop every match starts with, when a start that fails may move to the end of the run of its set:
     * every start in the rest of the run can only reach a subset of the places the loop can end at.
     */
    CharSet skipRun;
    /**
     * The pattern starts with {@code \b} and every character a match can start with is an ASCII word character, so a
     * start inside a word fails and the search passes over the rest of the word.
     */
    boolean leadingWordBoundary;
    /**
     * The bytes a match can start with over UTF-8, searched for together when they are few: the first set's ASCII
     * members and the lead bytes of its other members.
     */
    FirstBytes firstBytes;
    /** The code units a match can start with over UTF-16. */
    UnitSearch.UnitSet firstUnits;

    /** The one literal searched for over UTF-8, when there is one with no other forms; {@code null} otherwise. */
    byte[] singleUtf8() {
        return literals == null ? null : literals.single8;
    }

    /** Whether the plan is the windows at distance 0: every match starts with one of the literals. */
    boolean windowsAtZero() {
        return plan == Plan.WINDOWS && hi == 0;
    }

    /**
     * Makes every pattern compiled on this thread try every position its first set allows, which the tests compare the
     * searches with.
     */
    static final ThreadLocal<boolean[]> NO_SEARCH = ThreadLocal.withInitial(() -> new boolean[1]);

    static boolean noSearch() {
        return NO_SEARCH.get()[0];
    }

    /**
     * The anchored automaton of what follows an occurrence (an anchor and the terms after it), run at the occurrence
     * before any start is walked back to. A check that seldom rules an occurrence out costs more than it saves (an
     * e-mail address's {@code @} is nearly always followed by a domain), so the check keeps count over its first trials
     * and is dropped for good when fewer than one in eight of them ruled the occurrence out. The counts change only how
     * fast a search is, never what it finds, so threads share them without locks.
     */
    static final class RestCheck {
        /** The checks a check makes before it decides whether it pays. */
        static final int TRIALS = 64;

        final Adfa.Anchored dfa;
        int checks;
        int rejects;

        RestCheck(Adfa.Anchored dfa) {
            this.dfa = dfa;
        }

        /** Whether the check is still made. */
        boolean pays() {
            return checks < TRIALS || rejects * 8 >= TRIALS;
        }

        /** Counts a check while it is still on trial. */
        void count(boolean out) {
            int c = checks;
            if (c < TRIALS) {
                checks = c + 1;
                if (out) {
                    rejects++;
                }
            }
        }
    }

    /**
     * A search for any of a few bytes: the searches for one to three bytes, and a table for up to sixty-four, which is
     * the set the crate finds with the fingerprint search of one-byte literals.
     */
    static final class FirstBytes {
        final int count;
        final int a;
        final int b;
        final int c;
        final boolean[] table;

        private FirstBytes(int count, int a, int b, int c, boolean[] table) {
            this.count = count;
            this.a = a;
            this.b = b;
            this.c = c;
            this.table = table;
        }

        static FirstBytes of(CharSet set, boolean units) {
            boolean[] bytes = new boolean[256];
            int[] r = set.ranges;
            int[][] spans = {{0, 0x7F, 0, 0x00}, {0x80, 0x7FF, 6, 0xC0}, {0x800, 0xFFFF, 12, 0xE0},
                {0x10000, 0x10FFFF, 18, 0xF0}};
            for (int k = 0; k < r.length; k += 2) {
                int lo = r[k];
                int hi = r[k + 1];
                // A surrogate without the u flag is half of a character of four bytes, and U+FFFD is also what a byte
                // that is not UTF-8 reads as: neither starts at a byte the set can name.
                if (units && lo <= 0xDFFF && hi >= 0xD800 || lo <= 0xFFFD && hi >= 0xFFFD) {
                    return null;
                }
                for (int[] s : spans) {
                    int x = Math.max(lo, s[0]);
                    int y = Math.min(hi, s[1]);
                    if (x <= y) {
                        for (int byt = s[3] | (x >> s[2]); byt <= (s[3] | (y >> s[2])); byt++) {
                            bytes[byt] = true;
                        }
                    }
                }
            }
            int[] list = new int[256];
            int n = 0;
            for (int byt = 0; byt < 256; byt++) {
                if (bytes[byt]) {
                    list[n++] = byt;
                }
            }
            if (n == 0 || n > MAX_FORMS) {
                return null;
            }
            return new FirstBytes(n, list[0], n > 1 ? list[1] : 0, n > 2 ? list[2] : 0, bytes);
        }

        /** The first position at or after {@code from} whose byte is one of the bytes; -1 for none. */
        int find(byte[] h, int from) {
            switch (count) {
                case 1:
                    return Kernels.indexOf(h, from, h.length, a);
                case 2:
                    return Kernels.indexOf(h, from, h.length, a, b);
                case 3:
                    return Kernels.indexOf(h, from, h.length, a, b, c);
                default:
                    boolean[] t = table;
                    for (int at = from; at < h.length; at++) {
                        if (t[h[at] & 0xFF]) {
                            return at;
                        }
                    }
                    return -1;
            }
        }
    }

    /**
     * The test for the code units a match can start with over UTF-16: the first set's units as up to eight ranges (a
     * member beyond the BMP as its lead surrogate), where more are merged across the narrowest gaps, the ASCII and Latin
     * ones last since those units are common in text. A candidate is then tested against the first set itself.
     */
    static UnitSearch.UnitSet firstUnits(CharSet set) {
        List<long[]> units = new ArrayList<>();
        int[] r = set.ranges;
        for (int k = 0; k < r.length; k += 2) {
            long lo = r[k];
            long hi = r[k + 1];
            if (lo <= 0xFFFF) {
                units.add(new long[] {lo, Math.min(hi, 0xFFFF)});
            }
            if (hi > 0xFFFF) {
                units.add(new long[] {lead(Math.max(lo, 0x10000)), lead(hi)});
            }
        }
        units.sort((x, y) -> x[0] != y[0] ? Long.compare(x[0], y[0]) : Long.compare(x[1], y[1]));
        List<long[]> merged = new ArrayList<>();
        for (long[] u : units) {
            long[] last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && u[0] <= last[1] + 1) {
                last[1] = Math.max(last[1], u[1]);
            } else {
                merged.add(new long[] {u[0], u[1]});
            }
        }
        while (merged.size() > 8) {
            // The gap whose units are least likely in text: its width, a thousand times over below U+0300.
            int best = 0;
            long bestCost = Long.MAX_VALUE;
            for (int k = 0; k < merged.size() - 1; k++) {
                long a = merged.get(k)[1] + 1;
                long b = merged.get(k + 1)[0];
                long cost = (b - a) * (a < 0x300 ? 1000 : 1);
                if (cost < bestCost) {
                    bestCost = cost;
                    best = k;
                }
            }
            merged.get(best)[1] = merged.get(best + 1)[1];
            merged.remove(best + 1);
        }
        if (merged.isEmpty()) {
            return null;
        }
        int[] ranges = new int[2 * merged.size()];
        for (int k = 0; k < merged.size(); k++) {
            ranges[2 * k] = (int) merged.get(k)[0];
            ranges[2 * k + 1] = (int) merged.get(k)[1];
        }
        return new UnitSearch.UnitSet(ranges);
    }

    private static long lead(long cp) {
        return 0xD800 + ((cp - 0x10000) >> 10);
    }

    /** One literal every match of an alternative holds, {@code lo} to {@code hi} characters after its start. */
    static final class Anchor {
        /** The forms of each of its characters. */
        final List<int[]> chars;
        final long lo;
        final long hi;
        /** The terms a match goes through before the anchor. */
        final List<Node> prefix;
        /** The terms from the anchor on, to the end of the pattern. */
        final List<Node> rest;

        Anchor(List<int[]> chars, long lo, long hi, List<Node> prefix, List<Node> rest) {
            this.chars = chars;
            this.lo = lo;
            this.hi = hi;
            this.prefix = prefix;
            this.rest = rest;
        }
    }

    /** The forms of a character the search can look for: a literal code point, or the members of a small class. */
    static int[] forms(Node n) {
        if (n.kind == Node.Kind.CHAR) {
            return Compiler.literalRune(n.r) ? new int[] {n.r} : null;
        }
        if (n.kind != Node.Kind.SET) {
            return null;
        }
        int[] r = n.set.ranges;
        int[] out = new int[4];
        int count = 0;
        for (int k = 0; k < r.length; k += 2) {
            int lo = r[k];
            int hi = r[k + 1];
            if (hi - lo >= 4 || count + (hi - lo + 1) > 4) {
                return null;
            }
            for (int c = lo; c <= hi; c++) {
                if (!Compiler.literalRune(c)) {
                    return null;
                }
                out[count++] = c;
            }
        }
        return count == 0 ? null : Arrays.copyOf(out, count);
    }

    /** The terms of a node in order: a sequence and a group whose body is a sequence are spliced in. */
    static List<Node> terms(Node n) {
        List<Node> out = new ArrayList<>();
        addTerms(n, out);
        return out;
    }

    private static void addTerms(Node n, List<Node> out) {
        switch (n.kind) {
            case CAT:
                for (Node s : n.subs) {
                    addTerms(s, out);
                }
                break;
            case GROUP:
                if (n.subs.get(0).kind != Node.Kind.ALT) {
                    addTerms(n.subs.get(0), out);
                } else {
                    out.add(n);
                }
                break;
            case EMPTY:
                break;
            default:
                out.add(n);
                break;
        }
    }

    /** A bound past every window, so that sums of widths saturate there. */
    private static final long FAR = 1L << 40;

    private static long add(long a, long b) {
        return Math.min(a + b, FAR);
    }

    private static long mul(long a, long b) {
        return a == 0 || b == 0 ? 0 : Math.min(a, FAR) * Math.min(b, FAR) >= FAR ? FAR : a * b;
    }

    /**
     * The least and the greatest number of characters a node consumes (the greatest -1 for no bound), or {@code null}
     * when it is not known (a backreference).
     */
    static long[] width(Node n) {
        switch (n.kind) {
            case CHAR:
            case SET:
                return new long[] {1, 1};
            case CAT: {
                long lo = 0;
                long hi = 0;
                for (Node sub : n.subs) {
                    long[] w = width(sub);
                    if (w == null) {
                        return null;
                    }
                    lo = add(lo, w[0]);
                    hi = hi < 0 || w[1] < 0 ? -1 : add(hi, w[1]);
                }
                return new long[] {lo, hi};
            }
            case ALT: {
                long[] out = null;
                for (Node sub : n.subs) {
                    long[] w = width(sub);
                    if (w == null) {
                        return null;
                    }
                    if (out == null) {
                        out = w;
                    } else {
                        out = new long[] {Math.min(out[0], w[0]), out[1] < 0 || w[1] < 0 ? -1 : Math.max(out[1], w[1])};
                    }
                }
                return out == null ? new long[] {0, 0} : out;
            }
            case GROUP:
                return width(n.subs.get(0));
            case REPEAT: {
                long[] w = width(n.subs.get(0));
                if (w == null) {
                    return null;
                }
                long lo = mul(w[0], n.min);
                long hi;
                if (n.max == Node.UNBOUNDED) {
                    hi = w[1] == 0 ? 0 : -1;
                } else {
                    hi = w[1] < 0 ? -1 : mul(w[1], n.max);
                }
                return new long[] {lo, hi};
            }
            case BACKREF:
                return null;
            default:
                return new long[] {0, 0};
        }
    }

    /**
     * The anchors of a node whose match starts {@code lo} to {@code hi} characters after the start of the whole match:
     * the first run of literal characters of each of its alternatives, or {@code null} when some alternative has none at
     * a bounded distance.
     */
    static List<Anchor> anchors(Node n, long lo, long hi) {
        return anchorsAfter(n, lo, hi, new ArrayList<>(), new ArrayList<>());
    }

    /** The anchors of {@code n}, which the terms {@code before} precede and the terms {@code after} follow. */
    private static List<Anchor> anchorsAfter(Node n, long lo, long hi, List<Node> before, List<Node> after) {
        List<Node> ts = terms(n);
        List<Node> prefix = new ArrayList<>(before);
        int k = 0;
        while (k < ts.size()) {
            Node t = ts.get(k);
            if (forms(t) != null) {
                List<Node> rest = new ArrayList<>(ts.subList(k, ts.size()));
                rest.addAll(after);
                List<int[]> chars = new ArrayList<>();
                int[] f;
                while (k < ts.size() && (f = forms(ts.get(k))) != null) {
                    chars.add(f);
                    k++;
                }
                List<Anchor> one = new ArrayList<>();
                one.add(new Anchor(chars, lo, hi, prefix, rest));
                return one;
            }
            Node alt = null;
            if (t.kind == Node.Kind.ALT) {
                alt = t;
            } else if (t.kind == Node.Kind.GROUP && t.subs.get(0).kind == Node.Kind.ALT) {
                alt = t.subs.get(0);
            }
            if (alt != null) {
                List<Node> following = new ArrayList<>(ts.subList(k + 1, ts.size()));
                following.addAll(after);
                List<Anchor> all = new ArrayList<>();
                for (Node sub : alt.subs) {
                    List<Anchor> found = anchorsAfter(sub, lo, hi, prefix, following);
                    if (found == null) {
                        return null;
                    }
                    all.addAll(found);
                }
                return all;
            }
            long[] w = width(t);
            if (w == null || w[1] < 0) {
                return null;
            }
            lo = add(lo, w[0]);
            hi = add(hi, w[1]);
            if (hi > MAX_WINDOW) {
                return null;
            }
            prefix.add(t);
            k++;
        }
        return null;
    }

    /** The literals of an anchor: the forms of its first {@code count} characters written out. */
    static List<int[]> expand(Anchor a, int count) {
        List<int[]> out = new ArrayList<>();
        out.add(new int[0]);
        for (int c = 0; c < count; c++) {
            int[] f = a.chars.get(c);
            List<int[]> next = new ArrayList<>(out.size() * f.length);
            for (int[] prefix : out) {
                for (int r : f) {
                    int[] l = Arrays.copyOf(prefix, prefix.length + 1);
                    l[prefix.length] = r;
                    next.add(l);
                }
            }
            out = next;
        }
        return out;
    }

    /**
     * The most bytes of each of an anchor's literals written out over UTF-8: where the anchor was cut short, every
     * literal is cut to the bytes of its shortest, so that the forms of a character longer than the others (the long s,
     * two bytes beside the one of s) make no more literals than the others. The {@code regex} crate's rule.
     */
    static int[] utf8Cuts(boolean cutShort, List<int[]> literals) {
        int cut = Integer.MAX_VALUE;
        if (cutShort) {
            for (int[] l : literals) {
                int bytes = 0;
                for (int r : l) {
                    bytes += r < 0x80 ? 1 : r < 0x800 ? 2 : r < 0x10000 ? 3 : 4;
                }
                cut = Math.min(cut, bytes);
            }
        }
        int[] out = new int[literals.size()];
        Arrays.fill(out, cut);
        return out;
    }

    /** The number of literals the anchors make with the first {@code k} characters of each written out. */
    private static long total(List<Anchor> anchors, int k) {
        long t = 0;
        for (Anchor a : anchors) {
            long p = 1;
            for (int c = 0; c < Math.min(a.chars.size(), k); c++) {
                p = mul(p, a.chars.get(c).length);
            }
            t = add(t, p);
        }
        return t;
    }

    /**
     * How many of each anchor's characters to write out, then 1 or 0 for whether the anchors were cut short. Exact
     * anchors are written out in full. Where a character has several forms (a letter under the i flag), every anchor is
     * cut to one length by the rule of the {@code regex} crate's literal optimisation ({@code
     * Seq::optimize_by_preference}): the full literals if they are ten or fewer, else five characters if that makes ten
     * or fewer, else four if that makes sixty-four or fewer, else three if sixty-four or fewer, else two if ten or
     * fewer, else one.
     */
    static int[] counts(List<Anchor> anchors) {
        int[] out = new int[anchors.size() + 1];
        boolean exact = true;
        for (Anchor a : anchors) {
            for (int[] f : a.chars) {
                exact &= f.length == 1;
            }
        }
        int k;
        if (exact || total(anchors, Integer.MAX_VALUE) <= 10) {
            k = Integer.MAX_VALUE;
        } else if (total(anchors, 5) <= 10) {
            k = 5;
        } else if (total(anchors, 4) <= MAX_FORMS) {
            k = 4;
        } else if (total(anchors, 3) <= MAX_FORMS) {
            k = 3;
        } else if (total(anchors, 2) <= 10) {
            k = 2;
        } else {
            k = 1;
        }
        for (int i = 0; i < anchors.size(); i++) {
            out[i] = Math.min(anchors.get(i).chars.size(), k);
        }
        out[anchors.size()] = !exact && k != Integer.MAX_VALUE ? 1 : 0;
        return out;
    }

    /** Whether a set holds ASCII word characters alone. */
    private static boolean wordSet(CharSet f) {
        if (f == null || f.ranges.length == 0 || f.ranges[f.ranges.length - 1] >= 0x80) {
            return false;
        }
        for (int c = 0; c < 0x80; c++) {
            if (f.hasAscii(c) && !CharSet.isWordByte(c)) {
                return false;
            }
        }
        return true;
    }

    /** The top-level alternatives of a tree: the alternatives of an alternation (in a group or not), or the tree. */
    static List<Node> alternatives(Node root) {
        if (root.kind == Node.Kind.ALT) {
            return root.subs;
        }
        if (root.kind == Node.Kind.GROUP && root.group == 0 && root.subs.get(0).kind == Node.Kind.ALT) {
            return root.subs.get(0).subs;
        }
        List<Node> one = new ArrayList<>();
        one.add(root);
        return one;
    }

    /** Whether a node holds a capturing group. */
    static boolean holdsGroup(Node n) {
        if (n.kind == Node.Kind.GROUP && n.group > 0) {
            return true;
        }
        for (Node s : n.subs) {
            if (holdsGroup(s)) {
                return true;
            }
        }
        return false;
    }

    /** The literal shapes of a tree whose anchors are {@code found}: literals and nothing else, exact or with forms. */
    private void shapeOfLiterals(Node root, List<Anchor> found, int[] counts, boolean cutShort) {
        if (holdsGroup(root)) {
            return;
        }
        List<Node> alts = alternatives(root);
        int[][][] all = new int[alts.size()][][];
        for (int a = 0; a < alts.size(); a++) {
            List<Node> ts = terms(alts.get(a));
            if (ts.isEmpty()) {
                return;
            }
            all[a] = new int[ts.size()][];
            for (int k = 0; k < ts.size(); k++) {
                all[a][k] = forms(ts.get(k));
                if (all[a][k] == null) {
                    return;
                }
            }
        }
        // Literals written out in full, every form of each, are the search's own literals, in the pattern's order (an
        // alternative's forms together), so the search's leftmost-first match is the pattern's.
        boolean literal = !cutShort && found.size() == all.length;
        for (int a = 0; a < all.length && literal; a++) {
            Anchor an = found.get(a);
            literal = an.lo == 0 && an.hi == 0 && counts[a] == all[a].length;
        }
        if (literal) {
            shape = Shape.LITERALS;
        } else {
            shape = Shape.FORMS;
            forms = all;
        }
    }

    /** A loop of one character or class with no upper bound: its set, or {@code null}. */
    static CharSet openLoop(Node t) {
        if (t.kind != Node.Kind.REPEAT || t.max != Node.UNBOUNDED) {
            return null;
        }
        Node body = t.subs.get(0);
        if (body.kind == Node.Kind.SET) {
            return body.set;
        }
        if (body.kind == Node.Kind.CHAR) {
            return CharSet.of(new int[] {body.r, body.r}, false);
        }
        return null;
    }

    /** The class and loop shapes of a tree that is one term. */
    private void shapeOfOne(Node root) {
        if (holdsGroup(root)) {
            return;
        }
        List<Node> ts = terms(root);
        if (ts.size() != 1) {
            return;
        }
        Node t = ts.get(0);
        if (t.kind == Node.Kind.SET && !t.set.isEmpty()) {
            shape = Shape.SET;
            shapeSet = t.set;
        } else if (t.kind == Node.Kind.REPEAT && !t.lazy && t.min <= 1) {
            CharSet set = openLoop(t);
            if (set != null && !set.isEmpty()) {
                shape = Shape.LOOP;
                shapeSet = set;
                shapeMin = t.min;
                setRunEnd(set);
            }
        }
    }

    /** How the end of a run of the set's members is found. */
    private void setRunEnd(CharSet set) {
        int[] complement = set.complement().ranges;
        if (complement.length == 0) {
            runEnd = RunEnd.ALL;
            return;
        }
        if (Arrays.equals(complement, new int[] {0x0A, 0x0A, 0x0D, 0x0D, 0x2028, 0x2029})) {
            runEnd = RunEnd.LINE;
            stops16 = new UnitSearch.UnitSet(complement);
            return;
        }
        int[] stop = new int[3];
        int n = 0;
        for (int k = 0; k < complement.length; k += 2) {
            int lo = complement[k];
            int hi = complement[k + 1];
            if (hi >= 0x80 || hi - lo >= 3 || n + (hi - lo + 1) > 3) {
                runEnd = RunEnd.EACH;
                return;
            }
            for (int c = lo; c <= hi; c++) {
                stop[n++] = c;
            }
        }
        runEnd = RunEnd.STOPS;
        stops = n;
        stop0 = stop[0];
        stop1 = n > 1 ? stop[1] : stop[0];
        stop2 = n > 2 ? stop[2] : stop1;
        stops16 = new UnitSearch.UnitSet(complement);
    }

    /**
     * Works out the search of a tree, after simplify. {@code backrefs} says the pattern has a backreference, which rules
     * out what depends on a start reaching a subset of another's places; {@code first} is the first set of the program.
     */
    static Search plan(Node root, boolean backrefs, CharSet first, boolean units) {
        Search search = new Search();
        if (noSearch()) {
            return search;
        }
        if (first != null) {
            search.firstBytes = FirstBytes.of(first, units);
            search.firstUnits = firstUnits(first);
        }
        List<Node> ts = terms(root);
        // A leading \b before characters that are all ASCII word characters.
        boolean boundary = !ts.isEmpty() && ts.get(0).kind == Node.Kind.WORD_BOUNDARY && !ts.get(0).fold;
        if (boundary && wordSet(first)) {
            search.leadingWordBoundary = true;
        }
        List<Anchor> found = anchors(root, 0, 0);
        if (found != null) {
            List<int[]> literals = new ArrayList<>();
            IntList cuts = new IntList();
            long lo = Long.MAX_VALUE;
            long hi = 0;
            boolean withForms = false;
            int[] counts = counts(found);
            boolean cutShort = counts[found.size()] == 1;
            for (int i = 0; i < found.size(); i++) {
                Anchor a = found.get(i);
                lo = Math.min(lo, a.lo);
                hi = Math.max(hi, a.hi);
                for (int[] f : a.chars) {
                    withForms |= f.length > 1;
                }
                List<int[]> expanded = expand(a, counts[i]);
                int[] c = utf8Cuts(cutShort, expanded);
                for (int e = 0; e < expanded.size(); e++) {
                    boolean seen = false;
                    for (int[] l : literals) {
                        seen |= Arrays.equals(l, expanded.get(e));
                    }
                    if (!seen) {
                        literals.add(expanded.get(e));
                        cuts.add(c[e]);
                    }
                }
            }
            boolean nonEmpty = true;
            for (int[] l : literals) {
                nonEmpty &= l.length > 0;
            }
            if (!literals.isEmpty() && literals.size() <= MAX_LITERALS && nonEmpty) {
                boolean fingerprint = literals.size() >= 3 || withForms;
                search.shapeOfLiterals(root, found, counts, cutShort);
                search.literals = new Literals(literals, cuts.toArray(), fingerprint);
                // Where a window has more than one start, the terms before the anchors are matched backwards from each
                // occurrence, so that only the starts they allow are tried (the reverse search of the regex crate).
                if (hi > 0) {
                    List<List<Node>> prefixes = new ArrayList<>();
                    boolean longer = false;
                    for (Anchor a : found) {
                        prefixes.add(a.prefix);
                        longer |= a.rest.size() > a.chars.size();
                    }
                    search.reverse = Adfa.reverse(prefixes);
                    // Where every anchor is the end of its alternative, the occurrence is all the rest there is to check.
                    if (longer) {
                        List<Node> alts = new ArrayList<>();
                        for (Anchor a : found) {
                            alts.add(Node.withSubs(Node.Kind.CAT, new ArrayList<>(a.rest)));
                        }
                        Adfa.Anchored dfa = Adfa.build(Node.withSubs(Node.Kind.ALT, alts));
                        search.rest = dfa == null ? null : new RestCheck(dfa);
                    }
                }
                search.plan = Plan.WINDOWS;
                search.lo = (int) lo;
                search.hi = (int) hi;
                return search;
            }
        }
        search.shapeOfOne(root);
        if (backrefs) {
            return search;
        }
        // Loops with no upper bound, then a literal: leading \b allowed when the first loop's set is of word characters.
        int k = boundary ? 1 : 0;
        List<CharSet> sets = new ArrayList<>();
        IntList mins = new IntList();
        CharSet set;
        while (k < ts.size() && (set = openLoop(ts.get(k))) != null) {
            sets.add(set);
            mins.add(ts.get(k).min);
            k++;
        }
        if (!sets.isEmpty()) {
            // \b is allowed when every loop's set is of word characters (a later start of the runs can be in any loop's run,
            // where \b holds after a word character before a member that is not one: \b\d*\s*a over " a"), and the sets
            // must share no character, any two of them.
            boolean wordSet = true;
            for (CharSet each : sets) {
                wordSet &= wordSet(each);
            }
            boolean disjoint = true;
            for (int i = 0; i < sets.size(); i++) {
                for (int j = i + 1; j < sets.size(); j++) {
                    disjoint &= sets.get(i).intersect(sets.get(j)).isEmpty();
                }
            }
            boolean small = true;
            for (int i = 0; i < mins.size(); i++) {
                small &= mins.get(i) <= 1;
            }
            List<int[]> chars = new ArrayList<>();
            int literalAt = k;
            int[] f;
            while (k < ts.size() && (f = forms(ts.get(k))) != null) {
                chars.add(f);
                k++;
            }
            if ((!boundary || wordSet) && disjoint && small && !chars.isEmpty()) {
                Anchor anchor = new Anchor(chars, 0, 0, new ArrayList<>(), new ArrayList<>());
                List<Anchor> one = new ArrayList<>();
                one.add(anchor);
                int[] count = counts(one);
                List<int[]> literals = expand(anchor, count[0]);
                int[] cuts = utf8Cuts(count[1] == 1, literals);
                List<Node> rest = new ArrayList<>(ts.subList(literalAt, ts.size()));
                search.plan = Plan.AFTER_LOOPS;
                search.literals = new Literals(literals, cuts, literals.size() >= 2);
                search.sets = sets.toArray(new CharSet[0]);
                search.mins = mins.toArray();
                if (rest.size() > anchor.chars.size()) {
                    Adfa.Anchored dfa = Adfa.build(Node.withSubs(Node.Kind.CAT, rest));
                    search.rest = dfa == null ? null : new RestCheck(dfa);
                }
            }
            // A failed start may skip the rest of its leading loop's run (the loop is the first term).
            if (!boundary) {
                search.skipRun = sets.get(0);
            }
        }
        return search;
    }
}
