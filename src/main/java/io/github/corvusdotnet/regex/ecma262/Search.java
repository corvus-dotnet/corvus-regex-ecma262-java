package io.github.corvusdotnet.regex.ecma262;

/**
 * How the search finds where a match can start ({@code search.rs}). Step 1 of the port has the plain scan alone; the
 * start searches (the literal windows, the searches after loops, the shapes answered by the search) come with the
 * search layer.
 */
final class Search {
    enum Plan {
        SCAN,
    }

    enum Shape {
        NONE,
    }

    Plan plan = Plan.SCAN;
    Shape shape = Shape.NONE;
    /** A match can only start right after a word boundary that opens the pattern. */
    boolean leadingWordBoundary;
    /** The leading loop's class, when a failed start means every start in the rest of its run fails too. */
    CharSet skipRun;

    /** The one literal searched for over UTF-8, when there is one with no other forms; {@code null} otherwise. */
    byte[] singleUtf8() {
        return null;
    }

    /** Whether the plan is the windows at distance 0: every match starts with one of the literals. */
    boolean windowsAtZero() {
        return false;
    }

    /**
     * Makes every pattern compiled on this thread try every position its first set allows, which the tests compare the
     * searches with.
     */
    static final ThreadLocal<boolean[]> NO_SEARCH = ThreadLocal.withInitial(() -> new boolean[1]);

    static boolean noSearch() {
        return NO_SEARCH.get()[0];
    }

    /** The plan for a tree. */
    static Search plan(Node root, boolean backrefs, CharSet first, boolean units) {
        if (noSearch()) {
            return new Search();
        }
        return new Search();
    }
}
