package io.github.corvusdotnet.regex.ecma262;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An anchored, leftmost-first automaton over ASCII text, which finds where the match that starts at a position ends
 * for a pattern that needs no backtracking ({@code adfa.rs}): what the {@code regex} crate's DFAs do for the match,
 * taken into this engine where ECMA-262's semantics allow it.
 *
 * <p>A state is an ordered list of the pattern's states (its threads, the most preferred first, as the backtracking
 * matcher would try them), with whether the unit before it was a word character and whether it is at the start of the
 * text. When the match state is among the threads, a match ends here, and the threads after it, which the backtracking
 * matcher would only try had this way failed, are dropped; the threads before it may still find a longer match, which
 * it would prefer. So the last match found before the threads run out is the backtracking matcher's match, for a
 * pattern with no lookaround, no backreference and no repetition whose body can match the empty string (where
 * ECMA-262's rule for an empty iteration would differ from a thread's). A unit beyond ASCII hands the start back to the
 * backtracking matcher.
 *
 * <p>Every state is worked out when the pattern is compiled, within bounds of states and steps, so that a pattern too
 * large for an automaton costs little before that is known. This class builds the automata; the loops that read them
 * over a text are in the templates' {@code Automata8} and {@code Automata16}.
 */
final class Adfa {
    private Adfa() {}

    private static final int MAX_STATES = 2048;
    private static final int MAX_NFA = 1024;
    private static final long MAX_STEPS = 2_000_000;
    /** No thread is left: no match can end further on. */
    static final int DEAD = 0;
    /** The flag of a transition across which a match ends before the unit. */
    private static final int MATCHED = 0x8000;
    /** The same flag in the premultiplied table. */
    static final int FAST_MATCHED = 0x8000_0000;

    // The kinds of the pattern's states.
    private static final int SET = 0;
    private static final int SPLIT = 1;
    private static final int BOL = 2;
    private static final int EOL = 3;
    private static final int WORD = 4;
    private static final int NOT_WORD = 5;
    private static final int MATCH = 6;

    /** The anchored automaton of a pattern. */
    static final class Anchored {
        final byte[] classOf = new byte[128];
        int shift;
        /**
         * {@code fast[state * stride + class]}: the next state, premultiplied by the stride, with {@link #FAST_MATCHED}
         * when a match ends before the unit.
         */
        int[] fast;
        /** For each state: a match ends at the end of the text. */
        boolean[] atEnd;
        /** The start state for each of: the unit before is a word character (bit 1), the start of the text (bit 0). */
        final int[] starts = new int[4];
        /** Whether the start states differ (the pattern looks back at the unit before or at the start of the text). */
        boolean context;
        /**
         * For the forward automaton (see {@link #buildForward}), whether each state is a start state: no thread of an
         * earlier start is alive in it.
         */
        boolean[] startLike;

        /**
         * The state after {@code prefix} from each start state, premultiplied by the stride (0 where no match can
         * start with it), or {@code null} when a match can end inside it or it holds a byte beyond ASCII: what {@code
         * findAfter} goes on from where a match is known to start with {@code prefix}.
         */
        int[] after(byte[] prefix) {
            int[] rows = new int[4];
            for (int k = 0; k < 4; k++) {
                int at = starts[k] << shift;
                for (byte b : prefix) {
                    if (at == 0) {
                        // No match from this start state begins with the prefix.
                        break;
                    }
                    if (b < 0) {
                        return null;
                    }
                    int t = fast[at + classOf[b]];
                    if ((t & FAST_MATCHED) != 0) {
                        return null;
                    }
                    at = t;
                }
                rows[k] = at;
            }
            return rows;
        }
    }

    /**
     * The positions of a pattern read before a match can end: V8's Boyer-Moore lookahead ({@code BoyerMooreLookahead}
     * in its regexp compiler). For a window of positions {@code lo..=hi} of every match, the units each can be ({@code
     * table} for ASCII, {@code wide} for any other) are joined; a unit at {@code hi} from a place that is none of them
     * rules out the starts from that place to {@code hi - lo} units on, so the search reads one unit in {@code hi - lo
     * + 1} where the window's units are rare.
     */
    static final class Skip {
        /** The window's last position, the one read. */
        final int hi;
        /** The starts one unit rules out, {@code hi - lo + 1}. */
        final int len;
        private final boolean[] table;
        private final boolean wide;

