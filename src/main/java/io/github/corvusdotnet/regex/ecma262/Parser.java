package io.github.corvusdotnet.regex.ecma262;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The parser: a pattern to its syntax tree, in either grammar, with the limits of size ({@code parse.rs}, which is
 * {@code parse.go}). It reads by recursive descent. With {@code unicode} set it applies the grammar of the u flag;
 * without it, the Annex B grammar of patterns with no flag.
 */
final class Parser {
    /** Caps the bounds of a counted quantifier. No text that fits in memory reaches a larger count. */
    private static final long MAX_COUNT = Integer.MAX_VALUE;

    // The limits on the size of one pattern. A pattern may come from outside, so reading and compiling one must take
    // bounded time and memory whatever it holds.

    /** The longest pattern that is read, in bytes of UTF-8. */
    static final int MAX_PATTERN_LENGTH = 1 << 20;
    /** Caps the depth of groups, which bounds the recursion of the parser and of the compilers. */
    static final int MAX_NESTING = 1000;
    /**
     * The largest size a pattern may have, in the bytes the parser counts for it: {@code NODE_SIZE} for each node of
     * its syntax tree, and {@code RANGE_SIZE} for each range of a class as its members are read, for each range a case
     * insensitive group or a negated property adds, for each alternative that encloses a named group and for each
     * group that shares its name.
     */
    static final int MAX_SIZE = 1 << 20;

    private static final int NODE_SIZE = 32;
    private static final int RANGE_SIZE = 8;

    /** The grammar a pattern is read with and the flags that change what its atoms mean. */
    static final class Syntax {
        /** The grammar of the u flag. Without it, the Annex B grammar of a pattern with no u or v flag. */
        final boolean unicode;
        /** The grammar of the v flag inside a class, and the properties of strings. It comes with {@code unicode}. */
        final boolean sets;
        /** The i, m and s flags: the modifiers in force at the start of the pattern. */
        final boolean icase;
        final boolean multiline;
        final boolean dotAll;

        Syntax(boolean unicode, boolean sets, boolean icase, boolean multiline, boolean dotAll) {
            this.unicode = unicode;
            this.sets = sets;
            this.icase = icase;
            this.multiline = multiline;
            this.dotAll = dotAll;
        }
    }

    /** A capturing group with a name, and the alternatives that enclose it (pairs of disjunction and alternative). */
    private static final class NamedGroup {
        final int group;
        final IntList path;

        NamedGroup(int group, IntList path) {
            this.group = group;
            this.path = path;
        }
    }

    /** The pattern as code points with the u flag, and as UTF-16 code units without it. */
    private final int[] p;
    private int i;
    private final boolean unicode;
    /** The grammar of the v flag. */
    private final boolean sets;
    /** The sequences of the property of strings the last class escape named (the v flag), or {@code null}. */
    private List<int[]> escapeStrings;
    private int depth;
    /**
     * {@code groupCount} and {@code hasNames} come from a scan of the whole pattern before parsing, because the
     * meaning of {@code \1} and {@code \k} depends on groups that may follow them.
     */
    private long groupCount;
    private boolean hasNames;
    private int groups;
    private final Map<IntList, List<NamedGroup>> names = new HashMap<>();
    private boolean namedRefs;
    /** The alternatives that enclose the current position, innermost last, as pairs of disjunction and alternative. */
    private final IntList path = new IntList();
    private int disjunctions;
    /** The modifiers in force, which only a group such as {@code (?i:...)} changes. */
    private boolean icase;
    private boolean multiline;
    private boolean dotAll;
    /** Records that the pattern has a construct only the backtracking matcher can run. */
    boolean needsBacktracking;
    /** The size of what has been read so far (see {@link #MAX_SIZE}). */
    int size;

    private static final CharSet ANY_SET = CharSet.of(new int[] {0, CharSet.MAX_RUNE}, false);
    private static final CharSet DIGIT_SET = CharSet.of(CharSet.DIGIT_PAIRS, false);
    private static final CharSet NOT_DIGIT_SET = CharSet.of(CharSet.DIGIT_PAIRS, true);
    private static final CharSet WORD_SET = CharSet.of(CharSet.WORD_PAIRS, false);
    private static final CharSet NOT_WORD_SET = CharSet.of(CharSet.WORD_PAIRS, true);
    private static final CharSet SPACE_SET = CharSet.of(CharSet.SPACE_PAIRS, false);
    private static final CharSet NOT_SPACE_SET = CharSet.of(CharSet.SPACE_PAIRS, true);

    private Parser(int[] p, Syntax syntax) {
        this.p = p;
        this.unicode = syntax.unicode;
        this.sets = syntax.sets;
        this.icase = syntax.icase;
        this.multiline = syntax.multiline;
        this.dotAll = syntax.dotAll;
    }

    /** The result of reading a pattern: its syntax tree and the parser, which holds what it learnt. */
    static final class Parsed {
        final Node root;
        final Parser parser;

        Parsed(Node root, Parser parser) {
            this.root = root;
            this.parser = parser;
        }
    }

    /** The length of the pattern in bytes of UTF-8, a lone surrogate counting three as WTF-8 writes it. */
    static long utf8Length(String pattern) {
        long n = 0;
        for (int k = 0; k < pattern.length(); k++) {
            char c = pattern.charAt(k);
            if (c < 0x80) {
                n += 1;
            } else if (c < 0x800) {
                n += 2;
            } else if (Character.isHighSurrogate(c) && k + 1 < pattern.length()
                    && Character.isLowSurrogate(pattern.charAt(k + 1))) {
                n += 4;
                k++;
            } else {
                n += 3;
            }
        }
        return n;
    }

    /** Reads the pattern with no flag but the grammar's. */
    static Parsed parse(String pattern, boolean unicode) {
        return parseWith(pattern, new Syntax(unicode, false, false, false, false));
    }

