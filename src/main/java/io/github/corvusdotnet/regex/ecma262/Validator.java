package io.github.corvusdotnet.regex.ecma262;

import java.util.Arrays;

/**
 * Whether a pattern is valid in the grammar, decided as the parser decides it but without its limits of size, and with
 * nothing allocated for any pattern of ordinary size: no tree, no sets, no string (the design of the .NET engine's
 * EcmaRegexValidator, and of {@code validate.rs} of the Rust crate, which is {@code validate.go}).
 *
 * <p>It reads the pattern where it lies, decoding a character only where the grammar needs it. It keeps the groups and
 * the nested classes of the v flag on stacks of its own rather than recursing, so that no pattern can overflow the
 * stack, and it keeps a group name as its place in the pattern and a named group as the disjunction and the alternative it
 * lies in, never a copy of the path to it, so its memory is in proportion to the pattern however deeply named groups nest.
 * Each thread has one validator, whose stacks grow to
 * what the patterns it reads need and are kept for the next. It asks the grammar only: the parser's limits of size (the
 * length of the pattern, the nesting of groups, the size it counts) belong to compiling a pattern, and none of them
 * applies here.
 *
 * <p>Each method follows the method of the parser with the same name, and makes the same decision at each step. A
 * position is an index of a UTF-16 code unit of the pattern. With the u flag the grammar reads code points, a lone
 * surrogate among them; without it, code units.
 */
final class Validator {
    /** A character escape that is not valid. */
    private static final int INVALID_ESCAPE = -2;
    /** Caps a decimal escape's number as the parser's {@code number} does. */
    private static final long MAX_COUNT = Integer.MAX_VALUE;

    private static final int KIND_GROUP = 0;
    private static final int KIND_LOOKAHEAD = 1;
    private static final int KIND_LOOKBEHIND = 2;

    // A frame of a class of the v flag: whether it is negated, its phase, its operator, whether it may contain
    // strings.
    private static final int CLASS_FRAME = 4;
    /** The entries of a disjunction in {@code disjunctionTable}. */
    private static final int DISJUNCTION_FRAME = 4;
    private static final int PHASE_START = 0;
    private static final int PHASE_UNION = 1;
    private static final int PHASE_OPERANDS = 2;
    private static final int WAITING_FIRST = 3;
    private static final int WAITING_UNION = 4;
    private static final int WAITING_OPERAND = 5;

    // What a class escape was.
    private static final int ESCAPE_NONE = 0;
    private static final int ESCAPE_INVALID = 1;
    private static final int ESCAPE_VALID = 2;
    private static final int ESCAPE_STRINGS = 3;

    // One element of a class of the v flag's grammar.
    private static final int ELEMENT_INVALID = 0;
    /** A nested class, whose frame is now open. */
    private static final int ELEMENT_NESTED = 1;
    private static final int ELEMENT_OPERAND = 2;

    /** The stacks and the table of names a validator keeps between patterns, up to these sizes. */
    private static final int MAX_RETAINED_STACK = 4096;
    private static final int MAX_RETAINED_TABLE = 1024;

    private static final ThreadLocal<Validator> CURRENT = ThreadLocal.withInitial(Validator::new);

    private String s;
    private int end;
    private boolean unicode;
    private boolean sets;
    private boolean es2020;
    private Ucd ucd;

    private int i;
    private boolean hasNames;
    private long groupCount;
    private int disjunctions;
    /**
     * The disjunctions open at the position, outermost first (the parser's path, whose alternatives are in
     * {@code disjunctionTable}).
     */
    private final Stack path = new Stack();
    /** The groups open at the position: the kind of each. */
    private final Stack frames = new Stack();
    /**
     * Each named group: the start and the end of its name, the disjunction and the alternative it lies directly in, and
     * the hash of its name.
     */
    private final Stack names = new Stack();
    /**
     * Each disjunction, by its number from 1, as {@link #DISJUNCTION_FRAME} entries: the disjunction it lies in (0 for
     * the pattern's own) and the alternative of that one it lies in, its own current alternative, and whether it is
     * still open. A closed disjunction's first two entries may be moved on to an ancestor (see
     * {@link #separateAlternatives}).
     */
    private final Stack disjunctionTable = new Stack();
    /**
     * The places of the named groups by the hash of their names: one more than the place in {@code names} of the last
     * group with each name (0 for none). Open addressing, a power of two long and at most half full.
     */
    private int[] nameTable = new int[32];
    private int distinctNames;
    /** Each named reference: the start and the end of its name. */
    private final Stack references = new Stack();
    /** The classes of the v flag open at the position (see {@link #CLASS_FRAME}). */
    private final Stack classes = new Stack();

    // What the last group name, class atom or class element read was, beside its result.
    private int nameStart;
    private int nameStop;
    private boolean atomWasSet;
    private boolean elementStrings;
    private boolean elementRange;

    /** A stack of integers that grows as it needs to. */
    private static final class Stack {
        int[] items = new int[64];
        int n;

        void push(int x) {
            if (n == items.length) {
                items = Arrays.copyOf(items, 2 * n);
            }
            items[n++] = x;
        }