        Skip(int hi, int len, boolean[] table, boolean wide) {
            this.hi = hi;
            this.len = len;
            this.table = table;
            this.wide = wide;
        }

        /** Whether the unit can be at some position of the window. */
        boolean hit(int c) {
            return c < 128 ? table[c] : wide;
        }
    }

    /**
     * An automaton of the terms before the anchors of a search, read backwards (accepting where any of them can start):
     * from an occurrence of an anchor it finds, over ASCII text, every start from which some alternative's terms reach
     * it. Only terms with no assertion are taken.
     */
    static final class Reverse {
        final byte[] classOf = new byte[128];
        int shift;
        /**
         * The transitions, each the next state's row (premultiplied by the stride, so 0 is the dead state) with {@link
         * #FAST_MATCHED} where that state accepts.
         */
        int[] fast;
        /** Whether the first state accepts (an empty run of terms). */
        boolean acceptFirst;
    }

    /** The states of a pattern, as parallel arrays that grow. */
    private static final class Builder {
        int[] kind = new int[16];
        long[] set0 = new long[16];
        long[] set1 = new long[16];
        /** Whether the set holds a character beyond ASCII, which the automaton gives up at. */
        boolean[] wide = new boolean[16];
        int[] out = new int[16];
        int[] alt = new int[16];
        int size;
        boolean failed;

        int add(int k, long s0, long s1, boolean w, int o, int a) {
            if (size >= MAX_NFA) {
                failed = true;
                return 0;
            }
            if (size == kind.length) {
                int n = size * 2;
                kind = Arrays.copyOf(kind, n);
                set0 = Arrays.copyOf(set0, n);
                set1 = Arrays.copyOf(set1, n);
                wide = Arrays.copyOf(wide, n);
                out = Arrays.copyOf(out, n);
                alt = Arrays.copyOf(alt, n);
            }
            kind[size] = k;
            set0[size] = s0;
            set1[size] = s1;
            wide[size] = w;
            out[size] = o;
            alt[size] = a;
            return size++;
        }

        int add(int k, int o) {
            return add(k, 0, 0, false, o, 0);
        }

        /** A split whose preferred way is {@code first}. */
        int split(int first, int second) {
            return add(SPLIT, 0, 0, false, first, second);
        }

        boolean has(int i, int c) {
            return ((c < 64 ? set0[i] >>> c : set1[i] >>> (c - 64)) & 1) != 0;
        }

        /** The states of a node that go on to {@code next}; the state they start at. */
        int compile(Node n, int next) {
            if (failed) {
                return 0;
            }
            switch (n.kind) {
                case EMPTY:
                    return next;
                case CHAR: {
                    long s0 = 0;
                    long s1 = 0;
                    if (n.r >= 0 && n.r < 64) {
                        s0 = 1L << n.r;
                    } else if (n.r >= 64 && n.r < 128) {
                        s1 = 1L << (n.r - 64);
                    }
                    return add(SET, s0, s1, n.r >= 128, next, 0);
                }
                case SET: {
                    int[] r = n.set.ranges;
                    boolean w = r.length > 0 && r[r.length - 1] >= 128;
                    return add(SET, n.set.ascii0, n.set.ascii1, w, next, 0);
                }
                case CAT:
                    for (int k = n.subs.size() - 1; k >= 0; k--) {
                        next = compile(n.subs.get(k), next);
                    }
                    return next;
                case ALT: {
                    int last = n.subs.size() - 1;
                    int start = compile(n.subs.get(last), next);
                    for (int k = last - 1; k >= 0; k--) {
                        int o = compile(n.subs.get(k), next);
                        start = split(o, start);
                    }
                    return start;
                }
                case GROUP:
                    return compile(n.subs.get(0), next);
                case REPEAT: {
                    Node sub = n.subs.get(0);
                    if (n.max != 0 && n.max != 1 && Compiler.nullable(sub)) {
                        // ECMA-262 refuses an empty iteration after the least count, which a thread does not know.
                        failed = true;
                        return 0;
                    }
                    if (n.max == Node.UNBOUNDED) {
                        int loop = split(0, 0);
                        if (failed) {
                            return 0;
                        }
                        int body = compile(sub, loop);
                        // Written after the call, which may grow the arrays.
                        out[loop] = n.lazy ? next : body;
                        alt[loop] = n.lazy ? body : next;
                        next = loop;
                    } else {
                        for (long i = n.min; i < n.max && !failed; i++) {
                            int body = compile(sub, next);
                            next = n.lazy ? split(next, body) : split(body, next);
                        }
                    }
                    for (int i = 0; i < n.min && !failed; i++) {
                        next = compile(sub, next);
                    }
                    return next;
                }
                case BOL:
                    return add(BOL, next);
                case EOL:
                    return add(EOL, next);
                case WORD_BOUNDARY:
                    if (!n.fold) {
                        return add(WORD, next);
                    }
                    break;
                case NOT_WORD_BOUNDARY:
                    if (!n.fold) {
                        return add(NOT_WORD, next);
                    }
                    break;
                default:
                    break;
            }
            failed = true;
            return 0;
        }