    /**
     * Reads the pattern. It throws a {@link ParseFailure} for a pattern that is not valid under the chosen grammar,
     * and for a pattern over a limit of size, which it stops reading at the limit.
     */
    static Parsed parseWith(String pattern, Syntax syntax) {
        if (pattern.length() > MAX_PATTERN_LENGTH / 3 && utf8Length(pattern) > MAX_PATTERN_LENGTH) {
            throw ParseFailure.tooLarge(Limit.LENGTH, MAX_PATTERN_LENGTH);
        }
        // Without the u flag ECMA-262 reads a pattern, and matches a text, a code unit at a time. A character beyond
        // the Basic Multilingual Plane is then two atoms, a quantifier after it repeats the second, and in a class it
        // is two members, so a range between two such characters runs from a low surrogate to a high one and is an
        // error. With the u flag a lone surrogate is a code point of its own.
        int[] p = syntax.unicode ? pattern.codePoints().toArray() : pattern.chars().toArray();
        Parser ps = new Parser(p, syntax);
        ps.scanGroups();
        Node root = ps.disjunction();
        if (ps.i < ps.p.length) {
            if (ps.p[ps.i] == ')') {
                throw ps.fail("unmatched ')'");
            }
            throw ps.fail("unexpected character");
        }
        if (ps.namedRefs) {
            ps.resolve(root);
        }
        return new Parsed(root, ps);
    }

    private static boolean isDigit(int c) {
        return c >= '0' && c <= '9';
    }

    /**
     * Whether the first of two runs of decimal digits is the greater number. The runs are compared as written,
     * because a count has no limit of size in the grammar and {@code number} stops at {@code MAX_COUNT}.
     */
    private boolean greaterAsWritten(int aFrom, int aTo, int bFrom, int bTo) {
        while (aFrom < aTo && p[aFrom] == '0') {
            aFrom++;
        }
        while (bFrom < bTo && p[bFrom] == '0') {
            bFrom++;
        }
        if (aTo - aFrom != bTo - bFrom) {
            return aTo - aFrom > bTo - bFrom;
        }
        for (; aFrom < aTo; aFrom++, bFrom++) {
            if (p[aFrom] != p[bFrom]) {
                return p[aFrom] > p[bFrom];
            }
        }
        return false;
    }

    /** Whether two positions lie in different alternatives of some disjunction. */
    private static boolean separateAlternatives(IntList a, IntList b) {
        for (int k = 0; k + 1 < a.size() && k + 1 < b.size(); k += 2) {
            if (a.get(k) != b.get(k)) {
                break;
            }
            if (a.get(k + 1) != b.get(k + 1)) {
                return true;
            }
        }
        return false;
    }

    private static int hexValue(int c) {
        if (c >= 0x30 && c <= 0x39) {
            return c - 0x30;
        }
        if (c >= 0x61 && c <= 0x66) {
            return c - 0x61 + 10;
        }
        if (c >= 0x41 && c <= 0x46) {
            return c - 0x41 + 10;
        }
        return -1;
    }

