package io.github.corvusdotnet.regex.ecma262;

/**
 * A match of a pattern given a limit (see {@link Regex#compile(String, Flags, java.time.Duration)}) was abandoned
 * before it found its answer ({@code MatchError} of the Rust crate).
 */
public final class MatchLimitException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** Which limit the match reached. */
    public enum Kind {
        /** The match ran for longer than its limit. */
        TIME,
        /** The match needed more backtracking memory than its limit: its stack reached {@link Regex#MAX_STACK}. */
        MEMORY,
    }

    private final Kind kind;

    MatchLimitException(Kind kind) {
        super(kind == Kind.TIME ? "ecmaregex: the match ran for longer than its limit"
                : "ecmaregex: the match needed more backtracking memory than its limit", null, false, false);
        this.kind = kind;
    }

    /** Which limit the match reached. */
    public Kind kind() {
        return kind;
    }
}
