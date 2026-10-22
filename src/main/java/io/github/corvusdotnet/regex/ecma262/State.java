package io.github.corvusdotnet.regex.ecma262;

import java.util.Arrays;

/**
 * The working memory of one match ({@code State} of {@code vm.rs}). Each thread keeps one and every match on the thread
 * uses it, so a match allocates only while the stack is still growing to the depth the pattern and the text need.
 */
final class State {
    /** The number of steps the matcher takes between two looks at the clock and at the depth of its stack. */
    static final long CHECK_EVERY = 1 << 12;
    /** The number of entries the backtracking stack of a match with a limit may hold. */
    static final int MAX_STACK = 1 << 20;
    /** The largest memory of failed places, in words of 64 bits: 8 MB. A match that would need more goes without it. */
    static final int MAX_MEMO_WORDS = 1 << 20;
    /** The number of steps for each position of the text that a match makes before it takes the memory into use. */
    static final int MEMO_STEPS_PER_POSITION = 64;
    /** The largest memory kept between matches, in words. */
    static final int MAX_RETAINED_MEMO = 1 << 16;
    /** The depth of the backtracking stack beyond which a memo point is not marked, in entries. */
    static final int MAX_MARK_DEPTH = MAX_STACK / 2;
    /** The largest stack kept between matches, in ints. */
    static final int MAX_RETAINED_STACK = 4 << 16;

    // Why a match was abandoned.
    static final int STOP_NONE = 0;
    static final int STOP_TIME = 1;
    static final int STOP_MEMORY = 2;

    // The kinds of entry on the backtracking stack. A choice entry says where to resume. An undo entry restores a value
    // that was overwritten after the entries below it were pushed. An entry is four ints: kind, pc, pos, a.

    /** Resumes at pc with the position pos. */
    static final int E_CHOICE = 0;
    /** A greedy single character loop that stopped at pos. Resuming gives back one character, down to a. */
    static final int E_REP = 1;
    /** A lazy single character loop that has taken a characters and stands at pos. Resuming takes one more. */
    static final int E_REP_LAZY = 2;
    /** The start of a positive (and a negative) lookaround: the position to restore, and for E_LOOK_NEG where to go. */
    static final int E_LOOK = 3;
    static final int E_LOOK_NEG = 4;
    /** Lies under the choices of a memo point. When it is reached, every way from the point has failed. */
    static final int E_MARK = 5;
    /** Restores the entry position of group pc to pos. */
    static final int E_TMP = 6;
    /** Restores the start and end of group pc to pos and a. */
    static final int E_CAP = 7;
    /** Restores the count and the iteration start of loop pc to pos and a. */
    static final int E_COUNTER = 8;

    /** The value of a group that has not taken part, and of the start of an iteration that has not started. */
    static final int NONE = -1;

    int[] stack = new int[256];
    int sp;
    /** The start and end of each tracked group, or NONE, and the position at which each was entered. */
    int[] caps = new int[0];
    int[] tmp = new int[0];
    /** For each loop, the number of iterations started and the position at which the last one started. */
    int[] counts = new int[0];
    /** Where the match that was found starts and ends. */
    int foundAt;
    int end;
    /** The time the match may take in nanoseconds, or 0 for none; the clock starts at the first check. */
    long limitNanos;
    long deadline;
    boolean deadlineSet;
    long steps;
    int stop;
    /** The memory of the places where a way has failed (see vm.rs). */
    long[] memo = new long[0];
    boolean memoOn;
    boolean memoOff;
    int width;
    int checks;
    int points;
    /** The state is in use by a match on this thread (a replacement callback can start another). */
    boolean busy;