        /** The states of a node read backwards that go on to {@code next}. */
        int compileBack(Node n, int next) {
            if (failed) {
                return 0;
            }
            switch (n.kind) {
                case CAT:
                    for (Node sub : n.subs) {
                        next = compileBack(sub, next);
                    }
                    return next;
                case ALT: {
                    int last = n.subs.size() - 1;
                    int start = compileBack(n.subs.get(last), next);
                    for (int k = last - 1; k >= 0; k--) {
                        int o = compileBack(n.subs.get(k), next);
                        start = split(o, start);
                    }
                    return start;
                }
                case GROUP:
                    return compileBack(n.subs.get(0), next);
                case REPEAT: {
                    Node sub = n.subs.get(0);
                    if (n.max == Node.UNBOUNDED) {
                        int loop = split(0, 0);
                        if (failed) {
                            return 0;
                        }
                        int body = compileBack(sub, loop);
                        out[loop] = body;
                        alt[loop] = next;
                        next = loop;
                    } else {
                        for (long i = n.min; i < n.max && !failed; i++) {
                            int body = compileBack(sub, next);
                            next = split(body, next);
                        }
                    }
                    for (int i = 0; i < n.min && !failed; i++) {
                        next = compileBack(sub, next);
                    }
                    return next;
                }
                case EMPTY:
                case CHAR:
                case SET:
                    return compile(n, next);
                default:
                    failed = true;
                    return 0;
            }
        }
    }

    /** Makes the automaton's states from lists of the pattern's states. */
    private static final class Det {
        final Builder nfa;
        final int[] mark;
        int generation;
        long steps = MAX_STEPS;
        private final IntList stack = new IntList(16);

        Det(Builder nfa) {
            this.nfa = nfa;
            this.mark = new int[nfa.size];
        }

        /**
         * Appends to {@code out}, in order of preference, the threads that reading nothing reaches from {@code from},
         * keeping the states that read a unit, the anchors and boundaries still to pass, and the match, after which
         * nothing more is kept. Boundaries are passed or dropped with {@code words}, and kept to pass later without it.
         */
        void closure(IntList out, IntList from, boolean atStart, boolean atEnd, boolean words, boolean before,
                boolean after) {
            generation++;
            closureOn(out, from, atStart, atEnd, words, before, after);
        }

        /** {@link #closure} after the closure just made, leaving out the states it reached. */
        void closureOn(IntList out, IntList from, boolean atStart, boolean atEnd, boolean words, boolean before,
                boolean after) {
            stack.clear();
            for (int k = from.size() - 1; k >= 0; k--) {
                stack.add(from.get(k));
            }
            while (stack.size() > 0) {
                int i = stack.pop();
                steps--;
                if (mark[i] == generation) {
                    continue;
                }
                mark[i] = generation;
                switch (nfa.kind[i]) {
                    case SPLIT:
                        stack.add(nfa.alt[i]);
                        stack.add(nfa.out[i]);
                        break;
                    case BOL:
                        if (atStart) {
                            stack.add(nfa.out[i]);
                        }
                        break;
                    case EOL:
                        if (atEnd) {
                            stack.add(nfa.out[i]);
                        } else {
                            out.add(i);
                        }
                        break;
                    case WORD:
                    case NOT_WORD:
                        if (!words) {
                            out.add(i);
                        } else if ((before != after) == (nfa.kind[i] == WORD)) {
                            stack.add(nfa.out[i]);
                        }
                        break;
                    case MATCH:
                        out.add(i);
                        return;
                    default:
                        out.add(i);
                        break;
                }
            }
        }

