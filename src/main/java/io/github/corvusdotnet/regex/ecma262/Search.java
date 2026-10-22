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

    /** The plan for a tree. */
    static Search plan(Node root, boolean backrefs, CharSet first, boolean units) {
        return new Search();
    }
}
