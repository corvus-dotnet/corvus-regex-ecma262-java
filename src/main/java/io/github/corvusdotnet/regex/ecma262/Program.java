package io.github.corvusdotnet.regex.ecma262;

/** A pattern compiled for the backtracking matcher ({@code Program} of {@code compile.rs}). */
final class Program {
    /** The number of loops whose state a memo point can tell apart. */
    static final int MAX_MEMO_LOOPS = 2;

    Inst[] insts;
    /**
     * The number of groups the program records (every capturing group, or those some backreference names), and the
     * number of counted loops.
     */
    int groups;
    int loops;
    /** The pattern was read without the u flag, so the text is matched by UTF-16 code unit. */
    boolean units;
    /** Every match starts at the start of the text. */
    boolean anchored;
    /** The least number of bytes a match consumes (see setNeeds), and of UTF-16 code units. */
    int need;
    int need16;
    /**
     * The memo points of the program, {@code MAX_MEMO_LOOPS} ints each: the loops around the point (-1 for none) and
     * their least counts. A place where the matcher makes a choice and can remember that the choice has failed.
     */
    int[] pointLoops;
    int[] pointMins;
    int points;
    /**
     * For each instruction, which characters can come next on a way from it to the end of its match (see setQuick):
     * the ASCII characters as two words, and the flags {@link #Q_OTHER}, {@link #Q_MID} and {@link #Q_END}.
     */
    long[] quick0;
    long[] quick1;
    byte[] quickFlags;
    /** Every code point a match can start with; {@code null} when a match can be empty or the set is not known. */
    CharSet first;
    /** How the search finds where a match can start. */
    Search search = new Search();
    /**
     * The anchored automaton that finds where a match from a start ends, for a program that records no group and needs
     * no backtracking (see {@link Adfa}).
     */
    Adfa.Anchored anchoredDfa;
    /**
     * The tree of a pattern whose search tries every start its first set allows, from which the forward automaton (see
     * {@link Adfa#buildForward}) is built on the first search that wants it.
     */
    Node forwardRoot;
    private volatile Object forward;
    /**
     * For the same patterns, the window of positions read one unit at a time before the first set is searched for (see
     * {@link Adfa.Skip}).
     */
    Adfa.Skip skip;
    /**
     * For a pattern every match of which starts with one literal (a window at distance 0 of one literal over UTF-8),
     * the anchored automaton's states after it (see {@link Adfa.Anchored#after}) and its length: an occurrence goes on
     * from there, its units not read again.
     */
    int[] afterLiteral;
    int afterLiteralLength;

    private static final Object NO_FORWARD = new Object();

    /** The forward automaton, built on the first search that wants it; {@code null} when there is none. */
    Adfa.Anchored forward() {
        if (forwardRoot == null) {
            return null;
        }
        Object f = forward;
        if (f == null) {
            Adfa.Anchored built = Adfa.buildForward(forwardRoot);
            f = built == null ? NO_FORWARD : built;
            forward = f;
        }
        return f == NO_FORWARD ? null : (Adfa.Anchored) f;
    }

    /** The way can consume a character that is not ASCII. */
    static final int Q_OTHER = 1;
    /** A way reaches the end of its match consuming nothing more while text is left. */
    static final int Q_MID = 2;
    /** A way reaches the end of its match at the end of the text. */
    static final int Q_END = 4;
}
