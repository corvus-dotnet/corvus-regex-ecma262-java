package io.github.corvusdotnet.regex.ecma262;

/** One instruction of the backtracking matcher ({@code Inst} of {@code compile.rs}). */
final class Inst {
    // The operations. Char and Set consume one code point going forward; the Back forms consume the one before the
    // position, which is how the body of a lookbehind runs.
    static final int CHAR = 0;
    static final int CHAR_BACK = 1;
    static final int SET = 2;
    static final int SET_BACK = 3;
    /** Consumes a run of literal units going forward. */
    static final int LIT = 4;
    static final int FAIL = 5;
    static final int BOL = 6;
    static final int EOL = 7;
    static final int WORD_BOUNDARY = 8;
    static final int NOT_WORD_BOUNDARY = 9;
    /** ^ and $ with the m modifier. The Fold forms belong to a case-insensitive group. */
    static final int LINE_START = 10;
    static final int LINE_END = 11;
    static final int WORD_BOUNDARY_FOLD = 12;
    static final int NOT_WORD_BOUNDARY_FOLD = 13;
    static final int JMP = 14;
    /** Continues at x and leaves y as the alternative. */
    static final int SPLIT = 15;
    /** SaveTmp notes where a group was entered. Capture records the group once it has closed. */
    static final int SAVE_TMP = 16;
    static final int CAPTURE = 17;
    /** Resets the groups a to b, as ECMA-262 does at the start of each iteration of a quantified body. */
    static final int CLEAR = 18;
    static final int BACKREF = 19;
    static final int BACKREF_BACK = 20;
    static final int BACKREF_FOLD = 21;
    static final int BACKREF_FOLD_BACK = 22;
    /** Compare a group a code unit at a time, for a pattern matched by code unit. */
    static final int BACKREF_UNITS = 23;
    static final int BACKREF_UNITS_BACK = 24;
    /** Look opens a lookaround. LookOk ends the body of a positive one and LookFail the body of a negative one. */
    static final int LOOK = 25;
    static final int LOOK_OK = 26;
    static final int LOOK_FAIL = 27;
    /** Rep and RepLazy repeat one character or class without entering the general loop. */
    static final int REP = 28;
    static final int REP_LAZY = 29;
    /** LoopInit, Loop and LoopIter run a counted or possibly empty quantified body. */
    static final int LOOP_INIT = 30;
    static final int LOOP = 31;
    static final int LOOP_ITER = 32;
    static final int MATCH = 33;

    int op;
    /**
     * Marks a lazy Loop, a backward Capture, a negative Look, and a BackrefFold that folds case as the u flag grammar
     * does.
     */
    boolean flag;
    int r;
    CharSet set;
    /** The literal run of a Lit, in UTF-8 and in UTF-16. */
    byte[] lit = EMPTY_BYTES;
    char[] lit16 = EMPTY_CHARS;
    /** For a Rep, the ASCII bytes (up to three) what follows it can start with, when those are all it can. */
    byte[] back = EMPTY_BYTES;
    /** Jump targets. */
    int x;
    int y;
    /**
     * A group, a loop or a range of groups, depending on the instruction. For a backreference {@code b} is the major
     * number of the version of Unicode whose case folding it matches with.
     */
    int a;
    int b;
    /** Bounds of a repetition. A max of -1 is no bound. */
    int min;
    int max;
    /** The least number of bytes (and UTF-16 units) every way from a Loop to a match consumes (see setNeeds). */
    int need;
    int need16;
    /** The least number of bytes (and UTF-16 units) one iteration of the body of a Loop consumes. */
    int iter;
    int iter16;
    /** The number of the instruction's memo point, counted from 1, or 0 when it has none. */
    int m;

    static final byte[] EMPTY_BYTES = new byte[0];
    static final char[] EMPTY_CHARS = new char[0];

    Inst(int op) {
        this.op = op;
    }
}
