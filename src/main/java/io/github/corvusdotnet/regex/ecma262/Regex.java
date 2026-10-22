package io.github.corvusdotnet.regex.ecma262;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A compiled ECMA-262 regular expression (the {@code RegExp} of JavaScript). It is immutable once compiled, and safe
 * to share between threads.
 *
 * <p>A pattern is read as {@code new RegExp(pattern, flags)} reads it: with the u or v flag by the grammar of that flag
 * and matched by code point; with neither by the Annex B grammar and matched by UTF-16 code unit. The i, m and s flags
 * are the modifiers in force at the start of the pattern, which a modifier group ({@code (?i:...)}) changes for part
 * of it.
 *
 * <p>Every method takes its text in one of three forms: a {@code String}, which is UTF-16 and may hold lone
 * surrogates; a {@code byte[]} of UTF-8, which is checked on each call; and a {@link Utf8Text}, which was checked once
 * when it was made. Positions are in the units of the text: UTF-16 code units, or bytes of UTF-8. Nothing is
 * transcoded. A match allocates nothing once the thread's working memory has grown to what the pattern and the text
 * need; {@link #captures(Captures, String, int)} reads the groups of a match into a {@link Captures} made once.
 */
public final class Regex {
    /** The longest pattern, in bytes of UTF-8 (a lone surrogate counts three). */
    public static final int MAX_PATTERN_LENGTH = Parser.MAX_PATTERN_LENGTH;
    /** The deepest nesting of groups, lookarounds and (with the v flag) classes. */
    public static final int MAX_NESTING = Parser.MAX_NESTING;
    /** The largest size of a pattern as the parser counts it while it reads it. */
    public static final int MAX_SIZE = Parser.MAX_SIZE;
    /** The number of entries the backtracking stack of a match with a limit may hold. */
    public static final int MAX_STACK = State.MAX_STACK;

    /**
     * The number of groups (and with the v flag, classes) a pattern may open and still be read on the stack of the
     * thread that asks.
     */
    private static final int SHALLOW = 32;
    /**
     * The stack of the thread that reads a pattern with more groups than that. The parser and the compiler go one level
     * deeper for each level of groups, and {@link #MAX_NESTING} allows a thousand levels, which is more than the stack
     * of a thread can be relied on to hold. Only the pages the work touches are used.
     */
    private static final long DEEP_STACK = 64L << 20;

    private final String pattern;
    final Flags flags;
    /** The program for a match that needs no groups: it records only the groups some backreference names. */
    final Program prog;
    /** The program that records every group, built the first time a match asks for the groups. */
    private volatile Program caps;
    final GroupInfo info;
    /** The pattern is matched by code point (the u or v flag). */
    final boolean unicode;
    /** The pattern needs no backtracking and is small enough to be tried for an automaton. */
    final boolean regular;

    private Regex(String pattern, Flags flags, Parser.Parsed parsed) {
        this.pattern = pattern;
        this.flags = flags;
        this.unicode = flags.unicode() || flags.unicodeSets();
        this.regular = !parsed.parser.needsBacktracking && Re2.cost(parsed.root) <= Re2.MAX_COST;
        this.info = new GroupInfo(parsed.parser.groupCount(), parsed.parser.groupNames());
        this.prog = Compiler.compileProgram(parsed.root, unicode, -1);
    }

    /** Compiles a pattern with no flags (see {@link #compile(String, Flags)}). */
    public static Regex compile(String pattern) {
        return compile(pattern, Flags.NONE);
    }

    /**
     * Compiles a pattern with the flags {@code new RegExp(pattern, flags)} takes, such as {@code ""}, {@code "u"} or
     * {@code "gimsuy"} (see {@link #compile(String, Flags)}).
     */
    public static Regex compile(String pattern, String flags) {
        return compile(pattern, Flags.parse(flags));
    }

    /**
     * Compiles a pattern. It throws a {@link RegexException} of kind {@link RegexException.Kind#INVALID} for a pattern
     * that is not valid in the grammar of its flags, {@link RegexException.Kind#FLAGS} for flags that are not valid,
     * and {@link RegexException.Kind#TOO_LARGE} for a pattern over a limit of size, whether or not it is valid.
     */
    public static Regex compile(String pattern, Flags flags) {
        Parser.Syntax syntax = flags.syntax();
        Regex re = onADeepStack(pattern, flags.unicodeSets(), () -> new Regex(pattern, flags, read(pattern, syntax)));
        if (re == null) {
            throw RegexException.tooLarge(Limit.NESTING, MAX_NESTING);
        }
        return re;
    }

    /** Whether the pattern is valid with the flags, without compiling it. */
    public static boolean isValid(String pattern, String flags) {
        Flags f;
        try {
            f = Flags.parse(flags);
        } catch (RegexException e) {
            return false;
        }
        Boolean valid = onADeepStack(pattern, f.unicodeSets(), () -> {
            try {
                Parser.parseWith(pattern, f.syntax());
                return true;
            } catch (ParseFailure e) {
                return false;
            }
        });
        return valid != null && valid;
    }

    private static Parser.Parsed read(String pattern, Parser.Syntax syntax) {
        try {
            return Parser.parseWith(pattern, syntax);
        } catch (ParseFailure e) {
            if (e.isTooLarge()) {
                throw RegexException.tooLarge(e.limit, e.value);
            }
            throw RegexException.invalid(
                    "ecmaregex: " + e.reason + " at offset " + e.pos + " in " + RegexException.quote(pattern));
        }
    }

    /**
     * Runs the reading of a pattern, with everything that walks its syntax tree, where the depth of the tree cannot
     * overflow the stack. A pattern that opens few groups is read where it is; any other on a thread of its own with a
     * stack for the deepest nesting the limits allow. {@code null} when that thread could not be started.
     */
    private static <T> T onADeepStack(String pattern, boolean unicodeSets, Supplier<T> work) {
        return shallow(pattern, unicodeSets) ? work.get() : deep(work);
    }

    /**
     * Whether a pattern opens few enough groups to be read on the stack of the thread that asks. With the v flag a
     * class may hold a class, and the parser goes a level deeper for each, so there a {@code [} counts as a {@code (}
     * does. In the other grammars a class holds no class and a {@code [} opens no level.
     */
    private static boolean shallow(String pattern, boolean unicodeSets) {
        int n = 0;
        for (int k = 0; k < pattern.length(); k++) {
            char c = pattern.charAt(k);
            if (c == '(' || unicodeSets && c == '[') {
                if (++n > SHALLOW) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Runs the work on a thread with a stack for the deepest nesting; {@code null} when no thread could be started. */
    private static <T> T deep(Supplier<T> work) {
        Object[] result = new Object[1];
        Throwable[] failure = new Throwable[1];
        Thread thread = new Thread(null, () -> {
            try {
                result[0] = work.get();
            } catch (Throwable e) {
                failure[0] = e;
            }
        }, "corvus-regex-deep-pattern", DEEP_STACK);
        try {
            thread.start();
        } catch (OutOfMemoryError e) {
            return null;
        }
        boolean interrupted = false;
        while (true) {
            try {
                thread.join();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        if (failure[0] instanceof RuntimeException) {
            throw (RuntimeException) failure[0];
        }
        if (failure[0] instanceof Error) {
            throw (Error) failure[0];
        }
        @SuppressWarnings("unchecked")
        T t = (T) result[0];
        return t;
    }

    /** The program that records every group. */
    Program capsProgram() {
        if (prog.groups == info.groups) {
            return prog;
        }
        Program c = caps;
        if (c == null) {
            synchronized (this) {
                c = caps;
                if (c == null) {
                    Supplier<Program> build = () -> Compiler.compileProgram(
                            Parser.parseWith(pattern, flags.syntax()).root, unicode, info.groups);
                    c = shallow(pattern, flags.unicodeSets()) ? build.get() : deep(build);
                    if (c == null) {
                        throw new IllegalStateException("no thread could be started to read a deep pattern");
                    }
                    caps = c;
                }
            }
        }
        return c;
    }

    static int from(long span) {
        return (int) (span >>> 32);
    }

    static int to(long span) {
        return (int) span;
    }

    /** A limit in nanoseconds, 0 for none, saturating. */
    private static long nanos(Duration limit) {
        if (limit.isNegative() || limit.isZero()) {
            return 0;
        }
        try {
            return limit.toNanos();
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    /** The span, or -1 for none; it throws for a match that reached its limit. */
    private static long checked(long found) {
        if (found == Matcher16.STOPPED_TIME) {
            throw new MatchLimitException(MatchLimitException.Kind.TIME);
        }
        if (found == Matcher16.STOPPED_MEMORY) {
            throw new MatchLimitException(MatchLimitException.Kind.MEMORY);
        }
        return found;
    }

    private static void checkStart(int start) {
        if (start < 0) {
            throw new IndexOutOfBoundsException("the start " + start + " is negative");
        }
    }

    /** The source of the pattern. */
    public String pattern() {
        return pattern;
    }

    /** The flags of the pattern. */
    public Flags flags() {
        return flags;
    }

    /** The number of groups of a match: one for the whole match, and one for each capturing group. */
    public int capturesLength() {
        return info.groups + 1;
    }

    /** The names of the groups, in the order of the first group of each name. */
    public List<String> groupNames() {
        List<String> out = new ArrayList<>(info.names.length);
        for (GroupInfo.Name n : info.names) {
            out.add(n.name);
        }
        return out;
    }

    /** A place to read the groups of a match into (see {@link #captures(Captures, String, int)}). */
    public Captures newCaptures() {
        return new Captures(info);
    }

    /** The pattern as {@code RegExp.prototype.toString} writes it, with its flags: {@code /pattern/flags}. */
    @Override
    public String toString() {
        return "/" + pattern + "/" + flags;
    }

    // -----------------------------------------------------------------------------------------------------------------
    // String (UTF-16)

    /**
     * Whether the pattern matches anywhere in the text (at its start alone with the y flag), with no limit on the time
     * or the memory the match takes.
     */
    public boolean isMatch(String text) {
        return Text16.exec(this, text, 0, flags.sticky(), 0, null) >= 0;
    }

    /** {@link #isMatch(String)} from the position {@code start}, as {@code RegExp.prototype.test} with that lastIndex. */
    public boolean isMatch(String text, int start) {
        checkStart(start);
        return Text16.exec(this, text, start, flags.sticky(), 0, null) >= 0;
    }

    /**
     * {@link #isMatch(String)} with a limit on the time the match may take (none when it is zero). A match that reaches
     * the limit is abandoned with a {@link MatchLimitException} of kind {@link MatchLimitException.Kind#TIME}; so is a
     * match whose backtracking stack reaches {@link #MAX_STACK} entries, of kind
     * {@link MatchLimitException.Kind#MEMORY}. The clock is read every 4,096 steps of the backtracking matcher, so a
     * match can go past the limit by the time those take.
     */
    public boolean tryIsMatch(String text, Duration limit) {
        return checked(Text16.exec(this, text, 0, flags.sticky(), nanos(limit), null)) >= 0;
    }

    /** The first match in the text (at its start alone with the y flag): {@code exec} with lastIndex 0, or {@code null}. */
    public Match find(String text) {
        return find(text, 0);
    }

    /** The first match at or after {@code start}: {@code exec} with lastIndex set to it, or {@code null}. */
    public Match find(String text, int start) {
        checkStart(start);
        long found = Text16.exec(this, text, start, flags.sticky(), 0, null);
        return found < 0 ? null : new Match(text, null, from(found), to(found));
    }

    /** {@link #find(String, int)} with a limit on the time the match may take (see {@link #tryIsMatch(String, Duration)}). */
    public Match tryFind(String text, int start, Duration limit) {
        checkStart(start);
        long found = checked(Text16.exec(this, text, start, flags.sticky(), nanos(limit), null));
        return found < 0 ? null : new Match(text, null, from(found), to(found));
    }

    /**
     * Every match in the text, as a global search finds them ({@code matchAll}): after an empty match the search goes
     * on one character later. With the y flag each match must start where the one before it ended.
     */
    public Iterable<Match> findAll(String text) {
        return () -> new MatchIterator(text, null);
    }

    /** A global search of the text that allocates nothing for each match (see {@link Matches}). */
    public Matches matches(String text) {
        return new Matches(this).reset(text);
    }

    /** The first match and what each group captured ({@code exec}), or {@code null}. */
    public Captures captures(String text) {
        return captures(text, 0);
    }

    /** {@link #captures(String)} from the position {@code start}. */
    public Captures captures(String text, int start) {
        checkStart(start);
        Captures c = new Captures(info);
        return captures(c, text, start) ? c : null;
    }

    /** {@link #captures(String, int)} with a limit on the time the match may take (see {@link #tryIsMatch(String, Duration)}). */
    public Captures tryCaptures(String text, int start, Duration limit) {
        checkStart(start);
        Captures c = new Captures(info);
        if (checked(Text16.exec(this, text, start, flags.sticky(), nanos(limit), c.slots)) < 0) {
            return null;
        }
        c.text16 = text;
        return c;
    }

    /**
     * The first match at or after {@code start}, with what each group captured written into {@code into} (made by
     * {@link #newCaptures()} for this pattern), without allocating: whether there is one.
     */
    public boolean captures(Captures into, String text, int start) {
        checkStart(start);
        checkCaptures(into);
        if (Text16.exec(this, text, start, flags.sticky(), 0, into.slots) < 0) {
            return false;
        }
        into.text16 = text;
        into.text8 = null;
        return true;
    }

    /** The captures of every match, as {@link #findAll(String)} finds them. */
    public Iterable<Captures> capturesAll(String text) {
        return () -> new CapturesIterator(text, null);
    }

    /**
     * The text with the first match replaced, or every match with the g flag, as {@code String.prototype.replace}
     * replaces them: the replacement is a template in which {@code $&} is the match, {@code $`} and {@code $'} the text
     * before and after it, {@code $1} to {@code $99} a group, {@code $<name>} a named group, and {@code $$} a dollar
     * sign ({@code GetSubstitution}).
     */
    public String replace(String text, String replacement) {
        return Text16.replace(this, text, replacement, flags.global());
    }

    /** The text with every match replaced ({@code String.prototype.replaceAll}), whether or not the g flag is set. */
    public String replaceAll(String text, String replacement) {
        return Text16.replace(this, text, replacement, true);
    }

    /** The text with the first match replaced, or every match with the g flag, by what the function returns for it. */
    public String replace(String text, Function<Captures, String> replacement) {
        return Text16.replaceWith(this, text, flags.global(), replacement);
    }

    /**
     * The text split around the matches, as {@code String.prototype.split} splits it: what each group captured is
     * spliced in after the piece before it, {@code null} for a group that did not take part, and an empty match at the
     * start or the end of the text splits nothing.
     */
    public List<String> split(String text) {
        return Text16.split(this, text, 0xFFFFFFFFL);
    }

    /** {@link #split(String)} with at most {@code limit} items, as {@code split(separator, limit)}. */
    public List<String> split(String text, int limit) {
        return Text16.split(this, text, Integer.toUnsignedLong(limit));
    }

    // -----------------------------------------------------------------------------------------------------------------
    // UTF-8, checked once (Utf8Text)

    /** {@link #isMatch(String)} over UTF-8 text. */
    public boolean isMatch(Utf8Text text) {
        return Text8.exec(this, text.bytes, 0, flags.sticky(), 0, null) >= 0;
    }

    /** {@link #isMatch(String, int)} over UTF-8 text; {@code start} is a byte offset. */
    public boolean isMatch(Utf8Text text, int start) {
        checkStart(start);
        return Text8.exec(this, text.bytes, start, flags.sticky(), 0, null) >= 0;
    }

    /** {@link #tryIsMatch(String, Duration)} over UTF-8 text. */
    public boolean tryIsMatch(Utf8Text text, Duration limit) {
        return checked(Text8.exec(this, text.bytes, 0, flags.sticky(), nanos(limit), null)) >= 0;
    }

    /** {@link #find(String)} over UTF-8 text. */
    public Match find(Utf8Text text) {
        return find(text, 0);
    }

    /** {@link #find(String, int)} over UTF-8 text; {@code start} is a byte offset. */
    public Match find(Utf8Text text, int start) {
        checkStart(start);
        long found = Text8.exec(this, text.bytes, start, flags.sticky(), 0, null);
        return found < 0 ? null : new Match(null, text.bytes, from(found), to(found));
    }

    /** {@link #tryFind(String, int, Duration)} over UTF-8 text. */
    public Match tryFind(Utf8Text text, int start, Duration limit) {
        checkStart(start);
        long found = checked(Text8.exec(this, text.bytes, start, flags.sticky(), nanos(limit), null));
        return found < 0 ? null : new Match(null, text.bytes, from(found), to(found));
    }

    /** {@link #findAll(String)} over UTF-8 text. */
    public Iterable<Match> findAll(Utf8Text text) {
        return () -> new MatchIterator(null, text.bytes);
    }

    /** {@link #matches(String)} over UTF-8 text. */
    public Matches matches(Utf8Text text) {
        return new Matches(this).reset(text);
    }

    /** {@link #captures(String)} over UTF-8 text. */
    public Captures captures(Utf8Text text) {
        return captures(text, 0);
    }

    /** {@link #captures(String, int)} over UTF-8 text. */
    public Captures captures(Utf8Text text, int start) {
        checkStart(start);
        Captures c = new Captures(info);
        return captures(c, text, start) ? c : null;
    }

    /** {@link #tryCaptures(String, int, Duration)} over UTF-8 text. */
    public Captures tryCaptures(Utf8Text text, int start, Duration limit) {
        checkStart(start);
        Captures c = new Captures(info);
        if (checked(Text8.exec(this, text.bytes, start, flags.sticky(), nanos(limit), c.slots)) < 0) {
            return null;
        }
        c.text8 = text.bytes;
        return c;
    }

    /** {@link #captures(Captures, String, int)} over UTF-8 text. */
    public boolean captures(Captures into, Utf8Text text, int start) {
        checkStart(start);
        checkCaptures(into);
        if (Text8.exec(this, text.bytes, start, flags.sticky(), 0, into.slots) < 0) {
            return false;
        }
        into.text8 = text.bytes;
        into.text16 = null;
        return true;
    }

    /** {@link #capturesAll(String)} over UTF-8 text. */
    public Iterable<Captures> capturesAll(Utf8Text text) {
        return () -> new CapturesIterator(null, text.bytes);
    }

    /** {@link #replace(String, String)} over UTF-8 text. A half character at either end of a piece is written as U+FFFD. */
    public Utf8Text replace(Utf8Text text, String replacement) {
        byte[] out = Text8.replace(this, text.bytes, Text8.encode(replacement), flags.global());
        return out == text.bytes ? text : Utf8Text.wrap(out);
    }

    /** {@link #replaceAll(String, String)} over UTF-8 text. */
    public Utf8Text replaceAll(Utf8Text text, String replacement) {
        byte[] out = Text8.replace(this, text.bytes, Text8.encode(replacement), true);
        return out == text.bytes ? text : Utf8Text.wrap(out);
    }

    /** {@link #replace(String, Function)} over UTF-8 text. */
    public Utf8Text replace(Utf8Text text, Function<Captures, String> replacement) {
        byte[] out = Text8.replaceWith(this, text.bytes, flags.global(), replacement);
        return out == text.bytes ? text : Utf8Text.wrap(out);
    }

    /** {@link #split(String)} over UTF-8 text. */
    public List<Utf8Text> split(Utf8Text text) {
        return wrap(Text8.split(this, text.bytes, 0xFFFFFFFFL));
    }

    /** {@link #split(String, int)} over UTF-8 text. */
    public List<Utf8Text> split(Utf8Text text, int limit) {
        return wrap(Text8.split(this, text.bytes, Integer.toUnsignedLong(limit)));
    }

    private static List<Utf8Text> wrap(List<byte[]> pieces) {
        List<Utf8Text> out = new ArrayList<>(pieces.size());
        for (byte[] p : pieces) {
            out.add(p == null ? null : Utf8Text.wrap(p));
        }
        return out;
    }

    // -----------------------------------------------------------------------------------------------------------------
    // UTF-8, checked on each call (byte[])

    /** {@link #isMatch(String)} over UTF-8 bytes, which are checked first (see {@link Utf8Text}). */
    public boolean isMatch(byte[] text) {
        return isMatch(Utf8Text.of(text));
    }

    /** {@link #isMatch(String, int)} over UTF-8 bytes. */
    public boolean isMatch(byte[] text, int start) {
        return isMatch(Utf8Text.of(text), start);
    }

    /** {@link #tryIsMatch(String, Duration)} over UTF-8 bytes. */
    public boolean tryIsMatch(byte[] text, Duration limit) {
        return tryIsMatch(Utf8Text.of(text), limit);
    }

    /** {@link #find(String)} over UTF-8 bytes. */
    public Match find(byte[] text) {
        return find(Utf8Text.of(text), 0);
    }

    /** {@link #find(String, int)} over UTF-8 bytes. */
    public Match find(byte[] text, int start) {
        return find(Utf8Text.of(text), start);
    }

    /** {@link #tryFind(String, int, Duration)} over UTF-8 bytes. */
    public Match tryFind(byte[] text, int start, Duration limit) {
        return tryFind(Utf8Text.of(text), start, limit);
    }

    /** {@link #findAll(String)} over UTF-8 bytes. */
    public Iterable<Match> findAll(byte[] text) {
        return findAll(Utf8Text.of(text));
    }

    /** {@link #matches(String)} over UTF-8 bytes. */
    public Matches matches(byte[] text) {
        return new Matches(this).reset(text);
    }

    /** {@link #captures(String)} over UTF-8 bytes. */
    public Captures captures(byte[] text) {
        return captures(Utf8Text.of(text), 0);
    }

    /** {@link #captures(String, int)} over UTF-8 bytes. */
    public Captures captures(byte[] text, int start) {
        return captures(Utf8Text.of(text), start);
    }

    /** {@link #tryCaptures(String, int, Duration)} over UTF-8 bytes. */
    public Captures tryCaptures(byte[] text, int start, Duration limit) {
        return tryCaptures(Utf8Text.of(text), start, limit);
    }

    /** {@link #captures(Captures, String, int)} over UTF-8 bytes. */
    public boolean captures(Captures into, byte[] text, int start) {
        return captures(into, Utf8Text.of(text), start);
    }

    /** {@link #capturesAll(String)} over UTF-8 bytes. */
    public Iterable<Captures> capturesAll(byte[] text) {
        return capturesAll(Utf8Text.of(text));
    }

    /** {@link #replace(String, String)} over UTF-8 bytes. The result is a new array even when nothing matched. */
    public byte[] replace(byte[] text, String replacement) {
        Text8.check(text);
        byte[] out = Text8.replace(this, text, Text8.encode(replacement), flags.global());
        return out == text ? text.clone() : out;
    }

    /** {@link #replaceAll(String, String)} over UTF-8 bytes. The result is a new array even when nothing matched. */
    public byte[] replaceAll(byte[] text, String replacement) {
        Text8.check(text);
        byte[] out = Text8.replace(this, text, Text8.encode(replacement), true);
        return out == text ? text.clone() : out;
    }

    /** {@link #replace(String, Function)} over UTF-8 bytes. The result is a new array even when nothing matched. */
    public byte[] replace(byte[] text, Function<Captures, String> replacement) {
        Text8.check(text);
        byte[] out = Text8.replaceWith(this, text, flags.global(), replacement);
        return out == text ? text.clone() : out;
    }

    /** {@link #split(String)} over UTF-8 bytes. */
    public List<byte[]> split(byte[] text) {
        Text8.check(text);
        return Text8.split(this, text, 0xFFFFFFFFL);
    }

    /** {@link #split(String, int)} over UTF-8 bytes. */
    public List<byte[]> split(byte[] text, int limit) {
        Text8.check(text);
        return Text8.split(this, text, Integer.toUnsignedLong(limit));
    }

    private void checkCaptures(Captures into) {
        if (into.info != info) {
            throw new IllegalArgumentException("the captures were made for another pattern");
        }
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Global searches

    /** A global search: the text in one of its encodings, where the next search starts, and whether it has ended. */
    private abstract class GlobalSearch<T> implements Iterator<T> {
        final String text16;
        final byte[] text8;
        private int at;
        private boolean done;
        private T next;

        GlobalSearch(String text16, byte[] text8) {
            this.text16 = text16;
            this.text8 = text8;
        }

        /** The next match from {@code at} into {@code slots} when it is given: its span, or negative for none. */
        final long exec(int at, int[] slots) {
            return text16 != null ? Text16.exec(Regex.this, text16, at, flags.sticky(), 0, slots)
                    : Text8.exec(Regex.this, text8, at, flags.sticky(), 0, slots);
        }

        /** The next item from {@code at}, or {@code null}; {@code span} is set to where it is. */
        abstract T step(int at);

        abstract long span(T item);

        @Override
        public final boolean hasNext() {
            if (next == null && !done) {
                T item = step(at);
                if (item == null) {
                    done = true;
                } else {
                    long s = span(item);
                    int from = from(s);
                    int to = to(s);
                    at = text16 != null ? Text16.nextStart(Regex.this, text16, from, to)
                            : Text8.nextStart(Regex.this, text8, from, to);
                    next = item;
                }
            }
            return next != null;
        }

        @Override
        public final T next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            T item = next;
            next = null;
            return item;
        }
    }

    private final class MatchIterator extends GlobalSearch<Match> {
        MatchIterator(String text16, byte[] text8) {
            super(text16, text8);
        }

        @Override
        Match step(int at) {
            long found = exec(at, null);
            return found < 0 ? null : new Match(text16, text8, from(found), to(found));
        }

        @Override
        long span(Match m) {
            return Matcher16.span(m.start(), m.end());
        }
    }

    private final class CapturesIterator extends GlobalSearch<Captures> {
        CapturesIterator(String text16, byte[] text8) {
            super(text16, text8);
        }

        @Override
        Captures step(int at) {
            Captures c = new Captures(info);
            if (exec(at, c.slots) < 0) {
                return null;
            }
            c.text16 = text16;
            c.text8 = text8;
            return c;
        }

        @Override
        long span(Captures c) {
            return Matcher16.span(c.slots[0], c.slots[1]);
        }
    }
}
