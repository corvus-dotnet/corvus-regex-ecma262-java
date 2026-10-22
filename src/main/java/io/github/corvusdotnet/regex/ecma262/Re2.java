package io.github.corvusdotnet.regex.ecma262;

/**
 * The size of the program a linear matcher compiles for a tree ({@code re2.rs}, which is {@code re2Cost} of
 * {@code re2.go}). The Go engine writes a tree with no lookaround and no backreference in the syntax of the standard
 * library's regexp package, and bounds the program that package is asked to compile. This port takes the same bound
 * for its automaton.
 */
final class Re2 {
    private Re2() {}

    /**
     * The largest program a linear matcher is asked to compile, in the instructions estimated for it before it is
     * written (see {@link #cost}). A counted repetition is written out, so {@code a{1000}} is a thousand instructions.
     */
    static final long MAX_COST = 10_000;

    /**
     * Estimates the number of instructions of the program a linear matcher compiles for a tree. A counted repetition is
     * written out: {@code x{n,m}} is m copies of x, and {@code x{n,}} is n copies and a loop. The estimate stops
     * counting above {@link #MAX_COST}.
     */
    static long cost(Node n) {
        switch (n.kind) {
            case EMPTY:
                return 0;
            case CAT:
            case ALT:
            case GROUP: {
                long cost = n.subs.size() - 1;
                for (Node sub : n.subs) {
                    cost += cost(sub);
                    if (cost > MAX_COST) {
                        return MAX_COST + 1;
                    }
                }
                return cost;
            }
            case REPEAT: {
                long copies = n.max == Node.UNBOUNDED ? n.min : n.max;
                if (copies < 1) {
                    copies = 1;
                }
                // Each copy has the body and one instruction for the choice to enter it.
                long body = cost(n.subs.get(0)) + 1;
                if (copies > MAX_COST || body * copies > MAX_COST) {
                    return MAX_COST + 1;
                }
                return body * copies;
            }
            default:
                return 1;
        }
    }
}
