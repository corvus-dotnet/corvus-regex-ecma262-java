package io.github.corvusdotnet.regex.ecma262;

/**
 * Why a pattern was not read: it is over a limit of size, or it is not valid under the chosen grammar (the
 * {@code ParseError} of {@code parse.rs}). Internal: the public API reports it as a {@link RegexException}.
 */
final class ParseFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** The limit a pattern is over, or {@code null} for a pattern that is not valid. */
    final Limit limit;
    final int value;
    /** The reason, and the offset in the pattern's characters, for a pattern that is not valid. */
    final String reason;
    final int pos;

    private ParseFailure(Limit limit, int value, String reason, int pos) {
        super(reason == null ? limit.label() : reason, null, false, false);
        this.limit = limit;
        this.value = value;
        this.reason = reason;
        this.pos = pos;
    }

    static ParseFailure tooLarge(Limit limit, int value) {
        return new ParseFailure(limit, value, null, 0);
    }

    static ParseFailure syntax(String reason, int pos) {
        return new ParseFailure(null, 0, reason, pos);
    }

    boolean isTooLarge() {
        return limit != null;
    }
}
