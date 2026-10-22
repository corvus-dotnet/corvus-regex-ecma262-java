package io.github.corvusdotnet.regex.ecma262;

import java.util.ArrayList;
import java.util.List;

/** One element of the syntax tree of a pattern ({@code Node} of {@code parse.rs}). */
final class Node {
    enum Kind {
        EMPTY,
        CHAR,
        SET,
        CAT,
        ALT,
        GROUP,
        REPEAT,
        BOL,
        EOL,
        /** {@code LINE_START} and {@code LINE_END} are ^ and $ inside a group with the m modifier. */
        LINE_START,
        LINE_END,
        WORD_BOUNDARY,
        NOT_WORD_BOUNDARY,
        LOOK,
        BACKREF,
    }

    /** The max of a quantifier with no upper bound. */
    static final int UNBOUNDED = -1;

    Kind kind;
    /** The code point of a CHAR. */
    int r;
    /** The class of a SET. */
    CharSet set;
    /** The children of CAT and ALT, and the one child of GROUP, REPEAT and LOOK. */
    List<Node> subs = new ArrayList<>(0);
    /** {@code min}, {@code max} and {@code lazy} describe a REPEAT. */
    int min;
    int max;
    boolean lazy;
    /** The number of the capturing group of a GROUP, counted from 1. */
    int group;
    /** The groups a BACKREF names (a name can belong to several groups in separate alternatives). */
    IntList groups = new IntList(1);
    /** The group name of a named backreference until it is resolved. */
    IntList name = new IntList(1);
    /** Marks a BACKREF, WORD_BOUNDARY or NOT_WORD_BOUNDARY inside a case-insensitive group. */
    boolean fold;
    /** {@code behind} and {@code negative} describe a LOOK. */
    boolean behind;
    boolean negative;

    Node(Kind kind) {
        this.kind = kind;
    }

    static Node withSubs(Kind kind, List<Node> subs) {
        Node n = new Node(kind);
        n.subs = subs;
        return n;
    }

    static Node withSub(Kind kind, Node sub) {
        List<Node> subs = new ArrayList<>(1);
        subs.add(sub);
        return withSubs(kind, subs);
    }

    static Node ofChar(int r) {
        Node n = new Node(Kind.CHAR);
        n.r = r;
        return n;
    }

    static Node ofSet(CharSet set) {
        Node n = new Node(Kind.SET);
        n.set = set;
        return n;
    }

    /** A copy of the whole tree under the node (the clone of {@code parse.rs}'s Node). */
    Node deepCopy() {
        Node n = copy();
        for (int k = 0; k < n.subs.size(); k++) {
            n.subs.set(k, n.subs.get(k).deepCopy());
        }
        return n;
    }

    /** A shallow copy, with its own lists of children, groups and name. */
    Node copy() {
        Node n = new Node(kind);
        n.r = r;
        n.set = set;
        n.subs = new ArrayList<>(subs);
        n.min = min;
        n.max = max;
        n.lazy = lazy;
        n.group = group;
        n.groups = IntList.of(groups.toArray());
        n.name = IntList.of(name.toArray());
        n.fold = fold;
        n.behind = behind;
        n.negative = negative;
        return n;
    }
}