        int pop() {
            return items[--n];
        }

        void reset() {
            n = 0;
            if (items.length > MAX_RETAINED_STACK) {
                items = new int[64];
            }
        }
    }

    /**
     * Whether a pattern is valid in the grammar of the u and v flags (with {@code sets}, {@code unicode} is set too),
     * of an edition and under a version of Unicode.
     */
    static boolean isValid(String pattern, boolean unicode, boolean sets, boolean es2020, Ucd ucd) {
        Validator v = CURRENT.get();
        v.s = pattern;
        v.end = pattern.length();
        v.unicode = unicode;
        v.sets = sets;
        v.es2020 = es2020;
        v.ucd = ucd;
        try {
            return v.validate();
        } finally {
            v.release();
        }
    }

    private void release() {
        s = null;
        i = 0;
        hasNames = false;
        groupCount = 0;
        disjunctions = 0;
        path.reset();
        frames.reset();
        names.reset();
        disjunctionTable.reset();
        references.reset();
        classes.reset();
        if (nameTable.length > MAX_RETAINED_TABLE) {
            nameTable = new int[32];
        } else if (distinctNames != 0) {
            Arrays.fill(nameTable, 0);
        }
        distinctNames = 0;
    }

    private static boolean isOneOf(int c, String set) {
        return 0 <= c && c < 128 && set.indexOf(c) >= 0;
    }

    private static boolean isDigit(int c) {
        return c >= '0' && c <= '9';
    }

