package io.github.corvusdotnet.regex.ecma262;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The compiler: a syntax tree to a program of the backtracking matcher, with what the matcher needs to give up early
 * ({@code compile.rs}, which is {@code compile.go}).
 */
final class Compiler {
    /** The need of an instruction from which no way leads to a match. Every need is capped at it. */
    static final long NO_MATCH = 1L << 30;

    private final List<Inst> insts = new ArrayList<>();
    /**
     * Maps a capturing group's number to its slot, for the groups some backreference names. The others are not
     * recorded at all.
     */
    private final Map<Integer, Integer> tracked = new HashMap<>();
    private int loops;
    /** The pattern was read with the u flag grammar, which decides how a backreference folds case. */
    private final boolean unicode;
    /**
     * {@code memo} says the pattern has no backreference, so its choices can have memo points. {@code open} lists the
     * counted loops around the instruction being written, outermost first, inside the innermost lookaround: three
     * ints each (loop, min, simple). {@code points} collects the memo points.
     */
    private boolean memo;
    private IntList open = new IntList();
    private final IntList pointLoops = new IntList();
    private final IntList pointMins = new IntList();

    private Compiler(boolean unicode) {
        this.unicode = unicode;
    }

    /**
     * Compiles a tree. It takes the tree and changes it in place. With {@code groups} non-negative the program records
     * every one of that many capturing groups, group g in slot g - 1, so that a match can report what each captured;
     * with -1, only the groups some backreference names.
     */
    static Program compileProgram(Node root, boolean unicode, int groups) {
        root = simplify(root, new IntList());
        Compiler c = new Compiler(unicode);
        IntList referenced = new IntList();
        collectBackrefs(root, referenced);
        // A choice can be remembered to have failed only where what a group holds cannot change what follows.
        c.memo = referenced.isEmpty();
        boolean backrefs = !referenced.isEmpty();
        if (groups >= 0) {
            for (int g = 1; g <= groups; g++) {
                c.tracked.put(g, g - 1);
            }
        } else {
            // Slots follow group order, so the groups inside any one subtree take a contiguous range of slots.
            int[] refs = referenced.toArray();
            Arrays.sort(refs);
            int slot = 0;
            for (int k = 0; k < refs.length; k++) {
                if (k > 0 && refs[k] == refs[k - 1]) {
                    continue;
                }
                c.tracked.put(refs[k], slot++);
            }
        }
        c.emit(root, false);
        c.add(new Inst(Inst.MATCH));
        Inst[] code = c.insts.toArray(new Inst[0]);
        Program p = new Program();
        p.insts = code;
        int[] needs = setNeeds(code);
        p.need = needs[0];
        p.need16 = needs[1];
        setQuick(p);
        setBack(p);
        p.groups = c.tracked.size();
        p.loops = c.loops;
        p.units = !unicode;
        p.anchored = startsAtBol(root);
        p.pointLoops = c.pointLoops.toArray();
        p.pointMins = c.pointMins.toArray();
        p.points = p.pointLoops.length / Program.MAX_MEMO_LOOPS;
        if (p.groups == 0 && !backrefs) {
            p.anchoredDfa = Adfa.build(root);
        }
        if (!p.anchored) {
            IntList pairs = new IntList();
            int nullable = firstChars(root, pairs);
            if (nullable == 0) {
                p.first = CharSet.of(pairs, false);
            }
            p.search = Search.plan(root, backrefs, p.first, !unicode);
            byte[] literal = p.search.singleUtf8();
            if (p.search.windowsAtZero() && p.anchoredDfa != null && literal != null) {
                p.afterLiteral = p.anchoredDfa.after(literal);
                p.afterLiteralLength = literal.length;
            }
            // The tree is kept, and later built from, on the thread that searches, whose stack may be small: only a
            // shallow one is kept.
            if (!Search.noSearch() && !backrefs && shallowerThan(root, 64) && p.search.plan == Search.Plan.SCAN
                    && p.search.shape == Search.Shape.NONE) {
                p.forwardRoot = root;
                p.skip = Adfa.skipWindow(root);
            }
        }
        return p;
    }

