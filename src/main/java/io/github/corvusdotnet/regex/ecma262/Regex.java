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
 *
 * <p>A pattern has a match timeout, as a {@code Regex} of .NET's {@code System.Text.RegularExpressions} has: the time
 * each match may take, which brings a cap on the memory of its backtracking with it. Every member that matches throws a
 * {@link MatchLimitException} when a match reaches either. There is none unless {@link #compile(String, Flags,
 * Duration)} or {@link #setDefaultMatchTimeout(Duration)} gives one, so a program that matches patterns or texts from
 * outside should give its patterns one.
 *
 * <p>{@link #builder(String)} reads a pattern with the grammar of ECMAScript 2020 ({@link Edition#ES2020}), the
 * grammar JSON Schema 2020-12 names, and with the tables of Unicode 13, 17 or 18 ({@link UnicodeVersion}), each chosen
 * on its own. Both are read when the pattern is compiled, so a match costs the same whichever is chosen. Its
 * {@link Builder#tryBuild()} and {@link #tryCompile} give a {@link CompileResult} in place of throwing.
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
    /**
     * The time each match may take before it is abandoned, which brings the cap on the memory of its backtracking with
     * it, in nanoseconds; 0 for no limit.
     */
    final long timeoutNanos;
    /** The size the parser counted for the pattern. */
    final int size;
    /** The edition of the grammar and the version of Unicode, resolved, the pattern was read with. */
    private final Edition edition;
    private final UnicodeVersion unicodeVersion;
    /**
     * The automaton of the pattern over ASCII text ({@link Dfa}), built on the first match that wants it; {@link
     * #NO_DFA} when the pattern has none.
     */
    private volatile Object dfa;

    private static final Object NO_DFA = new Object();

    private Regex(String pattern, Flags flags, long timeoutNanos, Parser.Syntax syntax, Parser.Parsed parsed) {
        this.pattern = pattern;
        this.flags = flags;
        this.timeoutNanos = timeoutNanos;
        this.edition = syntax.edition;
        this.unicodeVersion = syntax.unicodeVersion.resolve();
        this.unicode = flags.unicode() || flags.unicodeSets();
        this.regular = !parsed.parser.needsBacktracking && Re2.cost(parsed.root) <= Re2.MAX_COST;
        this.size = parsed.parser.size;
        this.info = new GroupInfo(parsed.parser.groupCount(), parsed.parser.groupNames());
        if (regular && !shallow(pattern, flags.unicodeSets())) {
            // The automaton is built from the syntax tree, so that of a pattern that nests deeply is built here, on
            // the deep thread, and not on the stack of the thread that first matches.
            Dfa d = Dfa.build(parsed.root);
            this.dfa = d == null ? NO_DFA : d;
        }
        this.prog = Compiler.compileProgram(parsed.root, unicode, -1);
    }

    /** The automaton of the pattern, built the first time; {@code null} when it has none. */
    Dfa dfa() {
        Object d = dfa;
        if (d == null) {
            Dfa built = Dfa.build(Parser.parseWith(pattern, syntax()).root);
            d = built == null ? NO_DFA : built;
            dfa = d;
        }
        return d == NO_DFA ? null : (Dfa) d;
    }

    /**
     * The match timeout of every pattern compiled without one of its own, in nanoseconds; 0 for none. It starts from
     * the system property {@code corvus.regex.defaultMatchTimeout} (see {@link #setDefaultMatchTimeout(Duration)}).
     */
    private static volatile long defaultTimeoutNanos =
            timeoutFromProperty(System.getProperty("corvus.regex.defaultMatchTimeout"));

    /** The timeout the system property gives, in nanoseconds: milliseconds, or an ISO-8601 duration; 0 for none. */
    static long timeoutFromProperty(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        try {
            return nanos(value.chars().allMatch(Character::isDigit) ? Duration.ofMillis(Long.parseLong(value))
                    : Duration.parse(value));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "corvus.regex.defaultMatchTimeout is neither milliseconds nor an ISO-8601 duration: " + value, e);
        }
    }

    /**
     * Sets the match timeout of every pattern compiled from now on without one of its own: the time each match may take
     * before it is abandoned with a {@link MatchLimitException}, or {@link Duration#ZERO} (or {@code null}) for no
     * limit, which is the default. It is the process-wide default of .NET's {@code System.Text.RegularExpressions}
     * ({@code REGEX_DEFAULT_MATCH_TIMEOUT}), and a program that matches patterns or texts from outside should set it
     * when it starts, here or with the system property {@code corvus.regex.defaultMatchTimeout} (milliseconds, or an
     * ISO-8601 duration such as {@code PT1S}). A pattern already compiled keeps the timeout it was compiled with.
     */
    public static void setDefaultMatchTimeout(Duration timeout) {
        defaultTimeoutNanos = timeout == null ? 0 : nanos(timeout);
    }

    /** The match timeout a pattern compiled now without one of its own takes, {@link Duration#ZERO} for none. */
    public static Duration defaultMatchTimeout() {
        return Duration.ofNanos(defaultTimeoutNanos);
    }

    /** Compiles a pattern with no flags (see {@link #compile(String, Flags, Duration)}). */
    public static Regex compile(String pattern) {
        return compile(pattern, Flags.NONE);
    }

    /**
     * Compiles a pattern with the flags {@code new RegExp(pattern, flags)} takes, such as {@code ""}, {@code "u"} or
     * {@code "gimsuy"}, and the {@link #defaultMatchTimeout()} (see {@link #compile(String, Flags, Duration)}).
     */
    public static Regex compile(String pattern, String flags) {
        return compile(pattern, Flags.parse(flags));
    }

    /** {@link #compile(String, String)} with a match timeout (see {@link #compile(String, Flags, Duration)}). */
    public static Regex compile(String pattern, String flags, Duration matchTimeout) {
        return compile(pattern, Flags.parse(flags), matchTimeout);
    }

    /** {@link #compile(String, String)} with the flags already read. */
    public static Regex compile(String pattern, Flags flags) {
        return compile(pattern, flags, Duration.ofNanos(defaultTimeoutNanos));
    }

    /**
     * Compiles a pattern with a match timeout: the time each match may take before it is abandoned with a {@link
     * MatchLimitException} of kind {@link MatchLimitException.Kind#TIME}, {@link Duration#ZERO} (or {@code null}) for
     * no limit. A match with a timeout is also abandoned, with the kind {@link MatchLimitException.Kind#MEMORY}, when
     * its backtracking stack reaches {@link #MAX_STACK} entries. The clock is read every 4,096 steps of the
     * backtracking matcher, so a match can go past the timeout by the time those take. Every member that matches gives
     * each of its matches the timeout, the global searches, replace and split among them; the members that take a
     * {@code Duration} give one match a limit of its own in its place.
     *
     * <p>Without a timeout a match has no limit of time or of memory: a pattern and a text that come from outside can
     * then hold the thread, and take memory, for as long as the match takes. Give such a pattern a timeout, here or for
     * every pattern with {@link #setDefaultMatchTimeout(Duration)}.
     *
     * <p>It throws a {@link RegexException} of kind {@link RegexException.Kind#INVALID} for a pattern that is not valid
     * in the grammar of its flags, {@link RegexException.Kind#FLAGS} for flags that are not valid, and
     * {@link RegexException.Kind#TOO_LARGE} for a pattern over a limit of size, whether or not it is valid.
     */
    public static Regex compile(String pattern, Flags flags, Duration matchTimeout) {
        return build(pattern, flags, matchTimeout, Edition.CURRENT, UnicodeVersion.DEFAULT);
    }

    private static Regex build(String pattern, Flags flags, Duration matchTimeout, Edition edition,
            UnicodeVersion unicodeVersion) {
        Parser.Syntax syntax = syntaxOf(flags, edition, unicodeVersion);
        long timeout = matchTimeout == null ? 0 : nanos(matchTimeout);
        Regex re = onADeepStack(pattern, flags.unicodeSets(),
                () -> new Regex(pattern, flags, timeout, syntax, read(pattern, syntax)));
        if (re == null) {
            throw RegexException.tooLarge(Limit.NESTING, MAX_NESTING);
        }
        return re;
    }

    /**
     * The grammar of flags in an edition, under a version of Unicode. The grammar of ECMAScript 2020 has no d flag
     * (2022) and no v flag (2024), and refuses them as flags that are not valid.
     */
    private static Parser.Syntax syntaxOf(Flags flags, Edition edition, UnicodeVersion unicodeVersion) {
        if (edition == Edition.ES2020 && (flags.hasIndices() || flags.unicodeSets())) {
            throw RegexException.flags("ecmaregex: the flags " + RegexException.quote(flags.toString())
                    + " have d or v, which ECMAScript 2020 does not have");
        }
        return flags.syntax().with(edition, unicodeVersion);
    }

    /**
     * Compiles a pattern with the flags and a match timeout ({@link Duration#ZERO} or {@code null} for none), without
     * throwing: the {@link CompileResult} holds the {@code Regex} or why it was not compiled. A validator that compiles
     * patterns from outside takes this form, and gives each its own timeout.
     */
    public static CompileResult tryCompile(String pattern, String flags, Duration matchTimeout) {
        return builder(pattern).flags(flags).matchTimeout(matchTimeout).tryBuild();
    }

    /** A builder for the pattern, with no flags, for the settings beyond the flags (see {@link Builder}). */
    public static Builder builder(String pattern) {
        return new Builder(pattern);
    }

    /**
     * Compiles a pattern with settings beyond its flags: the match timeout, the edition of ECMA-262 whose grammar it is
     * read with and the version of Unicode whose tables it is read with, each independent of the others. What it does
     * not set is as {@link #compile(String, Flags)} has it: the {@link #defaultMatchTimeout()}, today's grammar and
     * V8's Unicode.
     *
     * <pre>{@code
     * // ECMAScript 2020 as its edition was published, with the Unicode of its year, as JSON Schema 2020-12 reads a
     * // pattern.
     * CompileResult result = Regex.builder("^\\p{Script=Greek}+$")
     *         .flags("u")
     *         .matchTimeout(Duration.ofSeconds(1))
     *         .edition(Edition.ES2020)
     *         .unicodeVersion(UnicodeVersion.V13)
     *         .tryBuild();
     * }</pre>
     */
    public static final class Builder {
        private final String pattern;
        private Flags flags = Flags.NONE;
        private String flagsText;
        private boolean hasTimeout;
        private Duration matchTimeout;
        private Edition edition = Edition.CURRENT;
        private UnicodeVersion unicodeVersion = UnicodeVersion.DEFAULT;

        private Builder(String pattern) {
            this.pattern = pattern;
        }

        /** The flags, as {@code new RegExp(pattern, flags)} takes them. Flags that are not valid fail the build. */
        public Builder flags(String flags) {
            this.flagsText = flags;
            return this;
        }

        /** The flags, already read. */
        public Builder flags(Flags flags) {
            this.flags = flags;
            this.flagsText = null;
            return this;
        }

        /**
         * The match timeout (see {@link Regex#compile(String, Flags, Duration)}): {@link Duration#ZERO} or {@code null}
         * for no limit. Without it the pattern takes the {@link Regex#defaultMatchTimeout()}.
         */
        public Builder matchTimeout(Duration matchTimeout) {
            this.hasTimeout = true;
            this.matchTimeout = matchTimeout;
            return this;
        }

        /** The edition of ECMA-262 whose grammar the pattern is read with. */
        public Builder edition(Edition edition) {
            this.edition = edition == null ? Edition.CURRENT : edition;
            return this;
        }

        /** The version of Unicode whose tables the pattern is read with. */
        public Builder unicodeVersion(UnicodeVersion unicodeVersion) {
            this.unicodeVersion = unicodeVersion == null ? UnicodeVersion.DEFAULT : unicodeVersion;
            return this;
        }

        /**
         * Compiles the pattern, with the exceptions of {@link Regex#compile(String, Flags, Duration)}. Under
         * {@link Edition#ES2020} the d and v flags are a {@link RegexException} of kind {@code FLAGS}.
         */
        public Regex build() {
            Flags f = flagsText == null ? flags : Flags.parse(flagsText);
            Duration timeout = hasTimeout ? matchTimeout : Duration.ofNanos(defaultTimeoutNanos);
            return Regex.build(pattern, f, timeout, edition, unicodeVersion);
        }

        /** Compiles the pattern as {@link #build()} does, giving why it was not compiled in place of throwing. */
        public CompileResult tryBuild() {
            try {
                return CompileResult.ok(build());
            } catch (RegexException e) {
                return CompileResult.failed(e);
            }
        }
    }

    /** Whether the pattern is valid with the flags, without compiling it. */
    public static boolean isValid(String pattern, String flags) {
        Flags f;
        try {
            f = Flags.parse(flags);
        } catch (RegexException e) {
            return false;
        }
        return isValid(pattern, f, Edition.CURRENT, UnicodeVersion.DEFAULT);
    }

    /**
     * Whether the pattern is valid with the flags in the grammar of an edition and under a version of Unicode, without
     * compiling it.
     */
    public static boolean isValid(String pattern, Flags flags, Edition edition, UnicodeVersion unicodeVersion) {
        Parser.Syntax syntax;
        try {
            syntax = syntaxOf(flags, edition, unicodeVersion);
        } catch (RegexException e) {
            return false;
        }
        Boolean valid = onADeepStack(pattern, flags.unicodeSets(), () -> {
            try {
                Parser.parseWith(pattern, syntax);
                return true;
            } catch (ParseFailure e) {
                return false;
            }
        });
        return valid != null && valid;
    }

    /**
     * Whether the pattern is valid in the grammar of the flags, decided with nothing allocated (see {@link
     * #isValidPattern(String, Flags, Edition, UnicodeVersion)}). Unlike {@link #isValid(String, String)}, it does not
     * apply the limits of size, so a pattern too large to compile is valid here.
     */
    public static boolean isValidPattern(String pattern, String flags) {
        int bits = Flags.bitsOf(flags);
        if (bits < 0) {
            return false;
        }
        return Validator.isValid(pattern, (bits & (Flags.U | Flags.V)) != 0, (bits & Flags.V) != 0, false,
                UnicodeVersion.DEFAULT.ucd());
    }

    /**
     * Whether the pattern is valid in the grammar of ECMA-262 for the flags, of an edition and under a version of
     * Unicode. It asks the grammar only: unlike {@link #isValid(String, Flags, Edition, UnicodeVersion)}, which reads
     * the pattern as compiling it would, it does not apply this engine's limits of size ({@link #MAX_PATTERN_LENGTH},
     * {@link #MAX_NESTING}, {@link #MAX_SIZE}), so a pattern too large to compile is valid here. It reads the pattern in
     * place with stacks of its own, so no pattern can overflow the stack of the thread, and once the thread's stacks
     * have grown to what its patterns need it allocates nothing. The u and v flags choose the grammar, and the others
     * do not change it. It is {@code false} too when the flags and the edition are not valid together.
     *
     * <pre>{@code
     * Flags u = Flags.parse("u");
     * // Valid in the grammar, though over the limits of size, so isValid says no.
     * String deep = "(".repeat(5000) + ")".repeat(5000);
     * assert Regex.isValidPattern(deep, u, Edition.CURRENT, UnicodeVersion.DEFAULT);
     * assert !Regex.isValid(deep, u, Edition.CURRENT, UnicodeVersion.DEFAULT);
     * // ECMAScript 2020 has no modifier groups.
     * assert !Regex.isValidPattern("(?i:a)", u, Edition.ES2020, UnicodeVersion.DEFAULT);
     * }</pre>
     */
    public static boolean isValidPattern(String pattern, Flags flags, Edition edition,
            UnicodeVersion unicodeVersion) {
        boolean es2020 = edition == Edition.ES2020;
        if (es2020 && (flags.hasIndices() || flags.unicodeSets())) {
            return false;
        }
        return Validator.isValid(pattern, flags.unicode() || flags.unicodeSets(), flags.unicodeSets(), es2020,
                unicodeVersion.ucd());
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
    static <T> T onADeepStack(String pattern, boolean unicodeSets, Supplier<T> work) {
        return shallow(pattern, unicodeSets) ? work.get() : deep(work);
    }

    /**
     * Whether a pattern opens few enough groups to be read on the stack of the thread that asks. With the v flag a
     * class may hold a class, and the parser goes a level deeper for each, so there a {@code [} counts as a {@code (}
     * does. In the other grammars a class holds no class and a {@code [} opens no level.
     */
    static boolean shallow(String pattern, boolean unicodeSets) {
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
                            Parser.parseWith(pattern, syntax()).root, unicode, info.groups);
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
    static long checked(long found) {
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

    /** The edition of ECMA-262 whose grammar the pattern was read with (see {@link Builder#edition}). */
    public Edition edition() {
        return edition;
    }

    /**
     * The version of Unicode whose tables the pattern was read with, resolved: never {@link UnicodeVersion#DEFAULT} or
     * {@link UnicodeVersion#LATEST} (see {@link Builder#unicodeVersion}).
     */
    public UnicodeVersion unicodeVersion() {
        return unicodeVersion;
    }

    /** The grammar the pattern was read with, to read it again. */
    private Parser.Syntax syntax() {
        return flags.syntax().with(edition, unicodeVersion);
    }

    /**
     * The time each match may take before it is abandoned, {@link Duration#ZERO} for no limit (see {@link
     * #compile(String, Flags, Duration)}).
     */
    public Duration matchTimeout() {
        return Duration.ofNanos(timeoutNanos);
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
        return Text16.test(this, text, 0, timeoutNanos);
    }

    /**
     * {@link #isMatch(String)} from the position {@code start}, as {@code RegExp.prototype.test} with that lastIndex.
     */
    public boolean isMatch(String text, int start) {
        checkStart(start);
        return Text16.test(this, text, start, timeoutNanos);
    }

    /**
     * {@link #isMatch(String)} with a limit on the time the match may take in place of the pattern's timeout (none when
     * it is zero). The limit brings the cap on the memory of the backtracking with it, as a timeout does (see
     * {@link #compile(String, Flags, Duration)}).
     */
    public boolean isMatch(String text, Duration limit) {
        return Text16.test(this, text, 0, nanos(limit));
    }

    /**
     * The first match in the text (at its start alone with the y flag): {@code exec} with lastIndex 0, or {@code null}.
     */
    public Match find(String text) {
        return find(text, 0);
    }

    /** The first match at or after {@code start}: {@code exec} with lastIndex set to it, or {@code null}. */
    public Match find(String text, int start) {
        checkStart(start);
        long found = Text16.exec(this, text, start, flags.sticky(), timeoutNanos, null);
        return found < 0 ? null : new Match(text, null, from(found), to(found));
    }

    /**
     * {@link #find(String, int)} with a limit on the time the match may take (see {@link #isMatch(String, Duration)}).
     */
    public Match find(String text, int start, Duration limit) {
        checkStart(start);
        long found = (Text16.exec(this, text, start, flags.sticky(), nanos(limit), null));
        return found < 0 ? null : new Match(text, null, from(found), to(found));
    }

    /**
     * Every match in the text, as a global search finds them ({@code matchAll}): after an empty match the search goes
     * on one character later. With the y flag each match must start where the one before it ended. A match that reaches
     * the pattern's timeout throws {@link MatchLimitException} from the iterator, and ends the search.
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

    /**
     * {@link #captures(String, int)} with a limit on the time the match may take (see {@link #isMatch(String,
     * Duration)}).
     */
    public Captures captures(String text, int start, Duration limit) {
        checkStart(start);
        Captures c = new Captures(info);
        if ((Text16.exec(this, text, start, flags.sticky(), nanos(limit), c.slots)) < 0) {
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
        if (Text16.exec(this, text, start, flags.sticky(), timeoutNanos, into.slots) < 0) {
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
        return Text8.test(this, text.bytes, 0, timeoutNanos);
    }

    /** {@link #isMatch(String, int)} over UTF-8 text; {@code start} is a byte offset. */
    public boolean isMatch(Utf8Text text, int start) {
        checkStart(start);
        return Text8.test(this, text.bytes, start, timeoutNanos);
    }

    /** {@link #isMatch(String, Duration)} over UTF-8 text. */
    public boolean isMatch(Utf8Text text, Duration limit) {
        return Text8.test(this, text.bytes, 0, nanos(limit));
    }

    /** {@link #find(String)} over UTF-8 text. */
    public Match find(Utf8Text text) {
        return find(text, 0);
    }

    /** {@link #find(String, int)} over UTF-8 text; {@code start} is a byte offset. */
    public Match find(Utf8Text text, int start) {
        checkStart(start);
        long found = Text8.exec(this, text.bytes, start, flags.sticky(), timeoutNanos, null);
        return found < 0 ? null : new Match(null, text.bytes, from(found), to(found));
    }

    /** {@link #find(String, int, Duration)} over UTF-8 text. */
    public Match find(Utf8Text text, int start, Duration limit) {
        checkStart(start);
        long found = (Text8.exec(this, text.bytes, start, flags.sticky(), nanos(limit), null));
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

    /** {@link #captures(String, int, Duration)} over UTF-8 text. */
    public Captures captures(Utf8Text text, int start, Duration limit) {
        checkStart(start);
        Captures c = new Captures(info);
        if ((Text8.exec(this, text.bytes, start, flags.sticky(), nanos(limit), c.slots)) < 0) {
            return null;
        }
        c.text8 = text.bytes;
        return c;
    }

    /** {@link #captures(Captures, String, int)} over UTF-8 text. */
    public boolean captures(Captures into, Utf8Text text, int start) {
        checkStart(start);
        checkCaptures(into);
        if (Text8.exec(this, text.bytes, start, flags.sticky(), timeoutNanos, into.slots) < 0) {
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

    /**
     * {@link #replace(String, String)} over UTF-8 text. A half character at either end of a piece is written as U+FFFD.
     */
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

    /** {@link #isMatch(String, Duration)} over UTF-8 bytes. */
    public boolean isMatch(byte[] text, Duration limit) {
        return isMatch(Utf8Text.of(text), limit);
    }

    /** {@link #find(String)} over UTF-8 bytes. */
    public Match find(byte[] text) {
        return find(Utf8Text.of(text), 0);
    }

    /** {@link #find(String, int)} over UTF-8 bytes. */
    public Match find(byte[] text, int start) {
        return find(Utf8Text.of(text), start);
    }

    /** {@link #find(String, int, Duration)} over UTF-8 bytes. */
    public Match find(byte[] text, int start, Duration limit) {
        return find(Utf8Text.of(text), start, limit);
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

    /** {@link #captures(String, int, Duration)} over UTF-8 bytes. */
    public Captures captures(byte[] text, int start, Duration limit) {
        return captures(Utf8Text.of(text), start, limit);
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
            return text16 != null ? Text16.exec(Regex.this, text16, at, flags.sticky(), timeoutNanos, slots)
                    : Text8.exec(Regex.this, text8, at, flags.sticky(), timeoutNanos, slots);
        }

        /** The next item from {@code at}, or {@code null}. */
        abstract T step(int at);

        abstract long span(T item);

        @Override
        public final boolean hasNext() {
            if (next == null && !done) {
                T item;
                try {
                    item = step(at);
                } catch (MatchLimitException e) {
                    done = true;
                    throw e;
                }
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