        boolean has(IntList set, int k) {
            for (int j = 0; j < set.size(); j++) {
                if (nfa.kind[set.get(j)] == k) {
                    return true;
                }
            }
            return false;
        }
    }

    private static boolean isWord(int c) {
        return CharSet.isWordByte(c);
    }

    /** The most positions looked at for a window. */
    private static final int SKIP_POSITIONS = 16;

    /** How often a unit is found in text, roughly: what a window's worth is reckoned by. */
    private static double frequency(int c) {
        if (c == ' ') {
            return 0.15;
        }
        if (c >= 'a' && c <= 'z') {
            return 0.06;
        }
        if (c == '\n' || c == '\r' || c == '.' || c == ',') {
            return 0.015;
        }
        if (c >= 'A' && c <= 'Z' || c >= '0' && c <= '9') {
            return 0.004;
        }
        return 0.003;
    }

    /**
     * The states each depth's threads are at for {@link #skipWindow}, assertions taken as passed (they only rule threads
     * out); {@code null} where a match can end, so that no later position is known.
     */
    private static IntList skipClosure(Builder nfa, IntList from) {
        boolean[] seen = new boolean[nfa.size];
        IntList stack = IntList.of(from.toArray());
        IntList sets = new IntList(8);
        while (stack.size() > 0) {
            int i = stack.pop();
            if (seen[i]) {
                continue;
            }
            seen[i] = true;
            switch (nfa.kind[i]) {
                case MATCH:
                    return null;
                case SET:
                    sets.add(i);
                    break;
                case SPLIT:
                    stack.add(nfa.out[i]);
                    stack.add(nfa.alt[i]);
                    break;
                default:
                    stack.add(nfa.out[i]);
                    break;
            }
        }
        return sets;
    }

    /**
     * The window of a tree (after simplify) worth reading one unit of instead of every unit, or {@code null}: the units
     * each position before a match can end can be, from the pattern's automaton without its determinisation, and the
     * window whose units are rare enough that one unit read passes over three starts or more, reckoned by {@link
     * #frequency}.
     */
    static Skip skipWindow(Node root) {
        Builder b = new Builder();
        int match = b.add(MATCH, 0);
        int start = b.compile(root, match);
        if (b.failed) {
            return null;
        }
        List<boolean[]> tables = new ArrayList<>();
        List<Boolean> wides = new ArrayList<>();
        IntList threads = IntList.of(start);
        while (tables.size() < SKIP_POSITIONS) {
            IntList sets = skipClosure(b, threads);
            if (sets == null) {
                break;
            }
            boolean[] table = new boolean[128];
            boolean wide = false;
            for (int k = 0; k < sets.size(); k++) {
                int i = sets.get(k);
                for (int c = 0; c < 128; c++) {
                    table[c] |= b.has(i, c);
                }
                wide |= b.wide[i];
            }
            tables.add(table);
            wides.add(wide);
            // A character beyond ASCII takes more than one unit of UTF-8: the positions after it are not known.
            if (wide) {
                break;
            }
            IntList next = new IntList(sets.size());
            for (int k = 0; k < sets.size(); k++) {
                next.add(b.out[sets.get(k)]);
            }
            threads = next;
        }
        double bestWorth = 0;
        int bestLo = -1;
        int bestHi = -1;
        for (int lo = 0; lo < tables.size(); lo++) {
            boolean[] table = new boolean[128];
            boolean wide = false;
            for (int hi = lo; hi < tables.size(); hi++) {
                boolean[] t = tables.get(hi);
                for (int c = 0; c < 128; c++) {
                    table[c] |= t[c];
                }
                wide |= wides.get(hi);
                double p = wide ? 0.1 : 0.0;
                for (int c = 0; c < 128; c++) {
                    if (table[c]) {
                        p += frequency(c);
                    }
                }
                double worth = (hi - lo + 1) * (1.0 - Math.min(p, 1.0));
                if (worth >= 3.0 && (bestLo < 0 || worth > bestWorth)) {
                    bestWorth = worth;
                    bestLo = lo;
                    bestHi = hi;
                }
            }
        }
        if (bestLo < 0) {
            return null;
        }
        boolean[] table = new boolean[128];
        boolean wide = false;
        for (int k = bestLo; k <= bestHi; k++) {
            boolean[] t = tables.get(k);
            for (int c = 0; c < 128; c++) {
                table[c] |= t[c];
            }
            wide |= wides.get(k);
        }
        return new Skip(bestHi, bestHi - bestLo + 1, table, wide);
    }