    private static boolean isAsciiLetter(int c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    /** Whether {@code c} is one of the characters the u flag lets an identity escape name. */
    private static boolean syntaxCharacter(int c) {
        return c < 0x80 && "^$\\.*+?()[]{}|/".indexOf(c) >= 0;
    }

    private static boolean oneOf(int c, String chars) {
        return c >= 0 && c < 0x80 && chars.indexOf(c) >= 0;
    }

    private static final class IdTables {
        static final Table ID_START = Ucd.binaryTable("ID_Start");
        static final Table ID_CONTINUE = Ucd.binaryTable("ID_Continue");
    }

    private static void appendClassAtom(IntList pairs, int c, CharSet set) {
        if (set != null) {
            pairs.addAll(set.ranges);
        } else {
            pairs.add(c, c);
        }
    }

    /** The number of capturing groups of the pattern. */
    int groupCount() {
        return groups;
    }

    /** A name of groups, with the groups that have it. */
    static final class GroupName {
        final IntList name;
        final int[] groups;

        GroupName(IntList name, int[] groups) {
            this.name = name;
            this.groups = groups;
        }
    }

    /** The names of the groups, each with the groups that have it, in the order of their first group. */
    List<GroupName> groupNames() {
        List<GroupName> out = new ArrayList<>();
        for (Map.Entry<IntList, List<NamedGroup>> e : names.entrySet()) {
            int[] gs = new int[e.getValue().size()];
            for (int k = 0; k < gs.length; k++) {
                gs[k] = e.getValue().get(k).group;
            }
            out.add(new GroupName(e.getKey(), gs));
        }
        out.sort(Comparator.comparingInt(g -> g.groups[0]));
        return out;
    }

    /** Adds to the size of the pattern, and stops the parser when the size is over its limit. */
    private void charge(long bytes) {
        long s = size + bytes;
        if (s > MAX_SIZE) {
            throw ParseFailure.tooLarge(Limit.SIZE, MAX_SIZE);
        }
        size = (int) s;
    }

    private ParseFailure fail(String msg) {
        return ParseFailure.syntax(msg, i);
    }

    /** Gives every named backreference the groups of its name. A name no group has is an error. */
    private void resolve(Node n) {
        if (n.kind == Node.Kind.BACKREF && !n.name.isEmpty()) {
            List<NamedGroup> named = names.get(n.name);
            if (named == null) {
                throw fail("reference to an unknown group name");
            }
            for (NamedGroup g : named) {
                n.groups.add(g.group);
            }
        }
        for (Node sub : n.subs) {
            resolve(sub);
        }
    }

    /** Counts the capturing groups of the pattern and notes whether any is named. */
    private void scanGroups() {
        boolean inClass = false;
        int[] s = p;
        for (int k = 0; k < s.length; k++) {
            int c = s[k];
            if (c == '\\') {
                k++;
            } else if (c == '[') {
                inClass = true;
            } else if (c == ']') {
                inClass = false;
            } else if (c == '(' && !inClass) {
                if (k + 1 >= s.length || s[k + 1] != '?') {
                    groupCount++;
                } else if (k + 3 < s.length && s[k + 2] == '<' && s[k + 3] != '=' && s[k + 3] != '!') {
                    groupCount++;
                    hasNames = true;
                }
            }
        }
    }

    private boolean more() {
        return i < p.length;
    }

    private int peek() {
        return i < p.length ? p[i] : -1;
    }

    private boolean eat(char c) {
        if (i < p.length && p[i] == c) {
            i++;
            return true;
        }
        return false;
    }

    private Node disjunction() {
        depth++;
        if (depth > MAX_NESTING) {
            throw ParseFailure.tooLarge(Limit.NESTING, MAX_NESTING);
        }
        disjunctions++;
        path.add(disjunctions, 0);
        int last = path.size() - 2;
        Node first = alternative();
        if (peek() != '|') {
            path.truncate(last);
            depth--;
            return first;
        }
        Node alt = Node.withSub(Node.Kind.ALT, first);
        while (eat('|')) {
            charge(NODE_SIZE);
            path.set(last + 1, path.get(last + 1) + 1);
            alt.subs.add(alternative());
        }
        path.truncate(last);
        depth--;
        return alt;
    }

    private Node alternative() {
        List<Node> terms = new ArrayList<>();
        while (more() && peek() != '|' && peek() != ')') {
            terms.add(term());
        }
        switch (terms.size()) {
            case 0:
                return new Node(Node.Kind.EMPTY);
            case 1:
                return terms.get(0);
            default:
                return Node.withSubs(Node.Kind.CAT, terms);
        }
    }

    /**
     * The node for one literal character. Inside a case-insensitive group that is the class of the characters
     * equivalent to it.
     */
    private Node charNode(int c) {
        if (icase) {
            CharSet set = Fold.foldClosure(CharSet.of(new int[] {c, c}, false), unicode).set;
            if (set.ranges.length != 2 || set.ranges[0] != set.ranges[1]) {
                return Node.ofSet(set);
            }
        }
        return Node.ofChar(c);
    }

    /** The node for a class, which inside a case-insensitive group also takes every equivalent character. */
    private Node setNode(CharSet set) {
        return Node.ofSet(icase ? fold(set) : set);
    }

    /**
     * The set with every character equivalent to one of its members. It charges the pattern for the work and for the
     * set: a byte for each character with a case variant that the set holds, and its ranges.
     */
    private CharSet fold(CharSet set) {
        Fold.Closure closed = Fold.foldClosure(set, unicode);
        charge(closed.variants + (long) RANGE_SIZE * closed.set.ranges.length / 2);
        return closed.set;
    }

    /** The quantifier of a term: its bounds and whether it is lazy. */
    private int qMin;
    private int qMax;
    private boolean qLazy;
    /** Whether the atom just read may take a quantifier. */
    private boolean quantifiable;

    private Node term() {
        // The atom, and the repetition or the group around it.
        charge(2 * NODE_SIZE);
        int c = peek();
        quantifiable = true;
        Node atom;
        switch (c) {
            case '^':
                i++;
                quantifiable = false;
                if (multiline) {
                    needsBacktracking = true;
                    atom = new Node(Node.Kind.LINE_START);
                } else {
                    atom = new Node(Node.Kind.BOL);
                }
                break;
            case '$':
                i++;
                quantifiable = false;
                if (multiline) {
                    needsBacktracking = true;
                    atom = new Node(Node.Kind.LINE_END);
                } else {
                    atom = new Node(Node.Kind.EOL);
                }
                break;
            case '(':
                atom = group();
                break;
            case '.':
                i++;
                atom = Node.ofSet(dotAll ? ANY_SET : CharSet.DOT_SET);
                break;
            case '[':
                atom = classNode();
                break;
            case '\\':
                atom = atomEscape();
                break;
            case '*':
            case '+':
            case '?':
                throw fail("nothing to repeat");
            case '{':
                if (unicode || looksLikeQuantifier()) {
                    throw fail("nothing to repeat");
                }
                i++;
                atom = charNode('{');
                break;
            case ']':
            case '}':
                if (unicode) {
                    throw fail("lone bracket");
                }
                i++;
                atom = charNode(c);
                break;
            default:
                i++;
                atom = charNode(c);
                break;
        }
        boolean canRepeat = quantifiable;
        if (!quantifier()) {
            return atom;
        }
        if (!canRepeat) {
            throw fail("nothing to repeat");
        }
        Node repeat = Node.withSub(Node.Kind.REPEAT, atom);
        repeat.min = qMin;
        repeat.max = qMax;
        repeat.lazy = qLazy;
        return repeat;
    }

    /** Whether the text at the current {@code {} reads as {n}, {n,} or {n,m}. */
    private boolean looksLikeQuantifier() {
        int[] s = p;
        int k = i + 1;
        int start = k;
        while (k < s.length && isDigit(s[k])) {
            k++;
        }
        if (k == start || k >= s.length) {
            return false;
        }
        if (s[k] == '}') {
            return true;
        }
        if (s[k] != ',') {
            return false;
        }
        k++;
        while (k < s.length && isDigit(s[k])) {
            k++;
        }
        return k < s.length && s[k] == '}';
    }

    /** Reads a run of decimal digits, saturating at {@code MAX_COUNT}. */
    private long number() {
        int start = i;
        long v = 0;
        while (i < p.length && isDigit(p[i])) {
            if (v < MAX_COUNT) {
                v = v * 10 + (p[i] - '0');
                if (v > MAX_COUNT) {
                    v = MAX_COUNT;
                }
            }
            i++;
        }
        if (start == i) {
            throw fail("expected a number");
        }
        return v;
    }

    /** Reads a quantifier, when one is here, into its bounds and whether it is lazy. */
    private boolean quantifier() {
        int c = peek();
        int min;
        int max;
        if (c == '*') {
            i++;
            min = 0;
            max = Node.UNBOUNDED;
        } else if (c == '+') {
            i++;
            min = 1;
            max = Node.UNBOUNDED;
        } else if (c == '?') {
            i++;
            min = 0;
            max = 1;
        } else if (c == '{' && looksLikeQuantifier()) {
            i++;
            int first = i;
            int lo = (int) number();
            int hi = lo;
            int firstEnd = i;
            if (eat(',')) {
                if (peek() == '}') {
                    hi = Node.UNBOUNDED;
                } else {
                    int second = i;
                    hi = (int) number();
                    // Both counts stop at MAX_COUNT, so the numbers are compared as they are written.
                    if (greaterAsWritten(first, firstEnd, second, i)) {
                        throw fail("numbers out of order in quantifier");
                    }
                }
            }
            if (!eat('}')) {
                throw fail("malformed quantifier");
            }
            min = lo;
            max = hi;
        } else {
            return false;
        }
        qMin = min;
        qMax = max;
        qLazy = eat('?');
        return true;
    }

    /**
     * Reads a group or a lookaround, and sets whether a quantifier may follow. The body's terms set {@link
     * #quantifiable} too, so each way out sets it after the body is read.
     */
    private Node group() {
        i++;
        if (!eat('?')) {
            Node g = capture(null);
            quantifiable = true;
            return g;
        }
        if (eat(':')) {
            Node body = disjunction();
            close();
            quantifiable = true;
            return Node.withSub(Node.Kind.GROUP, body);
        }
        int c = peek();
        if (c == 'i' || c == 'm' || c == 's' || c == '-') {
            Node g = modifierGroup();
            quantifiable = true;
            return g;
        }
        if (eat('=') || eat('!')) {
            boolean negative = p[i - 1] == '!';
            Node body = disjunction();
            close();
            needsBacktracking = true;
            Node look = Node.withSub(Node.Kind.LOOK, body);
            look.negative = negative;
            // Annex B lets a quantifier follow a lookahead. The u flag does not.
            quantifiable = !unicode;
            return look;
        }
        if (eat('<')) {
            if (eat('=') || eat('!')) {
                boolean negative = p[i - 1] == '!';
                Node body = disjunction();
                close();
                needsBacktracking = true;
                Node look = Node.withSub(Node.Kind.LOOK, body);
                look.negative = negative;
                look.behind = true;
                quantifiable = false;
                return look;
            }
            IntList name = groupName();
            // Two groups may share a name only when they are in separate alternatives of one disjunction, so that no
            // match can go through both.
            List<NamedGroup> others = names.get(name);
            if (others != null) {
                for (NamedGroup other : others) {
                    if (!separateAlternatives(other.path, path)) {
                        throw fail("duplicate group name");
                    }
                }
            }
            Node g = capture(name);
            quantifiable = true;
            return g;
        }
        throw fail("invalid group");
    }

    /** Reads a group that turns modifiers on or off for its body, such as (?i:...) or (?s-i:...), after its (?. */
    private Node modifierGroup() {
        boolean savedIcase = icase;
        boolean savedMultiline = multiline;
        boolean savedDotAll = dotAll;
        boolean[] seen = new boolean[3];
        boolean removing = false;
        int count = 0;
        while (!eat(':')) {
            int c = peek();
            i++;
            int flag;
            if (c == 'i') {
                flag = 0;
                icase = !removing;
            } else if (c == 'm') {
                flag = 1;
                multiline = !removing;
            } else if (c == 's') {
                flag = 2;
                dotAll = !removing;
            } else if (c == '-') {
                if (removing) {
                    throw fail("invalid group modifier");
                }
                removing = true;
                continue;
            } else {
                throw fail("invalid group modifier");
            }
            if (seen[flag]) {
                throw fail("repeated group modifier");
            }
            seen[flag] = true;
            count++;
        }
        if (count == 0) {
            throw fail("invalid group modifier");
        }
        Node body = disjunction();
        close();
        icase = savedIcase;
        multiline = savedMultiline;
        dotAll = savedDotAll;
        return Node.withSub(Node.Kind.GROUP, body);
    }

    private Node capture(IntList name) {
        groups++;
        int group = groups;
        if (name != null) {
            // The path is kept, and every group of the same name is compared with it.
            List<NamedGroup> same = names.get(name);
            charge((long) RANGE_SIZE * (path.size() / 2 + (same == null ? 0 : same.size())));
            names.computeIfAbsent(name, k -> new ArrayList<>()).add(new NamedGroup(group, IntList.of(path.toArray())));
        }
        Node body = disjunction();
        close();
        Node g = Node.withSub(Node.Kind.GROUP, body);
        g.group = group;
        return g;
    }

    private void close() {
        if (!eat(')')) {
            throw fail("unterminated group");
        }
    }

    /** Reads a group name up to and including its {@code >}. A character of the name may be a {@code \\u} escape. */
    private IntList groupName() {
        IntList name = new IntList();
        while (!eat('>')) {
            if (!more()) {
                throw fail("invalid group name");
            }
            int c = peek();
            i++;
            if (c == '\\') {
                if (!eat('u')) {
                    throw fail("invalid group name");
                }
                c = unicodeEscape(true);
                if (c < 0) {
                    throw fail("invalid group name");
                }
            } else if (c >= 0xD800 && c <= 0xDBFF && peek() >= 0xDC00 && peek() <= 0xDFFF) {
                // Without the u flag the pattern is code units, and a name still takes a surrogate pair as one
                // character.
                c = 0x10000 + ((c - 0xD800) << 10) + (peek() - 0xDC00);
                i++;
            }
            if (name.isEmpty()) {
                if (!(c == '$' || c == '_' || IdTables.ID_START.contains(c))) {
                    throw fail("invalid group name");
                }
            } else if (!(c == '$' || c == 0x200C || c == 0x200D || IdTables.ID_CONTINUE.contains(c))) {
                throw fail("invalid group name");
            }
            name.add(c);
        }
        if (name.isEmpty()) {
            throw fail("invalid group name");
        }
        return name;
    }

    /** Reads an escape outside a class, and sets whether a quantifier may follow. */
    private Node atomEscape() {
        i++;
        quantifiable = true;
        if (!more()) {
            throw fail("\\ at end of pattern");
        }
        int c = peek();
        if (c == 'b' || c == 'B') {
            i++;
            Node boundary = new Node(c == 'B' ? Node.Kind.NOT_WORD_BOUNDARY : Node.Kind.WORD_BOUNDARY);
            // With the u flag, a case-insensitive word boundary counts two more characters as word characters.
            if (unicode && icase) {
                boundary.fold = true;
                needsBacktracking = true;
            }
            quantifiable = false;
            return boundary;
        }
        if (c == 'k') {
            // Annex B reads \k as the letter k in a pattern with no named group.
            if (unicode || hasNames) {
                i++;
                if (!eat('<')) {
                    throw fail("invalid named reference");
                }
                IntList name = groupName();
                namedRefs = true;
                needsBacktracking = true;
                Node ref = new Node(Node.Kind.BACKREF);
                ref.name = name;
                ref.fold = icase;
                return ref;
            }
            i++;
            return charNode('k');
        }
        if (c >= '1' && c <= '9') {
            int start = i;
            long n = number();
            if (n <= groupCount) {
                needsBacktracking = true;
                Node ref = new Node(Node.Kind.BACKREF);
                ref.groups.add((int) n);
                ref.fold = icase;
                return ref;
            }
            if (unicode) {
                throw fail("reference to a group that does not exist");
            }
            // Annex B reads it as an octal escape, or as the digit itself.
            i = start;
            return charNode(legacyOctal());
        }
        CharSet set = classEscape(c);
        if (set != null) {
            if (escapeStrings != null) {
                // A property of strings (the v flag): the class of its code points and its sequences.
                ClassSetValue value = stringsValue(foldIf(set), escapeStrings);
                return classNodeOf(value, false);
            }
            return setNode(set);
        }
        return charNode(characterEscape(false));
    }

    /**
     * Reads an Annex B octal escape of up to three digits with a value below 256. A digit that cannot start one (8 or
     * 9) stands for itself.
     */
    private int legacyOctal() {
        int v = 0;
        int k = 0;
        while (k < 3 && more() && peek() >= '0' && peek() <= '7' && v * 8 + (peek() - '0') <= 0377) {
            v = v * 8 + (peek() - '0');
            i++;
            k++;
        }
        if (k == 0) {
            int d = peek();
            i++;
            return d;
        }
        return v;
    }

    /**
     * Reads a class escape such as {@code \d} or {@code \p{...}} whose letter is {@code c}. It returns {@code null},
     * consuming nothing, when {@code c} does not start one.
     */
    private CharSet classEscape(int c) {
        escapeStrings = null;
        CharSet set;
        switch (c) {
            case 'd':
                set = DIGIT_SET;
                break;
            case 'D':
                set = NOT_DIGIT_SET;
                break;
            case 'w':
                set = unicode && icase ? Fold.FOLD_WORD_SET : WORD_SET;
                break;
            case 'W':
                set = unicode && icase ? Fold.NOT_FOLD_WORD_SET : NOT_WORD_SET;
                break;
            case 's':
                set = SPACE_SET;
                break;
            case 'S':
                set = NOT_SPACE_SET;
                break;
            case 'p':
            case 'P': {
                // Without the u flag \p is the letter p.
                if (!unicode) {
                    return null;
                }
                i++;
                if (!eat('{')) {
                    throw fail("invalid property name");
                }
                int start = i;
                while (more() && peek() != '}') {
                    i++;
                }
                // A name ECMA-262 defines is ASCII. Any other character makes a name no table has.
                StringBuilder sb = new StringBuilder();
                for (int k = start; k < i; k++) {
                    int r = p[k];
                    sb.appendCodePoint(r >= 0 && r <= 0x10FFFF ? r : 0xFFFD);
                }
                String expr = sb.toString();
                if (!eat('}')) {
                    throw fail("invalid property name");
                }
                CharSet property = CharSet.propertySet(expr);
                if (property == null) {
                    CharSet.StringProperty strings = sets && c == 'p' ? CharSet.stringProperty(expr) : null;
                    if (strings != null) {
                        // A property of strings, which the v flag alone names, and which has no complement.
                        charge((long) RANGE_SIZE * (strings.set.ranges.length / 2 + strings.strings.size()));
                        escapeStrings = strings.strings;
                        return strings.set;
                    }
                    throw fail("invalid property name");
                }
                if (c == 'P') {
                    charge((long) RANGE_SIZE * property.ranges.length / 2);
                    // With the v flag the complement is of the case-folded set; with the u flag the fold comes after
                    // it.
                    CharSet base = sets ? foldIf(property) : property;
                    return CharSet.of(base.ranges, true);
                }
                return property;
            }
            default:
                return null;
        }
        i++;
        return set;
    }

    /** Reads exactly {@code n} hexadecimal digits. It returns -1, consuming nothing, when they are not there. */
    private int hex(int n) {
        if (i + n > p.length) {
            return -1;
        }
        int v = 0;
        for (int k = 0; k < n; k++) {
            int d = hexValue(p[i + k]);
            if (d < 0) {
                return -1;
            }
            v = v * 16 + d;
        }
        i += n;
        return v;
    }

    /** Reads the escape after a backslash, in or out of a class, and returns the code point it names. */
    private int characterEscape(boolean inClass) {
        int c = peek();
        i++;
        switch (c) {
            case 'f':
                return 0x0C;
            case 'n':
                return 0x0A;
            case 'r':
                return 0x0D;
            case 't':
                return 0x09;
            case 'v':
                return 0x0B;
            case 'c': {
                int l = peek();
                if (isAsciiLetter(l)) {
                    i++;
                    return l % 32;
                }
                if (!unicode && inClass && (isDigit(l) || l == '_')) {
                    i++;
                    return l % 32;
                }
                if (unicode) {
                    throw fail("invalid control escape");
                }
                // Annex B reads a \c that is not a control escape as a backslash followed by the letter c.
                i--;
                return '\\';
            }
            case '0':
                if (more() && isDigit(peek())) {
                    if (unicode) {
                        throw fail("invalid decimal escape");
                    }
                    i--;
                    return legacyOctal();
                }
                return 0;
            case 'x': {
                int h = hex(2);
                if (h < 0) {
                    if (unicode) {
                        throw fail("invalid \\x escape");
                    }
                    return 'x';
                }
                return h;
            }
            case 'u': {
                int u = unicodeEscape(unicode);
                if (u < 0) {
                    if (unicode) {
                        throw fail("invalid \\u escape");
                    }
                    return 'u';
                }
                return u;
            }
            default:
                break;
        }
        if (c >= '1' && c <= '9' && !unicode && inClass) {
            i--;
            return legacyOctal();
        }
        // Identity escapes. The u flag allows only the syntax characters and '/', and '-' in a class. Annex B allows
        // any character, except k in a pattern with a named group.
        if (unicode && !syntaxCharacter(c) && !(inClass && c == '-')) {
            throw fail("invalid escape");
        }
        if (c == 'k' && hasNames) {
            throw fail("invalid escape");
        }
        return c;
    }

    /**
     * Reads what follows a {@code \\u} and returns the code point, or -1, consuming nothing, when it is not a
     * well-formed escape. With {@code unicodeMode} set it accepts the {@code \\u{...}} form and reads a surrogate pair
     * written as two escapes as one code point.
     */
    private int unicodeEscape(boolean unicodeMode) {
        int start = i;
        if (unicodeMode && eat('{')) {
            int digits = i;
            int v = 0;
            while (more() && hexValue(peek()) >= 0) {
                if (v <= CharSet.MAX_RUNE) {
                    v = v * 16 + hexValue(peek());
                }
                i++;
            }
            if (digits == i || !eat('}') || v > CharSet.MAX_RUNE) {
                i = start;
                return -1;
            }
            return v;
        }
        int u = hex(4);
        if (u < 0) {
            return -1;
        }
        if (unicodeMode && u >= 0xD800 && u <= 0xDBFF && i + 6 <= p.length && p[i] == '\\' && p[i + 1] == 'u') {
            int save = i;
            i += 2;
            int low = hex(4);
            if (low >= 0xDC00 && low <= 0xDFFF) {
                return 0x10000 + ((u - 0xD800) << 10) + (low - 0xDC00);
            }
            i = save;
        }
        return u;
    }

    /** Reads a character class from its [ to its ]. */
    private Node classNode() {
        if (sets) {
            return classSetExpression();
        }
        i++;
        boolean negated = eat('^');
        IntList pairs = new IntList();
        // The ranges read so far that the size of the pattern has been charged for.
        int charged = 0;
        while (true) {
            if (!more()) {
                throw fail("unterminated character class");
            }
            if (eat(']')) {
                break;
            }
            charge((long) RANGE_SIZE * (pairs.size() / 2 - charged));
            charged = pairs.size() / 2;
            int first = classAtom();
            CharSet firstSet = atomSet;
            if (peek() == '-' && i + 1 < p.length && p[i + 1] != ']') {
                i++;
                int second = classAtom();
                CharSet secondSet = atomSet;
                if (firstSet != null || secondSet != null) {
                    if (unicode) {
                        throw fail("invalid character class range");
                    }
                    // Annex B reads the '-' as itself when a class escape is at either end.
                    appendClassAtom(pairs, first, firstSet);
                    pairs.add('-', '-');
                    appendClassAtom(pairs, second, secondSet);
                    continue;
                }
                if (second < first) {
                    throw fail("range out of order in character class");
                }
                pairs.add(first, second);
                continue;
            }
            appendClassAtom(pairs, first, firstSet);
        }
        // Inside a case-insensitive group a character matches the class when it is equivalent to a member, and a
        // negated class excludes exactly those characters.
        charge((long) RANGE_SIZE * (pairs.size() / 2 - charged));
        CharSet set = CharSet.of(pairs, false);
        if (icase) {
            set = fold(set);
        }
        if (negated) {
            set = CharSet.of(set.ranges, true);
        }
        return Node.ofSet(set);
    }

    /** The set of the class atom just read, or {@code null} when it was a code point. */
    private CharSet atomSet;

    /** Reads one member of a class: a code point, or a set for a class escape (in {@link #atomSet}). */
    private int classAtom() {
        atomSet = null;
        int c = peek();
        i++;
        if (c != '\\') {
            return c;
        }
        if (!more()) {
            throw fail("\\ at end of pattern");
        }
        int e = peek();
        if (e == 'b') {
            i++;
            return 0x08;
        }
        CharSet set = classEscape(e);
        if (set != null) {
            atomSet = set;
            return 0;
        }
        return characterEscape(true);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The v flag. Its grammar changes a class and nothing outside it. A class is read as a value of two parts, its
    // code points and its strings (each of a length other than one, the empty string among them), with a flag for
    // whether its syntax may hold a string, which ECMA-262's MayContainStrings decides from the syntax and not from
    // what the strings turn out to be. A string of one character is a character of the class. The union, the
    // intersection (&&) and the difference (--) act on both parts, strings compared whole. With the i flag each
    // operand is case folded as it is read, so that the complement of a class, and of \P{...}, is of the folded set
    // (with the u flag the fold comes after the complement). A class with strings is a group of an alternation: the
    // strings longest first, each a sequence of characters, then the set.

    /** The set with every equivalent character inside a case-insensitive group, and the set itself outside one. */
    private CharSet foldIf(CharSet set) {
        return icase ? fold(set) : set;
    }

    /** Reads a class of the v flag's grammar from its [ to its ]. */
    private Node classSetExpression() {
        i++;
        boolean negated = eat('^');
        int start = i;
        ClassSetValue value = classSetContents();
        if (!eat(']')) {
            throw fail("unterminated character class");
        }
        if (negated && value.mayContainStrings) {
            i = start;
            throw fail("negated character class may contain strings");
        }
        return classNodeOf(value, negated);
    }

    /** Reads the contents of a class of the v flag's grammar, up to its ]. */
    private ClassSetValue classSetContents() {
        if (peek() == ']') {
            return ClassSetValue.empty();
        }
        ClassSetValue value = classSetElement();
        boolean range = elementRange;
        int op = setOperator();
        if (op != 0) {
            // An intersection or a difference: operands only, with the one operator between every two.
            if (range) {
                throw fail("invalid set operation in character class");
            }
            while (setOperator() == op) {
                i += 2;
                if (peek() == ']' || !more()) {
                    throw fail("invalid set operation in character class");
                }
                ClassSetValue operand = classSetElement();
                if (elementRange) {
                    throw fail("invalid set operation in character class");
                }
                charge((long) RANGE_SIZE * (value.set.ranges.length + operand.set.ranges.length) / 2);
                value = op == '&' ? value.intersect(operand) : value.subtract(operand);
            }
            if (peek() != ']') {
                throw fail("invalid set operation in character class");
            }
            return value;
        }
        // A union.
        while (more() && peek() != ']') {
            if (setOperator() != 0) {
                throw fail("invalid set operation in character class");
            }
            ClassSetValue operand = classSetElement();
            charge((long) RANGE_SIZE * (value.set.ranges.length + operand.set.ranges.length) / 2);
            value = value.union(operand);
        }
        return value;
    }

    /** The set operator at the position ({@code &} for {@code &&}, {@code -} for {@code --}), or 0. */
    private int setOperator() {
        if (i + 1 < p.length && p[i] == p[i + 1] && (p[i] == '&' || p[i] == '-')) {
            return p[i];
        }
        return 0;
    }

    /** Whether the element just read by {@link #classSetElement} was a range of two characters. */
    private boolean elementRange;

    /**
     * Reads one element of a class of the v flag's grammar: an operand, or a range (noted in {@link #elementRange}).
     */
    private ClassSetValue classSetElement() {
        charge(NODE_SIZE);
        elementRange = false;
        int c = peek();
        if (c == '[') {
            // A nested class: its strings count as strings whether or not there are any.
            int start = i;
            depth++;
            if (depth > MAX_NESTING) {
                throw ParseFailure.tooLarge(Limit.NESTING, MAX_NESTING);
            }
            i++;
            boolean negated = eat('^');
            ClassSetValue inner = classSetContents();
            if (!eat(']')) {
                throw fail("unterminated character class");
            }
            depth--;
            elementRange = false;
            if (negated) {
                if (inner.mayContainStrings) {
                    i = start;
                    throw fail("negated character class may contain strings");
                }
                return ClassSetValue.of(inner.set.complement());
            }
            return inner;
        }
        if (c == '\\') {
            if (i + 1 >= p.length) {
                throw fail("\\ at end of pattern");
            }
            int e = p[i + 1];
            if (e == 'q') {
                i += 2;
                return classStringDisjunction();
            }
            i++;
            CharSet set = classEscape(e);
            if (set != null) {
                if (escapeStrings != null) {
                    return stringsValue(foldIf(set), escapeStrings);
                }
                // A class escape is folded as one operand; \P is already the complement of a folded set.
                return ClassSetValue.of(e == 'P' ? set : foldIf(set));
            }
            i--;
        }
        int first = classSetCharacter();
        if (peek() == '-' && setOperator() == 0) {
            int dash = i;
            i++;
            int next = peek();
            boolean escapeClass = next == '\\' && i + 1 < p.length && oneOf(p[i + 1], "qdDwWsSpP");
            if (next == ']' || next == '[' || escapeClass) {
                i = dash;
                throw fail("invalid character class range");
            }
            int second = classSetCharacter();
            if (second < first) {
                i = dash;
                throw fail("range out of order in character class");
            }
            ClassSetValue value = ClassSetValue.of(foldIf(CharSet.of(new int[] {first, second}, false)));
            elementRange = true;
            return value;
        }
        return ClassSetValue.of(foldIf(CharSet.of(new int[] {first, first}, false)));
    }

    /**
     * Reads one character of a class of the v flag's grammar: a character that is not a syntax character and does not
     * begin a reserved double punctuator, or an escape, which may be of a reserved punctuator.
     */
    private int classSetCharacter() {
        if (!more()) {
            throw fail("unterminated character class");
        }
        int c = p[i];
        if (c == '\\') {
            if (i + 1 >= p.length) {
                throw fail("\\ at end of pattern");
            }
            int e = p[i + 1];
            if (e == 'b') {
                i += 2;
                return 0x08;
            }
            if (oneOf(e, "&-!#%,:;<=>@`~")) {
                i += 2;
                return e;
            }
            i++;
            return characterEscape(true);
        }
        if (oneOf(c, "()[]{}/-\\|")) {
            throw fail("invalid character in character class");
        }
        if (i + 1 < p.length && p[i + 1] == c && oneOf(c, "&!#$%*+,.:;<=>?@^`~")) {
            throw fail("invalid set operation in character class");
        }
        i++;
        return c;
    }

    /** Reads the strings of \q{...} after its \q. A string of one character is a character of the class. */
    private ClassSetValue classStringDisjunction() {
        if (!eat('{')) {
            throw fail("invalid escape");
        }
        IntList current = new IntList();
        ClassSetValue value = ClassSetValue.empty();
        while (true) {
            if (!more()) {
                throw fail("unterminated character class");
            }
            int c = peek();
            if (c == '|' || c == '}') {
                i++;
                if (current.size() == 1) {
                    CharSet set = foldIf(CharSet.of(new int[] {current.get(0), current.get(0)}, false));
                    value.set = value.set.union(set);
                } else {
                    value.mayContainStrings = true;
                    charge((long) NODE_SIZE * Math.max(current.size(), 1));
                    IntList s = canonicalString(current.toArray());
                    if (!value.strings.contains(s)) {
                        value.strings.add(s);
                    }
                }
                current.clear();
                if (c == '}') {
                    break;
                }
                continue;
            }
            current.add(classSetCharacter());
        }
        return value;
    }

    /**
     * The value of a property of strings: its code points, and its sequences (case folded in a case-insensitive group).
     */
    private ClassSetValue stringsValue(CharSet set, List<int[]> strings) {
        List<IntList> canonical = new ArrayList<>(strings.size());
        for (int[] s : strings) {
            canonical.add(canonicalString(s));
        }
        ClassSetValue value = new ClassSetValue(set, canonical, true);
        return value;
    }

    /** A string as its code points, each canonicalized in a case-insensitive group. */
    private IntList canonicalString(int[] codePoints) {
        IntList out = new IntList(codePoints.length);
        for (int c : codePoints) {
            out.add(icase ? Fold.canonicalize(c, true) : c);
        }
        return out;
    }

    /**
     * The node of a class of the v flag's grammar: its set, or, where it holds strings, the alternatives of its strings
     * longest first and then its set, as ECMA-262 orders them, in a group.
     */
    private Node classNodeOf(ClassSetValue value, boolean negated) {
        if (negated) {
            return Node.ofSet(value.set.complement());
        }
        if (value.strings.isEmpty()) {
            return Node.ofSet(value.set);
        }
        List<IntList> ordered = new ArrayList<>(value.strings);
        // Stable, as Rust's sort_by_key is: strings of one length keep their order.
        ordered.sort(Comparator.comparingInt(IntList::size).reversed());
        List<Node> alternatives = new ArrayList<>(ordered.size() + 1);
        for (IntList s : ordered) {
            charge((long) NODE_SIZE * (s.size() + 1));
            if (s.isEmpty()) {
                alternatives.add(new Node(Node.Kind.EMPTY));
            } else {
                List<Node> chars = new ArrayList<>(s.size());
                for (int k = 0; k < s.size(); k++) {
                    chars.add(charNode(s.get(k)));
                }
                alternatives.add(Node.withSubs(Node.Kind.CAT, chars));
            }
        }
        if (value.set.ranges.length != 0) {
            alternatives.add(Node.ofSet(value.set));
        }
        return Node.withSub(Node.Kind.GROUP, Node.withSubs(Node.Kind.ALT, alternatives));
    }

    /**
     * The value of a class of the v flag's grammar: its code points, its strings (each of a length other than one, the
     * empty string among them), and whether its syntax may hold a string.
     */
    private static final class ClassSetValue {
        CharSet set;
        final List<IntList> strings;
        boolean mayContainStrings;

        ClassSetValue(CharSet set, List<IntList> strings, boolean mayContainStrings) {
            this.set = set;
            this.strings = strings;
            this.mayContainStrings = mayContainStrings;
        }

        static ClassSetValue empty() {
            return new ClassSetValue(CharSet.of(new int[0], false), new ArrayList<>(), false);
        }

        static ClassSetValue of(CharSet set) {
            return new ClassSetValue(set, new ArrayList<>(), false);
        }

        ClassSetValue union(ClassSetValue b) {
            List<IntList> all = new ArrayList<>(strings);
            for (IntList s : b.strings) {
                if (!all.contains(s)) {
                    all.add(s);
                }
            }
            return new ClassSetValue(set.union(b.set), all, mayContainStrings || b.mayContainStrings);
        }

        ClassSetValue intersect(ClassSetValue b) {
            List<IntList> kept = new ArrayList<>();
            for (IntList s : strings) {
                if (b.strings.contains(s)) {
                    kept.add(s);
                }
            }
            return new ClassSetValue(set.intersect(b.set), kept, mayContainStrings && b.mayContainStrings);
        }

        ClassSetValue subtract(ClassSetValue b) {
            List<IntList> kept = new ArrayList<>();
            for (IntList s : strings) {
                if (!b.strings.contains(s)) {
                    kept.add(s);
                }
            }
            return new ClassSetValue(set.subtract(b.set), kept, mayContainStrings);
        }
    }

    /** The UTF-8 bytes of a name of code points, for the public API. */
    static byte[] nameBytes(IntList name) {
        return new String(name.toArray(), 0, name.size()).getBytes(StandardCharsets.UTF_8);
    }
}
