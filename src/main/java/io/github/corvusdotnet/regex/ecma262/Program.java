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

    /** The way can consume a character that is not ASCII. */
    static final int Q_OTHER = 1;
    /** A way reaches the end of its match consuming nothing more while text is left. */
    static final int Q_MID = 2;
    /** A way reaches the end of its match at the end of the text. */
    static final int Q_END = 4;
}