    /** Whether the tree under {@code n} is less than {@code limit} levels deep. */
    static boolean shallowerThan(Node n, int limit) {
        if (limit <= 0) {
            return false;
        }
        for (Node s : n.subs) {
            if (!shallowerThan(s, limit - 1)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the tree with two rewrites, both of which V8 makes. Neither changes what the pattern matches or what any
     * group holds. A backreference to a group it is inside of is the empty string. A repetition whose body cannot
     * consume a character is the body once when its minimum is one or more, and nothing when its minimum is zero.
     */
    static Node simplify(Node n, IntList open) {
        if (n.kind == Node.Kind.BACKREF) {
            boolean allOpen = true;
            for (int k = 0; k < n.groups.size(); k++) {
                if (!contains(open, n.groups.get(k))) {
                    allOpen = false;
                    break;
                }
            }
            return allOpen ? new Node(Node.Kind.EMPTY) : n;
        }
        if (n.subs.isEmpty()) {
            return n;
        }
        boolean opened = n.kind == Node.Kind.GROUP && n.group > 0;
        if (opened) {
            open.add(n.group);
        }
        for (int k = 0; k < n.subs.size(); k++) {
            n.subs.set(k, simplify(n.subs.get(k), open));
        }
        if (opened) {
            open.pop();
        }
        if (n.kind == Node.Kind.CAT) {
            boolean nested = false;
            for (Node sub : n.subs) {
                if (sub.kind == Node.Kind.CAT) {
                    nested = true;
                    break;
                }
            }
            if (nested) {
                // A sequence inside a sequence (a written-out repetition) is the same sequence: flat, so that what
                // reads the terms (the searches, the first characters) sees them all.
                List<Node> flat = new ArrayList<>(n.subs.size());
                for (Node sub : n.subs) {
                    if (sub.kind == Node.Kind.CAT) {
                        flat.addAll(sub.subs);
                    } else {
                        flat.add(sub);
                    }
                }
                n.subs = flat;
            }
        }
        if (n.kind == Node.Kind.REPEAT && n.max != 0 && !consumes(n.subs.get(0))) {
            if (n.min == 0) {
                return new Node(Node.Kind.EMPTY);
            }
            return n.subs.remove(n.subs.size() - 1);
        }
        if (!noUnroll && n.kind == Node.Kind.REPEAT && n.min == n.max && n.max >= 2 && n.max <= 32
                && (n.subs.get(0).kind == Node.Kind.SET || n.subs.get(0).kind == Node.Kind.CHAR)) {
            // An exact count of one character or class is that many of it in a row: a straight run of tests without a
            // loop's counting.
            return unroll(n);
        }
        Node body = n.kind == Node.Kind.REPEAT ? n.subs.get(0) : null;
        if (!noUnroll && body != null && n.max >= 1 && n.max <= 4 && n.min >= 1 && !nullable(body) && !hasCapture(body)
                && size(body) <= 32
                && (body.kind == Node.Kind.GROUP || body.kind == Node.Kind.ALT || body.kind == Node.Kind.CAT)) {
            // A small counted repetition of a body that holds no group and cannot be empty is the body written out,
            // with the copies beyond the least count optional one inside the other.
            return unroll(n);
        }
        return n;
    }

    private static boolean contains(IntList list, int v) {
        for (int k = 0; k < list.size(); k++) {
            if (list.get(k) == v) {
                return true;
            }
        }
        return false;
    }

    /**
     * Writes out a counted repetition: X{2,4} is X X (X (X)?)?, the optional copies one inside the other, so that a
     * copy is tried only when the one before it matched.
     */
    /** Keeps every counted repetition a loop, for the tests of the loops' needs and memo points. */
    static boolean noUnroll;

    private static Node unroll(Node n) {
        Node body = n.subs.get(0);
        List<Node> terms = new ArrayList<>();
        for (int k = 0; k < n.min; k++) {
            terms.add(body.deepCopy());
        }
        if (n.max > n.min) {
            Node optional = null;
            for (int k = n.min; k < n.max; k++) {
                Node copy = body.deepCopy();
                Node inner;
                if (optional == null) {
                    inner = copy;
                } else {
                    List<Node> pair = new ArrayList<>(2);
                    pair.add(copy);
                    pair.add(optional);
                    inner = Node.withSubs(Node.Kind.CAT, pair);
                }
                Node rep = Node.withSub(Node.Kind.REPEAT, inner);
                rep.min = 0;
                rep.max = 1;
                rep.lazy = n.lazy;
                optional = rep;
            }
            terms.add(optional);
        }
        if (terms.size() == 1) {
            return terms.get(0);
        }
        List<Node> flat = new ArrayList<>(terms.size());
        for (Node t : terms) {
            if (t.kind == Node.Kind.CAT) {
                flat.addAll(t.subs);
            } else {
                flat.add(t);
            }
        }
        return Node.withSubs(Node.Kind.CAT, flat);
    }

    /** Whether a node holds a capturing group, a backreference or a lookaround. */
    private static boolean hasCapture(Node n) {
        if (n.kind == Node.Kind.GROUP && n.group > 0 || n.kind == Node.Kind.BACKREF || n.kind == Node.Kind.LOOK) {
            return true;
        }
        for (Node sub : n.subs) {
            if (hasCapture(sub)) {
                return true;
            }
        }
        return false;
    }

    /** The number of nodes of a tree. */
    private static int size(Node n) {
        int s = 1;
        for (Node sub : n.subs) {
            s += size(sub);
        }
        return s;
    }

    /** Whether n can match one character or more. It errs towards true. */
    private static boolean consumes(Node n) {
        switch (n.kind) {
            case CHAR:
            case SET:
            case BACKREF:
                return true;
            case CAT:
            case ALT:
            case GROUP:
                for (Node sub : n.subs) {
                    if (consumes(sub)) {
                        return true;
                    }
                }
                return false;
            case REPEAT:
                return n.max != 0 && consumes(n.subs.get(0));
            default:
                return false;
        }
    }

    private static void collectBackrefs(Node n, IntList out) {
        if (n.kind == Node.Kind.BACKREF) {
            out.addAll(n.groups);
        }
        for (Node sub : n.subs) {
            collectBackrefs(sub, out);
        }
    }

    private int add(Inst i) {
        insts.add(i);
        return insts.size() - 1;
    }

    private int pc() {
        return insts.size();
    }

    private Inst inst(int at) {
        return insts.get(at);
    }

    /** The number of a new memo point for an instruction written here, or 0 when a choice here cannot be remembered. */
    private int point() {
        int count = open.size() / 3;
        if (!memo || count > Program.MAX_MEMO_LOOPS) {
            return 0;
        }
        int[] loopsOf = new int[Program.MAX_MEMO_LOOPS];
        int[] minsOf = new int[Program.MAX_MEMO_LOOPS];
        Arrays.fill(loopsOf, -1);
        for (int k = 0; k < count; k++) {
            if (open.get(3 * k + 2) == 0) {
                return 0;
            }
            loopsOf[k] = open.get(3 * k);
            minsOf[k] = open.get(3 * k + 1);
        }
        pointLoops.addAll(loopsOf);
        pointMins.addAll(minsOf);
        return pointLoops.size() / Program.MAX_MEMO_LOOPS;
    }

    /** The first and last slot of the tracked groups inside n, or {@code null} when it has none. */
    private int[] groupRange(Node n) {
        int[] range = {Integer.MAX_VALUE, Integer.MIN_VALUE};
        walkGroups(n, range);
        return range[0] == Integer.MAX_VALUE ? null : range;
    }

    private void walkGroups(Node n, int[] range) {
        if (n.kind == Node.Kind.GROUP && n.group > 0) {
            Integer t = tracked.get(n.group);
            if (t != null) {
                range[0] = Math.min(range[0], t);
                range[1] = Math.max(range[1], t);
            }
        }
        for (Node sub : n.subs) {
            walkGroups(sub, range);
        }
    }

    /** Writes the instructions for n. With {@code back} set they match right to left, as a lookbehind's body must. */
    private void emit(Node n, boolean back) {
        switch (n.kind) {
            case EMPTY:
                break;
            case CHAR: {
                Inst i = new Inst(back ? Inst.CHAR_BACK : Inst.CHAR);
                i.r = n.r;
                add(i);
                break;
            }
            case SET: {
                if (n.set.isEmpty()) {
                    add(new Inst(Inst.FAIL));
                } else {
                    Inst i = new Inst(back ? Inst.SET_BACK : Inst.SET);
                    i.set = n.set;
                    add(i);
                }
                break;
            }
            case CAT: {
                if (back) {
                    for (int k = n.subs.size() - 1; k >= 0; k--) {
                        emit(n.subs.get(k), true);
                    }
                    return;
                }
                int i = 0;
                while (i < n.subs.size()) {
                    // A run of literal characters becomes one comparison of units.
                    int j = i;
                    StringBuilder lit = new StringBuilder();
                    while (j < n.subs.size() && n.subs.get(j).kind == Node.Kind.CHAR && literalRune(n.subs.get(j).r)) {
                        lit.appendCodePoint(n.subs.get(j).r);
                        j++;
                    }
                    if (j - i >= 2) {
                        Inst l = new Inst(Inst.LIT);
                        String s = lit.toString();
                        l.lit = s.getBytes(StandardCharsets.UTF_8);
                        l.lit16 = s.toCharArray();
                        add(l);
                        i = j;
                        continue;
                    }
                    emit(n.subs.get(i), false);
                    i++;
                }
                break;
            }
            case ALT: {
                IntList jumps = new IntList();
                for (int k = 0; k < n.subs.size(); k++) {
                    Node sub = n.subs.get(k);
                    if (k == n.subs.size() - 1) {
                        emit(sub, back);
                        break;
                    }
                    Inst s = new Inst(Inst.SPLIT);
                    s.m = point();
                    int split = add(s);
                    inst(split).x = pc();
                    emit(sub, back);
                    jumps.add(add(new Inst(Inst.JMP)));
                    inst(split).y = pc();
                }
                for (int k = 0; k < jumps.size(); k++) {
                    inst(jumps.get(k)).x = pc();
                }
                break;
            }
            case GROUP: {
                Integer t = n.group == 0 ? null : tracked.get(n.group);
                if (t == null) {
                    emit(n.subs.get(0), back);
                    return;
                }
                Inst save = new Inst(Inst.SAVE_TMP);
                save.a = t;
                add(save);
                emit(n.subs.get(0), back);
                Inst cap = new Inst(Inst.CAPTURE);
                cap.a = t;
                cap.flag = back;
                add(cap);
                break;
            }
            case BOL:
                add(new Inst(Inst.BOL));
                break;
            case EOL:
                add(new Inst(Inst.EOL));
                break;
            case LINE_START:
                add(new Inst(Inst.LINE_START));
                break;
            case LINE_END:
                add(new Inst(Inst.LINE_END));
                break;
            case WORD_BOUNDARY:
                add(new Inst(n.fold ? Inst.WORD_BOUNDARY_FOLD : Inst.WORD_BOUNDARY));
                break;
            case NOT_WORD_BOUNDARY:
                add(new Inst(n.fold ? Inst.NOT_WORD_BOUNDARY_FOLD : Inst.NOT_WORD_BOUNDARY));
                break;
            case BACKREF: {
                int op;
                if (n.fold) {
                    op = back ? Inst.BACKREF_FOLD_BACK : Inst.BACKREF_FOLD;
                } else if (back) {
                    op = unicode ? Inst.BACKREF_BACK : Inst.BACKREF_UNITS_BACK;
                } else {
                    op = unicode ? Inst.BACKREF : Inst.BACKREF_UNITS;
                }
                // When several groups share the name, at most one has taken part, and the others match the empty
                // string. So matching each in turn matches the one that took part.
                for (int k = 0; k < n.groups.size(); k++) {
                    Inst i = new Inst(op);
                    i.a = tracked.get(n.groups.get(k));
                    i.flag = unicode;
                    add(i);
                }
                break;
            }
            case LOOK: {
                Inst l = new Inst(Inst.LOOK);
                l.flag = n.negative;
                int look = add(l);
                // Whether the body matches depends on nothing outside it.
                IntList outer = open;
                open = new IntList();
                emit(n.subs.get(0), n.behind);
                open = outer;
                add(new Inst(n.negative ? Inst.LOOK_FAIL : Inst.LOOK_OK));
                inst(look).x = pc();
                break;
            }
            case REPEAT:
                emitRepeat(n, back);
                break;
            default:
                throw new IllegalStateException(n.kind.toString());
        }
    }

    private void emitBody(Node body, boolean back, int[] clear) {
        if (clear != null) {
            Inst c = new Inst(Inst.CLEAR);
            c.a = clear[0];
            c.b = clear[1];
            add(c);
        }
        emit(body, back);
    }

    /** Writes a choice between entering the body and leaving the loop, to be completed by {@code setSplit}. */
    private int split() {
        Inst s = new Inst(Inst.SPLIT);
        s.m = point();
        return add(s);
    }

    /** Completes a split in the order the quantifier prefers. */
    private void setSplit(boolean lazy, int at, int enter, int exit) {
        Inst i = inst(at);
        if (lazy) {
            i.x = exit;
            i.y = enter;
        } else {
            i.x = enter;
            i.y = exit;
        }
    }

    private void emitRepeat(Node n, boolean back) {
        Node body = n.subs.get(0);
        if (n.max == 0) {
            return;
        }
        int min = n.min;
        int max = n.max;
        if (!back && (body.kind == Node.Kind.CHAR || body.kind == Node.Kind.SET && !body.set.isEmpty())) {
            Inst rep = new Inst(n.lazy ? Inst.REP_LAZY : Inst.REP);
            rep.m = point();
            rep.r = body.r;
            rep.set = body.set;
            rep.min = min;
            rep.max = max;
            add(rep);
            return;
        }
        int[] clear = groupRange(body);
        // A body that always consumes something needs no check for empty iterations, so the unbounded forms and the
        // optional form are plain choices.
        if (!nullable(body)) {
            if (n.min == 0 && n.max == Node.UNBOUNDED) {
                int at = split();
                int enter = pc();
                emitBody(body, back, clear);
                Inst j = new Inst(Inst.JMP);
                j.x = at;
                add(j);
                int exit = pc();
                setSplit(n.lazy, at, enter, exit);
                return;
            }
            if (n.min == 1 && n.max == Node.UNBOUNDED) {
                int enter = pc();
                emitBody(body, back, clear);
                int at = split();
                int exit = pc();
                setSplit(n.lazy, at, enter, exit);
                return;
            }
            if (n.min == 0 && n.max == 1) {
                int at = split();
                int enter = pc();
                emitBody(body, back, clear);
                int exit = pc();
                setSplit(n.lazy, at, enter, exit);
                return;
            }
        }
        int k = loops++;
        Inst init = new Inst(Inst.LOOP_INIT);
        init.a = k;
        add(init);
        long iter = back ? 0 : minLength(body, true);
        long iter16 = back ? 0 : minLength(body, false);
        boolean simple = max == Node.UNBOUNDED && min <= 1;
        // The loop is left or entered from here, and what follows either depends on the loops around this one.
        int point = simple ? point() : 0;
        Inst l = new Inst(Inst.LOOP);
        l.a = k;
        l.min = min;
        l.max = max;
        l.flag = n.lazy;
        l.iter = (int) iter;
        l.iter16 = (int) iter16;
        l.m = point;
        int loop = add(l);
        Inst it = new Inst(Inst.LOOP_ITER);
        it.a = k;
        add(it);
        open.add(k, min);
        open.add(simple ? 1 : 0);
        emitBody(body, back, clear);
        open.truncate(open.size() - 3);
        Inst j = new Inst(Inst.JMP);
        j.x = loop;
        add(j);
        inst(loop).x = pc();
    }

    /** The number of bytes of UTF-8 a code point takes. */
    private static long runeLen(int r) {
        if (r <= 0x7F) {
            return 1;
        }
        if (r <= 0x7FF) {
            return 2;
        }
        if (r <= 0xFFFF) {
            return 3;
        }
        return 4;
    }

    /**
     * The least number of units a match of n consumes, capped at NO_MATCH: bytes of UTF-8 with {@code utf8}, and UTF-16
     * code units without it. It counts a character as its units when the matcher compares units for it and as one
     * unit otherwise, so it can only be too small.
     */
    static long minLength(Node n, boolean utf8) {
        switch (n.kind) {
            case CHAR:
                if (!literalRune(n.r)) {
                    return 1;
                } else if (utf8) {
                    return runeLen(n.r);
                } else {
                    return n.r > 0xFFFF ? 2 : 1;
                }
            case SET:
                return n.set.isEmpty() ? NO_MATCH : 1;
            case CAT: {
                long total = 0;
                for (Node sub : n.subs) {
                    total += minLength(sub, utf8);
                    if (total >= NO_MATCH) {
                        return NO_MATCH;
                    }
                }
                return total;
            }
            case ALT: {
                long least = NO_MATCH;
                for (Node sub : n.subs) {
                    least = Math.min(least, minLength(sub, utf8));
                }
                return Math.min(least, NO_MATCH);
            }
            case GROUP:
                return minLength(n.subs.get(0), utf8);
            case REPEAT:
                return timesNeed(n.min, minLength(n.subs.get(0), utf8));
            default:
                return 0;
        }
    }

    /** {@code count} times {@code need}, capped at NO_MATCH. */
    static long timesNeed(long count, long need) {
        if (count == 0 || need == 0) {
            return 0;
        }
        if (need >= NO_MATCH || count >= NO_MATCH / need) {
            return NO_MATCH;
        }
        return count * need;
    }

    /**
     * Works out, for every instruction, the least number of bytes of text that any way from it to a match consumes
     * going forward, and writes it into the counted loops. It returns the need of the whole program, in bytes of UTF-8
     * and in UTF-16 code units. The matcher gives up at a counted loop when fewer units than that are left, as V8 does
     * at every choice.
     */
    static int[] setNeeds(Inst[] insts) {
        int need16 = needsIn(insts, false, null);
        int need8 = needsIn(insts, true, null);
        return new int[] {need8, need16};
    }

    /**
     * {@link #setNeeds} in bytes of UTF-8 alone, and also the number of passes over the instructions it made, the last
     * of which changed nothing.
     */
    static int[] needs(Inst[] insts) {
        int[] passes = new int[1];
        int need = needsIn(insts, true, passes);
        return new int[] {need, passes[0]};
    }

    private static int needsIn(Inst[] insts, boolean utf8, int[] passes) {
        int[] need = new int[insts.length];
        Arrays.fill(need, (int) NO_MATCH);
        // The needs only fall, and nearly every way goes forward, so going through the instructions from the last to
        // the first settles all but the loops in one pass.
        boolean changed = true;
        int count = 0;
        while (changed) {
            changed = false;
            count++;
            for (int pc = insts.length - 1; pc >= 0; pc--) {
                Inst i = insts[pc];
                long n = NO_MATCH;
                int after = pc + 1;
                switch (i.op) {
                    case Inst.MATCH:
                    case Inst.LOOK_OK:
                    case Inst.LOOK_FAIL:
                        n = 0;
                        break;
                    case Inst.FAIL:
                        break;
                    case Inst.CHAR:
                    case Inst.SET:
                        n = Math.min(n, need[after] + 1L);
                        break;
                    case Inst.LIT:
                        n = Math.min(n, need[after] + (long) (utf8 ? i.lit.length : i.lit16.length));
                        break;
                    case Inst.REP:
                    case Inst.REP_LAZY:
                        n = Math.min(n, need[after] + (long) i.min);
                        break;
                    case Inst.JMP: {
                        // The one way that goes back is the jump at the end of the body of a loop, to its head, which
                        // this pass has not reached. That head's need is the need of what follows the loop, so the jump
                        // takes what follows the loop, which this pass has settled.
                        Inst head = insts[i.x];
                        if (i.x < pc && head.op == Inst.LOOP) {
                            n = Math.min(n, need[head.x]);
                        } else if (i.x < pc && head.op == Inst.SPLIT) {
                            n = Math.min(n, need[head.x]);
                            n = Math.min(n, need[head.y]);
                        } else {
                            n = Math.min(n, need[i.x]);
                        }
                        break;
                    }
                    case Inst.LOOK:
                        n = Math.min(n, need[i.x]);
                        break;
                    case Inst.SPLIT:
                        n = Math.min(n, need[i.x]);
                        n = Math.min(n, need[i.y]);
                        break;
                    case Inst.LOOP:
                        n = Math.min(n, need[after]);
                        n = Math.min(n, need[i.x]);
                        break;
                    case Inst.LOOP_INIT: {
                        Inst loop = insts[pc + 1];
                        int iter = utf8 ? loop.iter : loop.iter16;
                        n = Math.min(n, need[after] + timesNeed(loop.min, iter));
                        break;
                    }
                    default:
                        n = Math.min(n, need[after]);
                        break;
                }
                int v = (int) Math.min(n, NO_MATCH);
                if (v < need[pc]) {
                    need[pc] = v;
                    changed = true;
                }
            }
        }
        for (int pc = 0; pc < insts.length; pc++) {
            if (insts[pc].op == Inst.LOOP) {
                if (utf8) {
                    insts[pc].need = need[pc];
                } else {
                    insts[pc].need16 = need[pc];
                }
            }
        }
        if (passes != null) {
            passes[0] = count;
        }
        return need[0];
    }

    /**
     * Works out what can come next on a way from each instruction to the end of its match: the matcher looks at it
     * before it takes a way, and a way the next character rules out is not taken (V8's quick check). Every figure is a
     * set that can only be too large: an assertion is taken to hold, a backreference and an instruction that matches
     * right to left to allow anything. A positive lookahead allows what both its body and what follows it allow. It
     * returns the number of passes over the instructions it made, the last of which changed nothing.
     */
    static int setQuick(Program p) {
        Inst[] insts = p.insts;
        int count = insts.length;
        long[] q0 = new long[count];
        long[] q1 = new long[count];
        byte[] qf = new byte[count];
        final byte all = (byte) (Program.Q_OTHER | Program.Q_MID | Program.Q_END);
        boolean changed = true;
        int passes = 0;
        while (changed) {
            changed = false;
            passes++;
            for (int pc = count - 1; pc >= 0; pc--) {
                Inst i = insts[pc];
                long n0 = 0;
                long n1 = 0;
                int nf = 0;
                switch (i.op) {
                    case Inst.MATCH:
                    case Inst.LOOK_OK:
                    case Inst.LOOK_FAIL:
                        n0 = -1L;
                        n1 = -1L;
                        nf = all;
                        break;
                    case Inst.FAIL:
                        break;
                    case Inst.CHAR: {
                        int r = i.r;
                        if (r >= 0 && r < 0x80) {
                            if (r < 64) {
                                n0 |= 1L << r;
                            } else {
                                n1 |= 1L << (r - 64);
                            }
                        } else {
                            nf |= Program.Q_OTHER;
                        }
                        break;
                    }
                    case Inst.SET:
                        n0 |= i.set.ascii0;
                        n1 |= i.set.ascii1;
                        if (i.set.ranges.length > 0 && i.set.ranges[i.set.ranges.length - 1] >= 0x80) {
                            nf |= Program.Q_OTHER;
                        }
                        break;
                    case Inst.LIT: {
                        int r = i.lit[0] & 0xFF;
                        if (r < 0x80) {
                            if (r < 64) {
                                n0 |= 1L << r;
                            } else {
                                n1 |= 1L << (r - 64);
                            }
                        } else {
                            nf |= Program.Q_OTHER;
                        }
                        break;
                    }
                    case Inst.REP:
                    case Inst.REP_LAZY:
                        if (i.set != null) {
                            n0 |= i.set.ascii0;
                            n1 |= i.set.ascii1;
                            if (i.set.ranges.length > 0 && i.set.ranges[i.set.ranges.length - 1] >= 0x80) {
                                nf |= Program.Q_OTHER;
                            }
                        } else if (i.r >= 0 && i.r < 0x80) {
                            if (i.r < 64) {
                                n0 |= 1L << i.r;
                            } else {
                                n1 |= 1L << (i.r - 64);
                            }
                        } else {
                            nf |= Program.Q_OTHER;
                        }
                        if (i.min == 0) {
                            n0 |= q0[pc + 1];
                            n1 |= q1[pc + 1];
                            nf |= qf[pc + 1];
                        }
                        break;
                    case Inst.JMP: {
                        // The jump at the end of the body of a loop goes back to the head of the loop, which this pass
                        // has not reached. The head allows what follows the loop, which this pass has settled, and what
                        // the body starts with. So the jump takes what follows the loop too.
                        n0 = q0[i.x];
                        n1 = q1[i.x];
                        nf = qf[i.x];
                        Inst head = insts[i.x];
                        if (i.x < pc && head.op == Inst.LOOP) {
                            n0 |= q0[head.x];
                            n1 |= q1[head.x];
                            nf |= qf[head.x];
                        } else if (i.x < pc && head.op == Inst.SPLIT) {
                            n0 |= q0[head.x] | q0[head.y];
                            n1 |= q1[head.x] | q1[head.y];
                            nf |= qf[head.x] | qf[head.y];
                        }
                        break;
                    }
                    case Inst.SPLIT:
                        n0 = q0[i.x] | q0[i.y];
                        n1 = q1[i.x] | q1[i.y];
                        nf = qf[i.x] | qf[i.y];
                        break;
                    case Inst.LOOP:
                        n0 = q0[pc + 1] | q0[i.x];
                        n1 = q1[pc + 1] | q1[i.x];
                        nf = qf[pc + 1] | qf[i.x];
                        break;
                    case Inst.EOL:
                        nf = qf[pc + 1] & Program.Q_END;
                        break;
                    case Inst.LOOK:
                        n0 = q0[i.x];
                        n1 = q1[i.x];
                        nf = qf[i.x];
                        if (!i.flag) {
                            n0 &= q0[pc + 1];
                            n1 &= q1[pc + 1];
                            nf &= qf[pc + 1];
                        }
                        break;
                    case Inst.CHAR_BACK:
                    case Inst.SET_BACK:
                    case Inst.BACKREF:
                    case Inst.BACKREF_BACK:
                    case Inst.BACKREF_FOLD:
                    case Inst.BACKREF_FOLD_BACK:
                    case Inst.BACKREF_UNITS:
                    case Inst.BACKREF_UNITS_BACK:
                        n0 = -1L;
                        n1 = -1L;
                        nf = all;
                        break;
                    default:
                        n0 = q0[pc + 1];
                        n1 = q1[pc + 1];
                        nf = qf[pc + 1];
                        break;
                }
                if (n0 != q0[pc] || n1 != q1[pc] || (byte) nf != qf[pc]) {
                    q0[pc] = n0;
                    q1[pc] = n1;
                    qf[pc] = (byte) nf;
                    changed = true;
                }
            }
        }
        p.quick0 = q0;
        p.quick1 = q1;
        p.quickFlags = qf;
        return passes;
    }

    /**
     * Gives each greedy single-character loop the ASCII bytes what follows it can start with, when there are one to
     * three and nothing else can follow: the vectorised give-back of the .NET reference engine.
     */
    private static void setBack(Program p) {
        Inst[] insts = p.insts;
        for (int pc = 0; pc + 1 < insts.length; pc++) {
            if (insts[pc].op != Inst.REP) {
                continue;
            }
            int f = p.quickFlags[pc + 1];
            if ((f & (Program.Q_OTHER | Program.Q_MID)) != 0) {
                continue;
            }
            byte[] bytes = new byte[3];
            int n = 0;
            for (int c = 0; c < 0x80; c++) {
                long word = c < 64 ? p.quick0[pc + 1] >>> c : p.quick1[pc + 1] >>> (c - 64);
                if ((word & 1) != 0) {
                    if (n == 3) {
                        n = 4;
                        break;
                    }
                    bytes[n++] = (byte) c;
                }
            }
            if (n >= 1 && n <= 3) {
                insts[pc].back = Arrays.copyOf(bytes, n);
            }
        }
    }

    /**
     * Whether a code point can be matched by comparing its encoded units. A surrogate has no encoding, and U+FFFD is
     * also what a malformed byte of the text decodes to.
     */
    static boolean literalRune(int r) {
        return r >= 0 && r <= 0x10FFFF && !(r >= 0xD800 && r <= 0xDFFF) && r != Utf8.RUNE_ERROR;
    }

    /** Whether n can match without consuming anything. It errs towards true. */
    static boolean nullable(Node n) {
        switch (n.kind) {
            case CHAR:
            case SET:
                return false;
            case CAT:
                for (Node sub : n.subs) {
                    if (!nullable(sub)) {
                        return false;
                    }
                }
                return true;
            case ALT:
                for (Node sub : n.subs) {
                    if (nullable(sub)) {
                        return true;
                    }
                }
                return false;
            case GROUP:
                return nullable(n.subs.get(0));
            case REPEAT:
                return n.min == 0 || nullable(n.subs.get(0));
            default:
                return true;
        }
    }

    /** Whether every way through n begins with ^. */
    static boolean startsAtBol(Node n) {
        switch (n.kind) {
            case BOL:
                return true;
            case CAT:
                return startsAtBol(n.subs.get(0));
            case ALT:
                for (Node sub : n.subs) {
                    if (!startsAtBol(sub)) {
                        return false;
                    }
                }
                return true;
            case GROUP:
                return startsAtBol(n.subs.get(0));
            case REPEAT:
                return n.min >= 1 && startsAtBol(n.subs.get(0));
            default:
                return false;
        }
    }

    /**
     * Appends, as inclusive pairs, the code points a match of n can start with. It returns 1 when n can match the
     * empty string, 0 when it cannot, and -1 when the set is not known.
     */
    static int firstChars(Node n, IntList pairs) {
        switch (n.kind) {
            case CHAR:
                pairs.add(n.r, n.r);
                return 0;
            case SET:
                pairs.addAll(n.set.ranges);
                return 0;
            case CAT:
                for (Node sub : n.subs) {
                    int e = firstChars(sub, pairs);
                    if (e < 0) {
                        return -1;
                    }
                    if (e == 0) {
                        return 0;
                    }
                }
                return 1;
            case ALT: {
                boolean empty = false;
                for (Node sub : n.subs) {
                    int e = firstChars(sub, pairs);
                    if (e < 0) {
                        return -1;
                    }
                    empty |= e == 1;
                }
                return empty ? 1 : 0;
            }
            case GROUP:
                return firstChars(n.subs.get(0), pairs);
            case REPEAT: {
                if (n.max == 0) {
                    return 1;
                }
                int e = firstChars(n.subs.get(0), pairs);
                if (e < 0) {
                    return -1;
                }
                return e == 1 || n.min == 0 ? 1 : 0;
            }
            case BACKREF:
                return -1;
            default:
                return 1;
        }
    }
}