    private static int hexValue(int c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    private static boolean syntaxCharacter(int c) {
        return isOneOf(c, "^$\\.*+?()[]{}|/");
    }

    /** The element at a position: a code point with the u flag's grammar, a UTF-16 code unit without. */
    private int at(int pos) {
        char c = s.charAt(pos);
        if (unicode && Character.isHighSurrogate(c) && pos + 1 < end) {
            char low = s.charAt(pos + 1);
            if (Character.isLowSurrogate(low)) {
                return Character.toCodePoint(c, low);
            }
        }
        return c;
    }

    /** The position after the element at a position. */
    private int after(int pos) {
        return pos + (unicode && Character.isHighSurrogate(s.charAt(pos)) && pos + 1 < end
                && Character.isLowSurrogate(s.charAt(pos + 1)) ? 2 : 1);
    }

    private boolean more() {
        return i < end;
    }

    private int peek() {
        return i < end ? at(i) : -1;
    }

    /** The element after the one at the position, or -1. */
    private int peekSecond() {
        if (i >= end) {
            return -1;
        }
        int next = after(i);
        return next < end ? at(next) : -1;
    }

    private int next() {
        int c = at(i);
        i = after(i);
        return c;
    }

    private boolean eat(int c) {
        if (i < end && at(i) == c) {
            i = after(i);
            return true;
        }
        return false;
    }

    private boolean validate() {
        scanGroups();
        pushDisjunction();
        while (more()) {
            int c = peek();
            if (c == '|') {
                next();
                disjunctionTable.items[frame(path.items[path.n - 1]) + 2]++;
                continue;
            }
            if (c == ')') {
                // The end of a group: an unmatched ')' when none is open.
                if (frames.n == 0) {
                    return false;
                }
                next();
                disjunctionTable.items[frame(path.pop()) + 3] = 0;
                int kind = frames.pop();
                // Annex B lets a quantifier follow a lookahead. The u flag does not, and neither lets one follow a
                // lookbehind.
                boolean quantifiable = kind == KIND_GROUP || (!unicode && kind == KIND_LOOKAHEAD);
                int q = quantifier();
                if (q < 0 || (q > 0 && !quantifiable)) {
                    return false;
                }
                continue;
            }
            if (c == '(') {
                int kind = groupOrLook();
                if (kind < 0) {
                    return false;
                }
                frames.push(kind);
                pushDisjunction();
                continue;
            }
            int t = term(c);
            if (t < 0) {
                return false;
            }
            int q = quantifier();
            if (q < 0 || (q > 0 && t == 0)) {
                return false;
            }
        }
        // An unterminated group.
        if (frames.n != 0) {
            return false;
        }
        // Every named reference names a group somewhere in the pattern.
        for (int r = 0; r < references.n; r += 2) {
            int start = references.items[r];
            int stop = references.items[r + 1];
            if (nameTable[findName(start, stop, nameHash(start, stop))] == 0) {
                return false;
            }
        }
        return true;
    }

    private void pushDisjunction() {
        int up = path.n == 0 ? 0 : path.items[path.n - 1];
        int upAlternative = up == 0 ? 0 : disjunctionTable.items[frame(up) + 2];
        disjunctionTable.push(up);
        disjunctionTable.push(upAlternative);
        disjunctionTable.push(0);
        disjunctionTable.push(1);
        disjunctions++;
        path.push(disjunctions);
    }

    /** The place in {@code disjunctionTable} of a disjunction's entries. */
    private static int frame(int disjunction) {
        return (disjunction - 1) * DISJUNCTION_FRAME;
    }

    /** Counts the capturing groups of the pattern and notes whether any is named. */
    private void scanGroups() {
        boolean inClass = false;
        for (int pos = 0; pos < end;) {
            int c = at(pos);
            int next = after(pos);
            if (c == '\\') {
                if (next < end) {
                    next = after(next);
                }
            } else if (c == '[') {
                inClass = true;
            } else if (c == ']') {
                inClass = false;
            } else if (c == '(' && !inClass) {
                int e1 = next < end ? at(next) : -1;
                if (e1 != '?') {
                    groupCount++;
                } else {
                    int after1 = after(next);
                    int e2 = after1 < end ? at(after1) : -1;
                    int after2 = e2 >= 0 ? after(after1) : end;
                    int e3 = e2 >= 0 && after2 < end ? at(after2) : -1;
                    if (e2 == '<' && e3 >= 0 && e3 != '=' && e3 != '!') {
                        groupCount++;
                        hasNames = true;
                    }
                }
            }
            pos = next;
        }
    }

    /** Reads a term that is not a group: 1 when a quantifier may follow it, 0 when one may not, -1 when not valid. */
    private int term(int c) {
        switch (c) {
            case '^':
            case '$':
                next();
                return 0;
            case '[':
                return (sets ? classSetExpression() : characterClass()) ? 1 : -1;
            case '\\':
                return atomEscape();
            case '*':
            case '+':
            case '?':
                return -1;
            case '{':
                if (unicode || looksLikeQuantifier()) {
                    return -1;
                }
                break;
            case ']':
            case '}':
                if (unicode) {
                    return -1;
                }
                break;
            default:
                break;
        }
        next();
        return 1;
    }

    /** Whether the text at the current '{' reads as {n}, {n,} or {n,m}. */
    private boolean looksLikeQuantifier() {
        int k = after(i);
        int start = k;
        while (k < end && isDigit(at(k))) {
            k = after(k);
        }
        if (k == start || k >= end) {
            return false;
        }
        int c = at(k);
        if (c == '}') {
            return true;
        }
        if (c != ',') {
            return false;
        }
        k = after(k);
        while (k < end && isDigit(at(k))) {
            k = after(k);
        }
        return k < end && at(k) == '}';
    }

    /**
     * Whether the first of two runs of decimal digits (by position) is the greater number, whatever their lengths. A
     * digit is one code unit.
     */
    private boolean greaterAsWritten(int a, int aEnd, int b, int bEnd) {
        while (a < aEnd && s.charAt(a) == '0') {
            a++;
        }
        while (b < bEnd && s.charAt(b) == '0') {
            b++;
        }
        if (aEnd - a != bEnd - b) {
            return aEnd - a > bEnd - b;
        }
        for (; a < aEnd; a++, b++) {
            if (s.charAt(a) != s.charAt(b)) {
                return s.charAt(a) > s.charAt(b);
            }
        }
        return false;
    }

    /** Reads a run of decimal digits. */
    private void skipNumber() {
        while (more() && isDigit(peek())) {
            next();
        }
    }

    /** Reads the decimal digits at the position, saturating as the parser's number does. */
    private long numberValue() {
        long n = 0;
        while (more() && isDigit(peek())) {
            n = Math.min(n * 10 + (next() - '0'), MAX_COUNT);
        }
        return n;
    }

    /** Reads a quantifier when one is here: 0 for none, 1 for one, -1 when it is not valid. */
    private int quantifier() {
        int c = peek();
        if (c == '*' || c == '+' || c == '?') {
            next();
        } else if (c == '{' && looksLikeQuantifier()) {
            next();
            int first = i;
            skipNumber();
            int firstEnd = i;
            if (eat(',') && peek() != '}') {
                // The counts are compared as the numbers written, which can be past what a count is kept as.
                int second = i;
                skipNumber();
                if (greaterAsWritten(first, firstEnd, second, i)) {
                    return -1;
                }
            }
            if (!eat('}')) {
                return -1;
            }
        } else {
            return 0;
        }
        eat('?');
        return 1;
    }

    /** Reads the start of a group or a lookaround, to its body: its kind, or -1 when it is not valid. */
    private int groupOrLook() {
        next();
        if (!eat('?') || eat(':')) {
            return KIND_GROUP;
        }
        int c = peek();
        // Modifier groups came in ECMAScript 2025, so the 2020 grammar reads (?i as an invalid group.
        if ((c == 'i' || c == 'm' || c == 's' || c == '-') && !es2020) {
            return modifierGroup() ? KIND_GROUP : -1;
        }
        if (eat('=') || eat('!')) {
            return KIND_LOOKAHEAD;
        }
        if (!eat('<')) {
            return -1;
        }
        if (eat('=') || eat('!')) {
            return KIND_LOOKBEHIND;
        }
        if (!groupName()) {
            return -1;
        }
        int start = nameStart;
        int stop = nameStop;
        // Two groups may share a name only when they are in separate alternatives of one disjunction, so that no match
        // can go through both. The 2020 grammar refuses a name used twice, wherever the groups are. The last group with
        // the name is the only one to ask: the groups before it lie in alternatives apart from it, of a disjunction this
        // group is either outside, where its alternatives are all one to this group, or inside, in an alternative after
        // theirs.
        int hash = nameHash(start, stop);
        int slot = findName(start, stop, hash);
        boolean found = nameTable[slot] != 0;
        if (found) {
            int last = nameTable[slot] - 1;
            if (es2020 || !separateAlternatives(names.items[last + 2], names.items[last + 3])) {
                return -1;
            }
        }
        int entry = names.n;
        int disjunction = path.items[path.n - 1];
        int alternative = disjunctionTable.items[frame(disjunction) + 2];
        names.push(start);
        names.push(stop);
        names.push(disjunction);
        names.push(alternative);
        names.push(hash);
        if (found) {
            nameTable[slot] = entry + 1;
        } else {
            addName(slot, entry);
        }
        return KIND_GROUP;
    }

    /**
     * Whether a named group, by the disjunction and the alternative it lies directly in, and the current position lie in
     * different alternatives of some disjunction: of the innermost disjunction around the group that is still open,
     * which is the innermost around both. A disjunction never opens again once closed, so the walk out of closed ones
     * moves each one it passes on to its grandparent, and the walks take time in proportion to the pattern in all.
     */
    private boolean separateAlternatives(int disjunction, int alternative) {
        int[] table = disjunctionTable.items;
        while (table[frame(disjunction) + 3] == 0) {
            int at = frame(disjunction);
            int up = frame(table[at]);
            if (table[up + 3] == 0) {
                table[at] = table[up];
                table[at + 1] = table[up + 1];
            }
            alternative = table[at + 1];
            disjunction = table[at];
        }
        return table[frame(disjunction) + 2] != alternative;
    }

    /** Reads the modifiers of a group such as {@code (?i:...)} or {@code (?s-i:...)}, after the "(?", to its ':'. */
    private boolean modifierGroup() {
        int seen = 0;
        boolean removing = false;
        while (!eat(':')) {
            int c = peek();
            if (c == '-') {
                if (removing) {
                    return false;
                }
                next();
                removing = true;
                continue;
            }
            int flag = c == 'i' ? 1 : c == 'm' ? 2 : c == 's' ? 4 : 0;
            if (flag == 0 || (seen & flag) != 0) {
                return false;
            }
            next();
            seen |= flag;
        }
        return seen != 0;
    }

    /**
     * Reads a group name up to and including its '>', and keeps the positions of its first character and of the '>'
     * in {@code nameStart} and {@code nameStop}. A character of the name may be written as a Unicode escape.
     */
    private boolean groupName() {
        int start = i;
        int count = 0;
        while (true) {
            if (peek() == '>') {
                nameStart = start;
                nameStop = i;
                next();
                return count != 0;
            }
            if (!more()) {
                return false;
            }
            int c = nameCharacter();
            if (c < 0) {
                return false;
            }
            boolean ok = count == 0
                    ? c == '$' || c == '_' || ucd.idStart.get().contains(c)
                    : c == '$' || c == 0x200C || c == 0x200D || ucd.idContinue.get().contains(c);
            if (!ok) {
                return false;
            }
            count++;
        }
    }

    /** Reads one character of a group name: a code point, written or as a Unicode escape, or -1. */
    private int nameCharacter() {
        int c = next();
        if (c == '\\') {
            if (!eat('u')) {
                return -1;
            }
            return unicodeEscape(true);
        }
        if (c >= 0xD800 && c <= 0xDBFF) {
            int low = peek();
            if (low >= 0xDC00 && low <= 0xDFFF) {
                // Without the u flag the pattern is code units, and a name still takes a surrogate pair as one
                // character.
                next();
                return 0x10000 + ((c - 0xD800) << 10) + (low - 0xDC00);
            }
        }
        return c;
    }

    /** The hash of a name, by the positions of its characters, over its code points. */
    private int nameHash(int start, int stop) {
        int saved = i;
        int hash = 0x811C9DC5;
        for (i = start; i < stop;) {
            hash = (hash ^ nameCharacter()) * 16777619;
        }
        i = saved;
        return hash;
    }

    /** The slot of the name table that holds the last group with a name, or the empty slot the name would take. */
    private int findName(int start, int stop, int hash) {
        int mask = nameTable.length - 1;
        for (int slot = hash & mask;; slot = (slot + 1) & mask) {
            int held = nameTable[slot];
            if (held == 0) {
                return slot;
            }
            int n = held - 1;
            if (names.items[n + 4] == hash && sameName(start, stop, names.items[n], names.items[n + 1])) {
                return slot;
            }
        }
    }

    /** Puts a new name's group in the empty slot found for it, and doubles the table when it is half full. */
    private void addName(int slot, int entry) {
        nameTable[slot] = entry + 1;
        distinctNames++;
        if (distinctNames * 2 <= nameTable.length) {
            return;
        }
        int[] table = new int[2 * nameTable.length];
        int mask = table.length - 1;
        for (int held : nameTable) {
            if (held != 0) {
                int k = names.items[held - 1 + 4] & mask;
                while (table[k] != 0) {
                    k = (k + 1) & mask;
                }
                table[k] = held;
            }
        }
        nameTable = table;
    }

    /** Whether two names, by the positions of their characters, are the same code points. */
    private boolean sameName(int aStart, int aStop, int bStart, int bStop) {
        int saved = i;
        int a = aStart;
        int b = bStart;
        boolean same;
        while (true) {
            boolean aDone = a >= aStop;
            boolean bDone = b >= bStop;
            if (aDone || bDone) {
                same = aDone && bDone;
                break;
            }
            i = a;
            int ca = nameCharacter();
            a = i;
            i = b;
            int cb = nameCharacter();
            b = i;
            if (ca != cb) {
                same = false;
                break;
            }
        }
        i = saved;
        return same;
    }

    /** Reads an escape outside a class: 1 when a quantifier may follow it, 0 when one may not, -1 when not valid. */
    private int atomEscape() {
        next();
        if (!more()) {
            return -1;
        }
        int c = peek();
        if (c == 'b' || c == 'B') {
            next();
            return 0;
        }
        if (c == 'k') {
            next();
            // Annex B reads \k as the letter k in a pattern with no named group.
            if (!unicode && !hasNames) {
                return 1;
            }
            if (!eat('<') || !groupName()) {
                return -1;
            }
            references.push(nameStart);
            references.push(nameStop);
            return 1;
        }
        if (c >= '1' && c <= '9') {
            int start = i;
            if (numberValue() <= groupCount) {
                return 1;
            }
            if (unicode) {
                return -1;
            }
            // Annex B reads it as an octal escape, or as the digit itself.
            i = start;
            legacyOctal();
            return 1;
        }
        switch (classEscape(c)) {
            case ESCAPE_VALID:
            case ESCAPE_STRINGS:
                return 1;
            case ESCAPE_INVALID:
                return -1;
            default:
                return characterEscape(false) != INVALID_ESCAPE ? 1 : -1;
        }
    }

    /**
     * Reads an Annex B octal escape of up to three digits with a value below 256. A digit that cannot start one (8 or
     * 9) stands for itself.
     */
    private int legacyOctal() {
        int n = 0;
        int k = 0;
        while (k < 3 && more() && peek() >= '0' && peek() <= '7' && n * 8 + (peek() - '0') <= 0377) {
            n = n * 8 + (next() - '0');
            k++;
        }
        return k == 0 ? next() : n;
    }

    /** Reads a class escape such as \d or \p{...} whose letter is c. It reads nothing when c does not start one. */
    private int classEscape(int c) {
        switch (c) {
            case 'd':
            case 'D':
            case 'w':
            case 'W':
            case 's':
            case 'S':
                next();
                return ESCAPE_VALID;
            case 'p':
            case 'P': {
                // Without the u flag \p is the letter p.
                if (!unicode) {
                    return ESCAPE_NONE;
                }
                next();
                if (!eat('{')) {
                    return ESCAPE_INVALID;
                }
                int start = i;
                while (more() && peek() != '}') {
                    next();
                }
                int stop = i;
                if (!eat('}')) {
                    return ESCAPE_INVALID;
                }
                // A name ECMA-262 defines is ASCII, so an expression with any other character names nothing.
                for (int k = start; k < stop; k++) {
                    if (s.charAt(k) >= 0x80) {
                        return ESCAPE_INVALID;
                    }
                }
                PropertyNames known = ucd.propertyNames.get();
                int at = known.find(s, start, stop);
                // The 2020 grammar knows only the names and values of its own tables, whatever the version of Unicode.
                boolean found = at >= 0 && (!es2020 || known.inEs2020[at]);
                if (!found && sets && c == 'p' && hasStringProperty(start, stop)) {
                    // A property of strings, which the v flag alone names, and which has no complement.
                    return ESCAPE_STRINGS;
                }
                return found ? ESCAPE_VALID : ESCAPE_INVALID;
            }
            default:
                return ESCAPE_NONE;
        }
    }

    /** Whether the expression between two positions names a property of strings ({@link CharSet#stringProperty}). */
    private boolean hasStringProperty(int start, int stop) {
        int length = stop - start;
        if (length == "RGI_Emoji".length() && s.regionMatches(start, "RGI_Emoji", 0, length)) {
            return true;
        }
        for (String[] property : ucd.strings) {
            if (property[0].length() == length && s.regionMatches(start, property[0], 0, length)) {
                return true;
            }
        }
        return false;
    }

    /** Reads exactly n hexadecimal digits. It returns -1, reading nothing, when they are not there. */
    private int hex(int n) {
        int pos = i;
        int value = 0;
        for (int k = 0; k < n; k++) {
            if (pos >= end) {
                return -1;
            }
            int d = hexValue(at(pos));
            if (d < 0) {
                return -1;
            }
            value = value * 16 + d;
            pos = after(pos);
        }
        i = pos;
        return value;
    }

    /** Reads the escape after a backslash, in or out of a class: the character it names, or INVALID_ESCAPE. */
    private int characterEscape(boolean inClass) {
        int at = i;
        int c = next();
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
                if ((l >= 'a' && l <= 'z') || (l >= 'A' && l <= 'Z')) {
                    next();
                    return l % 32;
                }
                if (!unicode && inClass && (isDigit(l) || l == '_')) {
                    next();
                    return l % 32;
                }
                if (unicode) {
                    return INVALID_ESCAPE;
                }
                // Annex B reads a \c that is not a control escape as a backslash followed by the letter c.
                i = at;
                return '\\';
            }
            case '0':
                if (more() && isDigit(peek())) {
                    if (unicode) {
                        return INVALID_ESCAPE;
                    }
                    i = at;
                    return legacyOctal();
                }
                return 0;
            case 'x': {
                int h = hex(2);
                if (h >= 0) {
                    return h;
                }
                return unicode ? INVALID_ESCAPE : 'x';
            }
            case 'u': {
                int u = unicodeEscape(unicode);
                if (u >= 0) {
                    return u;
                }
                return unicode ? INVALID_ESCAPE : 'u';
            }
            default:
                break;
        }
        if (c >= '1' && c <= '9' && !unicode && inClass) {
            i = at;
            return legacyOctal();
        }
        // Identity escapes. The u flag allows only the syntax characters and '/', and '-' in a class. Annex B allows
        // any character, but k in a pattern with a named group.
        if ((unicode && !syntaxCharacter(c) && !(inClass && c == '-')) || (c == 'k' && hasNames)) {
            return INVALID_ESCAPE;
        }
        return c;
    }

    /**
     * Reads what follows the u of a Unicode escape and returns the character, or -1, reading nothing, when it is not a
     * well-formed escape. With {@code unicodeMode}, as with the u flag and always in a group name, it accepts the form
     * with braces and reads a surrogate pair written as two escapes as one code point.
     */
    private int unicodeEscape(boolean unicodeMode) {
        int start = i;
        if (unicodeMode && eat('{')) {
            int digits = i;
            int n = 0;
            while (more() && hexValue(peek()) >= 0) {
                if (n <= CharSet.MAX_RUNE) {
                    n = n * 16 + hexValue(peek());
                }
                next();
            }
            if (digits == i || !eat('}') || n > CharSet.MAX_RUNE) {
                i = start;
                return -1;
            }
            return n;
        }
        int u = hex(4);
        if (u < 0) {
            return -1;
        }
        if (unicodeMode && u >= 0xD800 && u <= 0xDBFF && peek() == '\\' && peekSecond() == 'u') {
            int save = i;
            next();
            next();
            int low = hex(4);
            if (low >= 0xDC00 && low <= 0xDFFF) {
                return 0x10000 + ((u - 0xD800) << 10) + (low - 0xDC00);
            }
            i = save;
        }
        return u;
    }

    /** Reads a character class from its '[' to its ']'. */
    private boolean characterClass() {
        next();
        eat('^');
        while (true) {
            if (!more()) {
                return false;
            }
            if (eat(']')) {
                return true;
            }
            int first = classAtom();
            boolean firstSet = atomWasSet;
            if (first == INVALID_ESCAPE) {
                return false;
            }
            if (peek() == '-' && peekSecond() >= 0 && peekSecond() != ']') {
                next();
                int second = classAtom();
                boolean secondSet = atomWasSet;
                if (second == INVALID_ESCAPE) {
                    return false;
                }
                if (firstSet || secondSet) {
                    // Annex B reads the '-' as itself when a class escape is at either end.
                    if (unicode) {
                        return false;
                    }
                    continue;
                }
                if (second < first) {
                    return false;
                }
            }
        }
    }

    /**
     * Reads one member of a class: its character, or 0 for a class escape, or INVALID_ESCAPE. {@code atomWasSet} says
     * whether it was a class escape.
     */
    private int classAtom() {
        atomWasSet = false;
        int c = next();
        if (c != '\\') {
            return c;
        }
        if (!more()) {
            return INVALID_ESCAPE;
        }
        int e = peek();
        if (e == 'b') {
            next();
            return 0x08;
        }
        switch (classEscape(e)) {
            case ESCAPE_VALID:
            case ESCAPE_STRINGS:
                atomWasSet = true;
                return 0;
            case ESCAPE_INVALID:
                atomWasSet = true;
                return INVALID_ESCAPE;
            default:
                return characterEscape(true);
        }
    }

    // The v flag.

    /**
     * Reads a class of the v flag's grammar from its '[' to its ']', with its nested classes on a stack of frames: a
     * union of elements, or the intersection or the difference of operands, which may not be mixed. A negated class may
     * not hold anything that may match a string.
     */
    private boolean classSetExpression() {
        openClass();
        while (true) {
            int top = classes.n - CLASS_FRAME;
            switch (classes.items[top + 1]) {
                case PHASE_START: {
                    if (peek() == ']') {
                        classes.items[top + 3] = 0;
                        int closed = closeClass();
                        if (closed != 0) {
                            return closed > 0;
                        }
                        continue;
                    }
                    int element = classSetElement();
                    if (element == ELEMENT_INVALID) {
                        return false;
                    }
                    if (element == ELEMENT_NESTED) {
                        classes.items[top + 1] = WAITING_FIRST;
                    } else if (!firstOperand(top, elementStrings, elementRange)) {
                        return false;
                    }
                    break;
                }
                case PHASE_UNION: {
                    if (more() && peek() != ']') {
                        if (setOperator() != 0) {
                            return false;
                        }
                        int element = classSetElement();
                        if (element == ELEMENT_INVALID) {
                            return false;
                        }
                        if (element == ELEMENT_NESTED) {
                            classes.items[top + 1] = WAITING_UNION;
                        } else if (elementStrings) {
                            classes.items[top + 3] = 1;
                        }
                        continue;
                    }
                    int closed = closeClass();
                    if (closed != 0) {
                        return closed > 0;
                    }
                    break;
                }
                case PHASE_OPERANDS: {
                    if (setOperator() == classes.items[top + 2]) {
                        next();
                        next();
                        if (peek() == ']' || !more()) {
                            return false;
                        }
                        int element = classSetElement();
                        if (element == ELEMENT_INVALID || elementRange) {
                            return false;
                        }
                        if (element == ELEMENT_NESTED) {
                            classes.items[top + 1] = WAITING_OPERAND;
                        } else {
                            operand(top, elementStrings);
                        }
                        continue;
                    }
                    if (peek() != ']') {
                        return false;
                    }
                    int closed = closeClass();
                    if (closed != 0) {
                        return closed > 0;
                    }
                    break;
                }
                default:
                    // A nested class is open above this one; it is never the top while it waits.
                    return false;
            }
        }
    }

    private void openClass() {
        next();
        classes.push(eat('^') ? 1 : 0);
        classes.push(PHASE_START);
        classes.push(0);
        classes.push(0);
    }

    /**
     * Closes the class at the top at its ']', and gives what it may contain to the class it is nested in, if any: 1
     * when it was the outermost, 0 when it was nested, -1 when it is not valid.
     */
    private int closeClass() {
        if (!eat(']')) {
            return -1;
        }
        int top = classes.n - CLASS_FRAME;
        boolean negated = classes.items[top] != 0;
        boolean strs = classes.items[top + 3] != 0;
        if (negated && strs) {
            return -1;
        }
        classes.n = top;
        if (classes.n == 0) {
            return 1;
        }
        // A nested class is an operand, never a range; a negated one may contain no strings.
        strs = strs && !negated;
        int parent = classes.n - CLASS_FRAME;
        switch (classes.items[parent + 1]) {
            case WAITING_FIRST:
                return firstOperand(parent, strs, false) ? 0 : -1;
            case WAITING_UNION:
                classes.items[parent + 1] = PHASE_UNION;
                if (strs) {
                    classes.items[parent + 3] = 1;
                }
                return 0;
            default:
                classes.items[parent + 1] = PHASE_OPERANDS;
                operand(parent, strs);
                return 0;
        }
    }

    /**
     * Takes the first element of a class, and decides from what follows it whether the class is a union or
     * operations.
     */
    private boolean firstOperand(int top, boolean strs, boolean range) {
        classes.items[top + 3] = strs ? 1 : 0;
        int op = setOperator();
        if (op != 0) {
            // An intersection or a difference: operands only, with the one operator between every two.
            if (range) {
                return false;
            }
            classes.items[top + 1] = PHASE_OPERANDS;
            classes.items[top + 2] = op;
            return true;
        }
        classes.items[top + 1] = PHASE_UNION;
        return true;
    }

    /** Takes an operand of an intersection (strings only where both may hold them) or a difference (the first's). */
    private void operand(int top, boolean strs) {
        if (classes.items[top + 2] == '&' && !strs) {
            classes.items[top + 3] = 0;
        }
    }

    /** The set operator at the position ('&' for &&, '-' for --), or 0. */
    private int setOperator() {
        int c = peek();
        return (c == '&' || c == '-') && peekSecond() == c ? c : 0;
    }

    /**
     * Reads one element of a class of the v flag's grammar: what it is. {@code elementStrings} says whether it may
     * hold strings, and {@code elementRange} whether it is a range of two characters.
     */
    private int classSetElement() {
        elementStrings = false;
        elementRange = false;
        int c = peek();
        if (c == '[') {
            openClass();
            return ELEMENT_NESTED;
        }
        if (c == '\\') {
            int e = peekSecond();
            if (e < 0) {
                return ELEMENT_INVALID;
            }
            if (e == 'q') {
                next();
                next();
                return classStringDisjunction() ? ELEMENT_OPERAND : ELEMENT_INVALID;
            }
            int save = i;
            next();
            switch (classEscape(e)) {
                case ESCAPE_INVALID:
                    return ELEMENT_INVALID;
                case ESCAPE_VALID:
                    return ELEMENT_OPERAND;
                case ESCAPE_STRINGS:
                    elementStrings = true;
                    return ELEMENT_OPERAND;
                default:
                    i = save;
                    break;
            }
        }
        int first = classSetCharacter();
        if (first == INVALID_ESCAPE) {
            return ELEMENT_INVALID;
        }
        if (peek() == '-' && setOperator() == 0) {
            next();
            int p = peek();
            if (p == ']' || p == '[' || (p == '\\' && isOneOf(peekSecond(), "qdDwWsSpP"))) {
                return ELEMENT_INVALID;
            }
            int second = classSetCharacter();
            if (second == INVALID_ESCAPE || second < first) {
                return ELEMENT_INVALID;
            }
            elementRange = true;
        }
        return ELEMENT_OPERAND;
    }

    /**
     * Reads one character of a class of the v flag's grammar: a character that is not a syntax character and does not
     * begin a reserved double punctuator, or an escape, which may be of a reserved punctuator.
     */
    private int classSetCharacter() {
        if (!more()) {
            return INVALID_ESCAPE;
        }
        int c = peek();
        if (c == '\\') {
            int e = peekSecond();
            if (e < 0) {
                return INVALID_ESCAPE;
            }
            if (e == 'b') {
                next();
                next();
                return 0x08;
            }
            if (isOneOf(e, "&-!#%,:;<=>@`~")) {
                next();
                next();
                return e;
            }
            next();
            return characterEscape(true);
        }
        if (isOneOf(c, "()[]{}/-\\|")) {
            return INVALID_ESCAPE;
        }
        if (peekSecond() == c && isOneOf(c, "&!#$%*+,.:;<=>?@^`~")) {
            return INVALID_ESCAPE;
        }
        next();
        return c;
    }

    /**
     * Reads the strings of \q{...} after its \q: whether it is valid. {@code elementStrings} says whether any string is
     * of other than one character.
     */
    private boolean classStringDisjunction() {
        if (!eat('{')) {
            return false;
        }
        int count = 0;
        while (true) {
            if (!more()) {
                return false;
            }
            int c = peek();
            if (c == '|' || c == '}') {
                next();
                if (count != 1) {
                    elementStrings = true;
                }
                count = 0;
                if (c == '}') {
                    return true;
                }
                continue;
            }
            if (classSetCharacter() == INVALID_ESCAPE) {
                return false;
            }
            count++;
        }
    }

    /**
     * Every expression {@code \p{...}} may hold under a version of Unicode, and whether the grammar of ECMAScript 2020
     * knows it, in a table that a stretch of a pattern is looked up in without making a string of it.
     */
    static final class PropertyNames {
        private static final String[] SCRIPT_PREFIXES = {"Script=", "sc=", "Script_Extensions=", "scx="};
        private static final String[] CATEGORY_PREFIXES = {"", "gc=", "General_Category="};

        /** The expressions by the hash of their characters, open addressing, at most half full. */
        private final String[] slots;
        /** Whether ECMAScript 2020 knows the expression in each slot. */
        final boolean[] inEs2020;

        private PropertyNames(String[] slots, boolean[] inEs2020) {
            this.slots = slots;
            this.inEs2020 = inEs2020;
        }

        static PropertyNames of(Ucd ucd) {
            java.util.List<String> candidates = new java.util.ArrayList<>();
            for (String[] row : CharSet.CATEGORY_NAMES) {
                for (int k = 1; k < row.length; k++) {
                    for (String prefix : CATEGORY_PREFIXES) {
                        candidates.add(prefix + row[k]);
                    }
                }
            }
            for (String[] row : CharSet.BINARY_PROPERTY_NAMES) {
                for (int k = 1; k < row.length; k++) {
                    candidates.add(row[k]);
                }
            }
            java.util.List<String> scripts = new java.util.ArrayList<>();
            for (String[] row : ucd.scriptNames) {
                scripts.addAll(Arrays.asList(row));
            }
            scripts.add("Katakana_Or_Hiragana");
            scripts.add("Hrkt");
            for (String script : scripts) {
                for (String prefix : SCRIPT_PREFIXES) {
                    candidates.add(prefix + script);
                }
            }
            int size = 64;
            while (size < 2 * candidates.size()) {
                size *= 2;
            }
            String[] slots = new String[size];
            boolean[] inEs2020 = new boolean[size];
            for (String expr : candidates) {
                if (!CharSet.hasProperty(ucd, expr)) {
                    continue;
                }
                int k = expr.hashCode() & (size - 1);
                while (slots[k] != null && !slots[k].equals(expr)) {
                    k = (k + 1) & (size - 1);
                }
                slots[k] = expr;
                inEs2020[k] = Es2020.hasProperty(expr);
            }
            return new PropertyNames(slots, inEs2020);
        }

        /** The slot of the expression between two positions of a string, or -1 when it names no property. */
        int find(String s, int start, int stop) {
            int hash = 0;
            for (int k = start; k < stop; k++) {
                hash = 31 * hash + s.charAt(k);
            }
            int length = stop - start;
            int mask = slots.length - 1;
            for (int k = hash & mask;; k = (k + 1) & mask) {
                String held = slots[k];
                if (held == null) {
                    return -1;
                }
                if (held.length() == length && s.regionMatches(start, held, 0, length)) {
                    return k;
                }
            }
        }
    }
}
