package io.github.corvusdotnet.regex.ecma262;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * A deterministic automaton over ASCII text, for the patterns that need no backtracking ({@code dfa.rs}).
 *
 * <p>The strings a schema tests are short and nearly always ASCII, and most patterns in schemas are small. For such a
 * pattern every state set the text can lead to is worked out once, and a match is then one table read per byte.
 *
 * <p>The automaton reads units below 0x80 only. A text with any other unit is handed back to the backtracking matcher
 * from the start, so nothing here needs to know UTF-8 or the Unicode classes: a class is its ASCII members. It takes
 * {@code \b} and {@code \B} (not those of a case-insensitive pattern with the u flag, which fold): a state carries
 * whether the unit before it was a word character and whether it is at the start of the text, and a boundary still to
 * pass is passed, or not, when the next unit is known (section 4.4 of the design of the .NET reference engine). A
 * pattern whose automaton has more states than a byte can number has no automaton. The automaton is built on the
 * first match, not when the pattern is compiled, so a program pays nothing for the patterns it never matches.
 */
final class Dfa {
    // The states every automaton has. DEAD: no match can follow. ACCEPT: a match was found. BAIL: the text is not
    // ASCII.
    private static final int DEAD = 0;
    private static final int ACCEPT = 1;
    private static final int BAIL = 2;
    private static final int FIRST = 3;

    // An automaton has at most this many states (they are numbered in a byte), built from at most this many states of
    // the pattern, in at most this many steps (a step is one state of the pattern looked at while a set is made). The
    // steps bound what a pattern too large for an automaton costs before that is known, to about a millisecond.
    private static final int MAX_DFA_STATES = 256;
    private static final int MAX_NFA_STATES = 512;
    private static final long MAX_DFA_STEPS = 400_000;

    /** What {@link #isMatch} answers for a text that is not ASCII, which this automaton does not decide. */
    static final int UNDECIDED = -1;

    // The kinds of the pattern's states. SET reads one byte of its set and goes to out. SPLIT goes to out and to alt,
    // reading nothing. BOL and EOL go to out at the start and at the end of the text. WORD and NOT_WORD go to out
    // where a word boundary holds and where it does not.
    private static final int SET = 0;
    private static final int SPLIT = 1;
    private static final int BOL = 2;
    private static final int EOL = 3;
    private static final int WORD = 4;
    private static final int NOT_WORD = 5;
    private static final int MATCH = 6;

    /** The class of each byte: bytes the pattern does not tell apart share one. */
    private final byte[] classOf = new byte[256];
    /** {@code trans[state << shift | class]} is the state after a byte. */
    private byte[] trans = new byte[0];
    private int shift;
    private int start;
    /** For each state: the pattern matches if the text ends there. */
    private boolean[] atEnd = new boolean[0];
    /** The pattern matches the empty text. */
    private boolean empty;

    private Dfa() {}

    /** Whether the pattern matches the text: 1 or 0, or {@link #UNDECIDED} when the text is not ASCII. */
    int isMatch(byte[] text) {
        if (text.length == 0) {
            return empty ? 1 : 0;
        }
        int s = start;
        if (s == ACCEPT) {
            return 1;
        }
        byte[] trans = this.trans;
        byte[] classOf = this.classOf;
        int shift = this.shift;
        for (byte b : text) {
            // A byte beyond ASCII is in class 0.
            s = trans[s << shift | classOf[b & 0xFF]] & 0xFF;
            if (s < FIRST) {
                return s == BAIL ? UNDECIDED : s == ACCEPT ? 1 : 0;
            }
        }
        return atEnd[s] ? 1 : 0;
    }