    /** Builds the anchored automaton of a tree (after simplify), or {@code null} when the pattern has none. */
    static Anchored build(Node root) {
        return buildWith(root, false);
    }

    /**
     * Builds the forward automaton of a tree: the anchored automaton with a new start joined at every unit, behind
     * every thread already alive (so last in preference, as the search tries later starts after earlier ones). Read
     * over the text from a start, it says where the first match ends, and its start states say where no thread of an
     * earlier start is alive: the leftmost match starts at or after the last of them, the forward search of the {@code
     * regex} crate.
     */
    static Anchored buildForward(Node root) {
        return buildWith(root, true);
    }

    /** The classes of units, with the number of classes last: those every set has both or neither of. */
    private static int classes(Builder nfa, byte[] classOf, boolean words) {
        Map<String, Integer> signatures = new HashMap<>();
        StringBuilder sig = new StringBuilder();
        for (int c = 0; c < 128; c++) {
            sig.setLength(0);
            if (words) {
                sig.append(isWord(c) ? '1' : '0');
            }
            for (int s = 0; s < nfa.size; s++) {
                if (nfa.kind[s] == SET) {
                    sig.append(nfa.has(s, c) ? '1' : '0');
                }
            }
            String key = sig.toString();
            Integer cls = signatures.get(key);
            if (cls == null) {
                cls = signatures.size();
                signatures.put(key, cls);
            }
            classOf[c] = (byte) (int) cls;
        }
        return signatures.size();
    }

    /** The states of an automaton being built, by their key. */
    private static final class Table {
        final Map<String, Integer> ids = new HashMap<>();
        final List<int[]> sets = new ArrayList<>();
        final List<boolean[]> bits = new ArrayList<>();
        int[] trans;
        boolean[] atEnd = new boolean[1];
        int stride;
    }

    private static String key(IntList set, boolean word, boolean atStart) {
        char[] k = new char[set.size() + 2];
        for (int i = 0; i < set.size(); i++) {
            k[i] = (char) set.get(i);
        }
        k[set.size()] = word ? '1' : '0';
        k[set.size() + 1] = atStart ? '1' : '0';
        return new String(k);
    }

    /** The threads of a forward state with those of the current start numbered as the pattern numbers them. */
    private static IntList decode(IntList set, int n) {
        IntList out = new IntList(set.size());
        for (int k = 0; k < set.size(); k++) {
            int i = set.get(k);
            out.add(i >= n ? i - n : i);
        }
        return out;
    }

