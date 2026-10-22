package io.github.corvusdotnet.regex.ecma262;

/** Why a pattern does not compile ({@code Error} of the Rust crate). */
public final class RegexException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    /** The kinds of failure. */
    public enum Kind {
        /** The pattern is not valid in the grammar of its flags (the {@code SyntaxError} of {@code new RegExp}). */
        INVALID,
        /** The flags are not valid: a letter that is not a flag, a flag given twice, or {@code u} with {@code v}. */
        FLAGS,
        /**
         * The pattern is over a limit of size ({@link #limit()}). It was not read to its end, so nothing is known of
         * whether it is valid.
         */
        TOO_LARGE,
    }

    private final Kind kind;
    private final Limit limit;
    private final int limitValue;

    private RegexException(Kind kind, String message, Limit limit, int limitValue) {
        super(message);
        this.kind = kind;
        this.limit = limit;
        this.limitValue = limitValue;
    }

    static RegexException invalid(String message) {
        return new RegexException(Kind.INVALID, message, null, 0);
    }

    static RegexException flags(String message) {
        return new RegexException(Kind.FLAGS, message, null, 0);
    }

    static RegexException tooLarge(Limit limit, int value) {
        String message;
        switch (limit) {
            case LENGTH:
                message = "the pattern is longer than " + value + " bytes";
                break;
            case NESTING:
                message = "the groups of the pattern nest more than " + value + " deep";
                break;
            default:
                message = "the pattern has a size of more than " + value + " bytes when it is read";
                break;
        }
        return new RegexException(Kind.TOO_LARGE, message, limit, value);
    }

    /** The kind of failure. */
    public Kind kind() {
        return kind;
    }

    /** The limit of size the pattern is over, or {@code null} when the kind is not {@link Kind#TOO_LARGE}. */
    public Limit limit() {
        return limit;
    }

    /** The value of the limit the pattern is over, or 0 when the kind is not {@link Kind#TOO_LARGE}. */
    public int limitValue() {
        return limitValue;
    }

    /** The string as Rust's Debug writes it, in double quotes with its control characters escaped. */
    static String quote(String s) {
        StringBuilder out = new StringBuilder(s.length() + 2).append('"');
        for (int k = 0; k < s.length(); k++) {
            escape(out, s.charAt(k), '"');
        }
        return out.append('"').toString();
    }

    /** The character as Rust's Debug writes it, in single quotes. */
    static String quoteChar(char c) {
        StringBuilder out = new StringBuilder(4).append('\'');
        escape(out, c, '\'');
        return out.append('\'').toString();
    }

    private static void escape(StringBuilder out, char c, char quote) {
        switch (c) {
            case '\t':
                out.append("\\t");
                break;
            case '\r':
                out.append("\\r");
                break;
            case '\n':
                out.append("\\n");
                break;
            case '\\':
                out.append("\\\\");
                break;
            case '\0':
                out.append("\\0");
                break;
            default:
                if (c == quote) {
                    out.append('\\').append(c);
                } else if (c < 0x20 || c == 0x7F) {
                    out.append("\\u{").append(Integer.toHexString(c)).append('}');
                } else {
                    out.append(c);
                }
        }
    }
}