    private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);

    /** The thread's state, or a new one when the thread is already inside a match. */
    static State acquire() {
        State st = STATE.get();
        if (st.busy) {
            return new State();
        }
        st.busy = true;
        return st;
    }

    void release() {
        if (stack.length > MAX_RETAINED_STACK) {
            stack = new int[256];
        }
        if (memo.length > MAX_RETAINED_MEMO) {
            memo = new long[0];
        }
        busy = false;
    }

    /** Readies the memory for a match of the program over a text of {@code len} units. */
    void reset(Program p, int len, long limit) {
        if (caps.length < 2 * p.groups) {
            caps = new int[2 * p.groups];
        }
        Arrays.fill(caps, 0, 2 * p.groups, NONE);
        if (tmp.length < p.groups) {
            tmp = new int[p.groups];
        }
        Arrays.fill(tmp, 0, p.groups, 0);
        if (counts.length < 2 * p.loops) {
            counts = new int[2 * p.loops];
        }
        Arrays.fill(counts, 0, 2 * p.loops, 0);
        limitNanos = limit;
        deadlineSet = false;
        steps = CHECK_EVERY;
        stop = STOP_NONE;
        memoOn = false;
        memoOff = p.points == 0;
        checks = 0;
        width = len + 1;
        points = p.points;
    }

    /** Takes the memory of failed places into use when the match has made enough steps to pay for it. */
    void remember() {
        long words = (((long) points * width << Program.MAX_MEMO_LOOPS) + 63) >> 6;
        if (words > MAX_MEMO_WORDS) {
            memoOff = true;
            return;
        }
        if ((long) checks * CHECK_EVERY < words + (long) MEMO_STEPS_PER_POSITION * width) {
            return;
        }
        if (memo.length < words) {
            memo = new long[(int) words];
        } else {
            Arrays.fill(memo, 0, (int) words, 0L);
        }
        memoOn = true;
    }

    /** Called every CHECK_EVERY steps. It reports whether the match may go on. */
    boolean check(int depth) {
        if (!memoOn && !memoOff) {
            checks++;
            remember();
        }
        if (limitNanos == 0) {
            return true;
        }
        if (depth > MAX_STACK) {
            stop = STOP_MEMORY;
            return false;
        }
        long now = System.nanoTime();
        if (!deadlineSet) {
            deadline = now + limitNanos;
            deadlineSet = true;
        } else if (now - deadline > 0) {
            stop = STOP_TIME;
            return false;
        }
        return true;
    }

    /** Applies the undo entry at {@code e}. */
    void undo(int e) {
        int k = stack[e + 1];
        switch (stack[e]) {
            case E_TMP:
                tmp[k] = stack[e + 2];
                break;
            case E_CAP:
                caps[2 * k] = stack[e + 2];
                caps[2 * k + 1] = stack[e + 3];
                break;
            case E_COUNTER:
                counts[2 * k] = stack[e + 2];
                counts[2 * k + 1] = stack[e + 3];
                break;
            default:
                break;
        }
    }

    /** Pushes an entry, growing the stack when it is full. */
    void push(int kind, int pc, int pos, int a) {
        if (sp + 4 > stack.length) {
            stack = Arrays.copyOf(stack, stack.length * 2);
        }
        int[] s = stack;
        s[sp] = kind;
        s[sp + 1] = pc;
        s[sp + 2] = pos;
        s[sp + 3] = a;
        sp += 4;
    }

    /**
     * The bit of the memory for the memo point m at a position, with the state of the loops around the point: for
     * each, whether it would refuse an iteration that ended here as empty.
     */
    int memoIndex(Program p, int m, int pos) {
        int base = (m - 1) * Program.MAX_MEMO_LOOPS;
        int idx = ((m - 1) * width + pos) << Program.MAX_MEMO_LOOPS;
        for (int k = 0; k < Program.MAX_MEMO_LOOPS; k++) {
            int l = p.pointLoops[base + k];
            if (l >= 0 && counts[2 * l] > p.pointMins[base + k] && counts[2 * l + 1] == pos) {
                idx |= 1 << k;
            }
        }
        return idx;
    }

    boolean memoBit(int idx) {
        return ((memo[idx >>> 6] >>> (idx & 63)) & 1) != 0;
    }

    void setMemoBit(int idx) {
        memo[idx >>> 6] |= 1L << (idx & 63);
    }
}