    private static Anchored buildWith(Node root, boolean forward) {
        Builder nfa = new Builder();
        int match = nfa.add(MATCH, 0);
        int start = nfa.compile(root, match);
        if (nfa.failed) {
            return null;
        }
        Det d = new Det(nfa);
        // In the forward automaton a thread of the start joined at the current unit is kept apart from the threads of
        // earlier starts, as its state's number plus the number of states, so that a state whose threads are all of
        // the current start (a start state) is known: no earlier start can still match.
        int n = nfa.size;

        Anchored a = new Anchored();
        int classes = classes(nfa, a.classOf, true);
        int shift = 0;
        while (1 << shift < classes) {
            shift++;
        }
        int stride = 1 << shift;
        int[] sample = new int[classes];
        for (int c = 127; c >= 0; c--) {
            sample[a.classOf[c]] = c;
        }

        // The word bit matters only to a pattern with a boundary and the start bit only to one with a start anchor
        // (each may be reached later, behind an end anchor or a boundary still to pass, so the whole pattern is
        // asked).
        boolean boundaries = false;
        boolean anchors = false;
        for (int s = 0; s < n; s++) {
            boundaries |= nfa.kind[s] == WORD || nfa.kind[s] == NOT_WORD;
            anchors |= nfa.kind[s] == BOL;
        }
        Table t = new Table();
        t.sets.add(new int[0]);
        t.bits.add(new boolean[2]);
        t.trans = new int[stride];
        t.stride = stride;
        IntList startList = IntList.of(start);
        for (int k = 0; k < 4; k++) {
            boolean word = (k & 2) != 0;
            boolean atStart = (k & 1) != 0;
            IntList first = new IntList(8);
            d.closure(first, startList, atStart, false, false, false, false);
            if (forward) {
                for (int j = 0; j < first.size(); j++) {
                    first.set(j, first.get(j) + n);
                }
            }
            int s = intern(d, t, first, word && boundaries, atStart && anchors, n);
            if (s < 0) {
                return null;
            }
            a.starts[k] = s;
        }
        IntList resolved = new IntList(8);
        IntList moved = new IntList(8);
        for (int id = 1; id < t.sets.size(); id++) {
            IntList set = decode(IntList.of(t.sets.get(id)), n);
            boolean word = t.bits.get(id)[0];
            boolean atStart = t.bits.get(id)[1];
            for (int cl = 0; cl < classes; cl++) {
                int c = sample[cl];
                resolved.clear();
                d.closure(resolved, set, atStart, false, true, word, isWord(c));
                int flag = 0;
                for (int j = 0; j < resolved.size(); j++) {
                    if (nfa.kind[resolved.get(j)] == MATCH) {
                        // A match ends before this unit; only the threads it would have been tried after go on.
                        flag = MATCHED;
                        resolved.truncate(j);
                        break;
                    }
                }
                moved.clear();
                for (int j = 0; j < resolved.size(); j++) {
                    int i = resolved.get(j);
                    if (nfa.kind[i] == SET && nfa.has(i, c)) {
                        moved.add(nfa.out[i]);
                    }
                }
                IntList next = new IntList(8);
                d.closure(next, moved, false, false, false, false, false);
                if (forward && flag == 0) {
                    IntList fresh = new IntList(8);
                    d.closureOn(fresh, startList, false, false, false, false, false);
                    for (int j = 0; j < fresh.size(); j++) {
                        next.add(fresh.get(j) + n);
                    }
                }
                int to = intern(d, t, next, isWord(c) && boundaries, false, n);
                if (to < 0 || d.steps < 0) {
                    return null;
                }
                t.trans[id * stride + cl] = to | flag;
            }
        }
        a.shift = shift;
        a.fast = new int[t.trans.length];
        for (int k = 0; k < t.trans.length; k++) {
            int v = t.trans[k];
            a.fast[k] = ((v & ~MATCHED) << shift) | ((v & MATCHED) != 0 ? FAST_MATCHED : 0);
        }
        a.atEnd = Arrays.copyOf(t.atEnd, t.sets.size());
        a.context = a.starts[1] != a.starts[0] || a.starts[2] != a.starts[0] || a.starts[3] != a.starts[0];
        if (forward) {
            a.startLike = new boolean[t.sets.size()];
            for (int id = 0; id < t.sets.size(); id++) {
                int[] set = t.sets.get(id);
                boolean all = set.length > 0;
                for (int i : set) {
                    all &= i >= n;
                }
                a.startLike[id] = all;
            }
        }
        return a;
    }

    /** Gives a list of threads its state, keyed with the bits that matter; -1 when there are too many. */
    private static int intern(Det d, Table t, IntList set, boolean word, boolean atStart, int n) {
        if (set.size() == 0) {
            return DEAD;
        }
        String key = key(set, word, atStart);
        Integer known = t.ids.get(key);
        if (known != null) {
            return known;
        }
        if (t.sets.size() >= MAX_STATES) {
            return -1;
        }
        int id = t.sets.size();
        IntList closed = new IntList(8);
        d.closure(closed, decode(set, n), atStart, true, true, word, false);
        if (t.atEnd.length <= id) {
            t.atEnd = Arrays.copyOf(t.atEnd, Math.max(id + 1, t.atEnd.length * 2));
        }
        t.atEnd[id] = d.has(closed, MATCH);
        t.trans = Arrays.copyOf(t.trans, t.trans.length + t.stride);
        t.ids.put(key, id);
        t.sets.add(set.toArray());
        t.bits.add(new boolean[] {word, atStart});
        return id;
    }