    /** {@link #isMatch(byte[])} over UTF-16 text. */
    int isMatch(String text) {
        int n = text.length();
        if (n == 0) {
            return empty ? 1 : 0;
        }
        int s = start;
        if (s == ACCEPT) {
            return 1;
        }
        byte[] trans = this.trans;
        byte[] classOf = this.classOf;
        int shift = this.shift;
        for (int k = 0; k < n; k++) {
            // A unit beyond ASCII is in class 0, as every byte beyond ASCII is.
            s = trans[s << shift | classOf[Math.min(text.charAt(k), 0x80)]] & 0xFF;
            if (s < FIRST) {
                return s == BAIL ? UNDECIDED : s == ACCEPT ? 1 : 0;
            }
        }
        return atEnd[s] ? 1 : 0;
    }

    /** The states of the pattern, as parallel arrays. */
    private static final class Nfa {
        int[] kind = new int[16];
        long[] set0 = new long[16];
        long[] set1 = new long[16];
        int[] out = new int[16];
        int[] alt = new int[16];
        int size;
        /** The pattern has something an automaton over bytes cannot decide, or is too large. */
        boolean failed;

        int add(int k, long s0, long s1, int o, int a) {
            if (size >= MAX_NFA_STATES) {
                failed = true;
                return 0;
            }
            if (size == kind.length) {
                int n = size * 2;
                kind = Arrays.copyOf(kind, n);
                set0 = Arrays.copyOf(set0, n);
                set1 = Arrays.copyOf(set1, n);
                out = Arrays.copyOf(out, n);
                alt = Arrays.copyOf(alt, n);
            }
            kind[size] = k;
            set0[size] = s0;
            set1[size] = s1;
            out[size] = o;
            alt[size] = a;
            return size++;
        }

        boolean has(int i, int c) {
            return ((c < 64 ? set0[i] >>> c : set1[i] >>> (c - 64)) & 1) != 0;
        }