    /**
     * The reverse automaton of the prefixes of a search's anchors, or {@code null} when one holds an assertion or the
     * automaton is too large.
     */
    static Reverse reverse(List<List<Node>> prefixes) {
        Builder b = new Builder();
        int match = b.add(MATCH, 0);
        IntList starts = new IntList(4);
        for (List<Node> prefix : prefixes) {
            int next = match;
            for (Node t : prefix) {
                next = b.compileBack(t, next);
            }
            starts.add(next);
        }
        if (b.failed || starts.size() == 0) {
            return null;
        }
        int start = starts.get(starts.size() - 1);
        for (int k = starts.size() - 2; k >= 0; k--) {
            start = b.split(starts.get(k), start);
        }
        if (b.failed) {
            return null;
        }
        Det d = new Det(b);
        Reverse r = new Reverse();
        int classes = classes(b, r.classOf, false);
        int shift = 0;
        while (1 << shift < classes) {
            shift++;
        }
        int stride = 1 << shift;
        int[] sample = new int[classes];
        for (int c = 127; c >= 0; c--) {
            sample[r.classOf[c]] = c;
        }
        Map<String, Integer> ids = new HashMap<>();
        List<int[]> sets = new ArrayList<>();
        sets.add(new int[0]);
        int[] trans = new int[2 * stride];
        boolean[] accept = new boolean[2];
        int[] first = reverseClosure(d, IntList.of(start));
        ids.put(key(first), 1);
        accept[1] = hasMatch(b, first);
        sets.add(first);
        for (int id = 1; id < sets.size(); id++) {
            for (int cl = 0; cl < classes; cl++) {
                int c = sample[cl];
                IntList moved = new IntList(8);
                for (int i : sets.get(id)) {
                    if (b.kind[i] == SET && b.has(i, c)) {
                        moved.add(b.out[i]);
                    }
                }
                int[] next = reverseClosure(d, moved);
                int to;
                if (next.length == 0) {
                    to = DEAD;
                } else {
                    String k = key(next);
                    Integer known = ids.get(k);
                    if (known != null) {
                        to = known;
                    } else {
                        if (sets.size() >= MAX_STATES) {
                            return null;
                        }
                        to = sets.size();
                        accept = Arrays.copyOf(accept, to + 1);
                        accept[to] = hasMatch(b, next);
                        ids.put(k, to);
                        sets.add(next);
                        trans = Arrays.copyOf(trans, trans.length + stride);
                    }
                }
                if (d.steps < 0) {
                    return null;
                }
                trans[id * stride + cl] = to;
            }
        }
        r.shift = shift;
        r.fast = new int[trans.length];
        for (int k = 0; k < trans.length; k++) {
            int v = trans[k];
            r.fast[k] = (v << shift) | (v != DEAD && accept[v] ? FAST_MATCHED : 0);
        }
        r.acceptFirst = accept[1];
        return r;
    }

    /** A sorted set of states as a key. */
    private static String key(int[] set) {
        char[] k = new char[set.length];
        for (int i = 0; i < set.length; i++) {
            k[i] = (char) set[i];
        }
        return new String(k);
    }

    private static boolean hasMatch(Builder b, int[] set) {
        for (int i : set) {
            if (b.kind[i] == MATCH) {
                return true;
            }
        }
        return false;
    }

    /** The closure of the reverse automaton, sorted: every thread is kept (any of them reaching the match is a start). */
    private static int[] reverseClosure(Det d, IntList from) {
        IntList out = new IntList(8);
        d.generation++;
        IntList stack = IntList.of(from.toArray());
        while (stack.size() > 0) {
            int i = stack.pop();
            d.steps--;
            if (d.mark[i] == d.generation) {
                continue;
            }
            d.mark[i] = d.generation;
            if (d.nfa.kind[i] == SPLIT) {
                stack.add(d.nfa.alt[i]);
                stack.add(d.nfa.out[i]);
            } else {
                out.add(i);
            }
        }
        int[] s = out.toArray();
        Arrays.sort(s);
        return s;
    }
}