        /** Adds the states of a node that go on to {@code next}, and returns the state they start at. */
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
                    return add(SET, s0, s1, next, 0);
                }
                case SET:
                    return add(SET, n.set.ascii0, n.set.ascii1, next, 0);
                case CAT:
                    for (int k = n.subs.size() - 1; k >= 0; k--) {
                        next = compile(n.subs.get(k), next);
                    }
                    return next;
                case ALT: {
                    int last = n.subs.size() - 1;
                    int start = compile(n.subs.get(last), next);
                    for (int k = last - 1; k >= 0; k--) {
                        int out = compile(n.subs.get(k), next);
                        start = add(SPLIT, 0, 0, out, start);
                    }
                    return start;
                }
                case GROUP:
                    return compile(n.subs.get(0), next);
                case REPEAT: {
                    Node sub = n.subs.get(0);
                    if (n.max == Node.UNBOUNDED) {
                        // The loop, then the copies that must match before it.
                        int loop = add(SPLIT, 0, 0, 0, next);
                        if (failed) {
                            return 0;
                        }
                        // Not out[loop] = compile(...): the array is read before the call, which may grow it.
                        int body = compile(sub, loop);
                        out[loop] = body;
                        next = loop;
                    } else {
                        // The copies that may match, innermost last.
                        for (long i = n.min; i < n.max && !failed; i++) {
                            int out = compile(sub, next);
                            next = add(SPLIT, 0, 0, out, next);
                        }
                    }
                    for (int i = 0; i < n.min && !failed; i++) {
                        next = compile(sub, next);
                    }
                    return next;
                }
                case BOL:
                    return add(BOL, 0, 0, next, 0);
                case EOL:
                    return add(EOL, 0, 0, next, 0);
                case WORD_BOUNDARY:
                    if (!n.fold) {
                        return add(WORD, 0, 0, next, 0);
                    }
                    break;
                case NOT_WORD_BOUNDARY:
                    if (!n.fold) {
                        return add(NOT_WORD, 0, 0, next, 0);
                    }
                    break;
                default:
                    break;
            }
            // A word boundary that folds, or anything that needs the backtracking matcher.
            failed = true;
            return 0;
        }
    }

    /** Makes the automaton's states from sets of the pattern's states. */
    private static final class Builder {
        final Nfa nfa;
        // Scratch for closure: the states visited, by generation, and the stack.
        final int[] mark;
        int generation;
        IntList stack = new IntList(16);
        /** The steps left (see {@link #MAX_DFA_STEPS}). */
        long steps = MAX_DFA_STEPS;

        Builder(Nfa nfa) {
            this.nfa = nfa;
            this.mark = new int[nfa.size];
        }

        /**
         * Adds to {@code set} every state that reading nothing reaches from the states in {@code from}, keeping the
         * states that read a byte, the anchors and boundaries still to pass, and the match. A start anchor is passed
         * only {@code atStart}, and an end anchor only {@code atEnd}. A word boundary is kept to pass later when
         * {@code words} is false, and otherwise (whether the units before and after the position are word characters)
         * is passed or dropped.
         */
        void closure(IntList set, IntList from, boolean atStart, boolean atEnd, boolean words, boolean before,
                boolean after) {
            generation++;
            stack.clear();
            for (int k = 0; k < from.size(); k++) {
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
                        stack.add(nfa.out[i]);
                        stack.add(nfa.alt[i]);
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
                            set.add(i);
                        }
                        break;
                    case WORD:
                    case NOT_WORD:
                        if (!words) {
                            set.add(i);
                        } else if ((before != after) == (nfa.kind[i] == WORD)) {
                            stack.add(nfa.out[i]);
                        }
                        break;
                    default:
                        set.add(i);
                        break;
                }
            }
        }

        boolean hasMatch(IntList set) {
            for (int k = 0; k < set.size(); k++) {
                if (nfa.kind[set.get(k)] == MATCH) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * A state of the automaton as a map key: its states in increasing order, then whether the unit before it was a
     * word character and whether it is at the start of the text.
     */
    private static String key(IntList set, boolean word, boolean atStart) {
        int[] s = set.toArray();
        Arrays.sort(s);
        char[] key = new char[s.length + 1];
        for (int k = 0; k < s.length; k++) {
            key[k] = (char) s[k];
        }
        key[s.length] = (char) (0xFFFF - (word ? 2 : 0) - (atStart ? 1 : 0));
        return new String(key);
    }

    private static boolean isWord(int c) {
        return CharSet.isWordByte(c);
    }

    /** The states of the automaton being built, and what the builder needs to make more. */
    private static final class States {
        final Map<String, Integer> ids = new HashMap<>();
        final java.util.List<int[]> sets = new java.util.ArrayList<>();
        final java.util.List<boolean[]> bits = new java.util.ArrayList<>();
    }

    /** Builds the automaton of a pattern for an unanchored search, or {@code null} when it has none. */
    static Dfa build(Node root) {
        Nfa nfa = new Nfa();
        int match = nfa.add(MATCH, 0, 0, 0, 0);
        int startState = nfa.compile(root, match);
        if (nfa.failed) {
            return null;
        }
        Builder b = new Builder(nfa);

        // The classes of bytes: two bytes are in one class when every set of the pattern has both or neither, and both
        // or neither is a word character. Class 0 is the bytes that are not ASCII.
        Dfa d = new Dfa();
        int classes = 1;
        Map<String, Integer> signatures = new HashMap<>();
        StringBuilder signature = new StringBuilder();
        for (int c = 0; c < 128; c++) {
            signature.setLength(0);
            signature.append(isWord(c) ? '1' : '0');
            for (int s = 0; s < nfa.size; s++) {
                if (nfa.kind[s] == SET) {
                    signature.append(nfa.has(s, c) ? '1' : '0');
                }
            }
            Integer cls = signatures.get(signature.toString());
            if (cls == null) {
                cls = classes++;
                signatures.put(signature.toString(), cls);
            }
            d.classOf[c] = (byte) (int) cls;
        }
        while (1 << d.shift < classes) {
            d.shift++;
        }
        // One byte of each class, to move a set by.
        int[] sample = new int[129];
        for (int c = 127; c >= 0; c--) {
            sample[d.classOf[c]] = c;
        }

        // The search is unanchored: a match may start at any position, so after every byte the states the pattern
        // starts in (not at the start of the text) join the set.
        IntList restart = new IntList(8);
        IntList startList = IntList.of(new int[] {startState});
        b.closure(restart, startList, false, false, false, false, false);

        States states = new States();
        for (int k = 0; k < FIRST; k++) {
            states.sets.add(new int[0]);
            states.bits.add(new boolean[2]);
        }
        int stride = 1 << d.shift;
        byte[] trans = new byte[FIRST * stride];
        boolean[] atEnd = new boolean[FIRST];
        for (int cls = 0; cls < stride; cls++) {
            trans[ACCEPT * stride + cls] = ACCEPT;
            trans[BAIL * stride + cls] = BAIL;
        }
        d.trans = trans;
        d.atEnd = atEnd;
        IntList first = new IntList(8);
        b.closure(first, startList, true, false, false, false, false);
        int s0 = intern(b, d, states, stride, first, false, true);
        if (s0 < 0) {
            return null;
        }
        d.start = s0;
        d.empty = d.start == ACCEPT || d.atEnd[d.start];
        IntList resolved = new IntList(8);
        IntList moved = new IntList(8);
        IntList next = new IntList(8);
        for (int id = FIRST; id < states.sets.size(); id++) {
            IntList set = IntList.of(states.sets.get(id));
            boolean word = states.bits.get(id)[0];
            boolean atStart = states.bits.get(id)[1];
            for (int cls = 0; cls < stride; cls++) {
                if (cls == 0 || cls >= classes) {
                    // Not ASCII (or no such class).
                    d.trans[id * stride + cls] = BAIL;
                    continue;
                }
                int c = sample[cls];
                // The boundaries at this position are passed now that the unit after it is known.
                resolved.clear();
                b.closure(resolved, set, atStart, false, true, word, isWord(c));
                if (b.hasMatch(resolved)) {
                    d.trans[id * stride + cls] = ACCEPT;
                    continue;
                }
                moved.clear();
                for (int k = 0; k < resolved.size(); k++) {
                    int i = resolved.get(k);
                    if (nfa.kind[i] == SET && nfa.has(i, c)) {
                        moved.add(nfa.out[i]);
                    }
                }
                for (int k = 0; k < restart.size(); k++) {
                    moved.add(restart.get(k));
                }
                next.clear();
                b.closure(next, moved, false, false, false, false, false);
                int to = intern(b, d, states, stride, next, isWord(c), false);
                if (to < 0 || b.steps < 0) {
                    return null;
                }
                d.trans[id * stride + cls] = (byte) to;
            }
        }
        return d;
    }

    /** Gives a set its state; -1 when there are too many. */
    private static int intern(Builder b, Dfa d, States states, int stride, IntList set, boolean word,
            boolean atStart) {
        if (b.hasMatch(set)) {
            return ACCEPT;
        }
        if (set.size() == 0) {
            return DEAD;
        }
        String key = key(set, word, atStart);
        Integer known = states.ids.get(key);
        if (known != null) {
            return known;
        }
        if (states.sets.size() >= MAX_DFA_STATES) {
            return -1;
        }
        int id = states.sets.size();
        states.ids.put(key, id);
        states.sets.add(set.toArray());
        states.bits.add(new boolean[] {word, atStart});
        d.trans = Arrays.copyOf(d.trans, d.trans.length + stride);
        // At the end of the text the end anchors are passed, and a boundary is between a unit and none.
        IntList closed = new IntList(8);
        b.closure(closed, set, atStart, true, true, word, false);
        d.atEnd = Arrays.copyOf(d.atEnd, d.atEnd.length + 1);
        d.atEnd[id] = b.hasMatch(closed);
        return id;
    }
}
