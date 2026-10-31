package io.github.corvusdotnet.regex.ecma262;

/** The flags of a pattern, as {@code new RegExp(pattern, flags)} takes them. Immutable. */
public final class Flags {
    /** No flags. */
    public static final Flags NONE = new Flags(0);

    private static final String LETTERS = "dgimsuvy";
    static final int D = 1;
    private static final int G = 2;
    private static final int I = 4;
    private static final int M = 8;
    private static final int S = 16;
    static final int U = 32;
    static final int V = 64;
    private static final int Y = 128;

    private final int bits;

    private Flags(int bits) {
        this.bits = bits;
    }

    /**
     * Reads the flags as {@code new RegExp} does: each of the letters {@code dgimsuvy} at most once, and not {@code u}
     * with {@code v}. It throws a {@link RegexException} of kind {@link RegexException.Kind#FLAGS} for any other.
     */
    public static Flags parse(String flags) {
        int valid = bitsOf(flags);
        if (valid >= 0) {
            return new Flags(valid);
        }
        int bits = 0;
        for (int k = 0; k < flags.length(); k++) {
            char c = flags.charAt(k);
            int at = LETTERS.indexOf(c);
            if (at < 0) {
                throw RegexException.flags(
                        "invalid flag " + RegexException.quoteChar(c) + " in " + RegexException.quote(flags));
            }
            if ((bits & 1 << at) != 0) {
                throw RegexException.flags("the flag " + RegexException.quoteChar(c) + " is given twice in "
                        + RegexException.quote(flags));
            }
            bits |= 1 << at;
        }
        throw RegexException.flags("the flags u and v are given together in " + RegexException.quote(flags));
    }

    /**
     * The flags as bits ({@link #U}, {@link #V}, ...), read as {@link #parse} reads them, or -1 for flags that are not
     * valid. It allocates nothing.
     */
    static int bitsOf(String flags) {
        int bits = 0;
        for (int k = 0; k < flags.length(); k++) {
            int at = LETTERS.indexOf(flags.charAt(k));
            if (at < 0 || (bits & 1 << at) != 0) {
                return -1;
            }
            bits |= 1 << at;
        }
        return (bits & U) != 0 && (bits & V) != 0 ? -1 : bits;
    }

    /** {@code d}: accepted for completeness. The positions of the groups are always kept, so it changes nothing. */
    public boolean hasIndices() {
        return (bits & D) != 0;
    }

    /** {@code g}: {@link Regex#replace(String, String)} replaces every match rather than the first. */
    public boolean global() {
        return (bits & G) != 0;
    }

    /** {@code i}: case is ignored, by the Canonicalize of the mode in force. */
    public boolean ignoreCase() {
        return (bits & I) != 0;
    }

    /** {@code m}: {@code ^} and {@code $} hold at line terminators. */
    public boolean multiline() {
        return (bits & M) != 0;
    }

    /** {@code s}: the dot matches line terminators. */
    public boolean dotAll() {
        return (bits & S) != 0;
    }

    /** {@code u}: the grammar of the u flag, and matching by code point. */
    public boolean unicode() {
        return (bits & U) != 0;
    }

    /** {@code v}: the grammar of the v flag (set notation and properties of strings), and matching by code point. */
    public boolean unicodeSets() {
        return (bits & V) != 0;
    }

    /** {@code y}: a match only at the position the search starts from. */
    public boolean sticky() {
        return (bits & Y) != 0;
    }

    Parser.Syntax syntax() {
        return new Parser.Syntax(unicode() || unicodeSets(), unicodeSets(), ignoreCase(), multiline(), dotAll());
    }

    /** The flags as {@code RegExp.prototype.flags} writes them, in the order {@code dgimsuvy}. */
    @Override
    public String toString() {
        StringBuilder out = new StringBuilder(8);
        for (int k = 0; k < LETTERS.length(); k++) {
            if ((bits & 1 << k) != 0) {
                out.append(LETTERS.charAt(k));
            }
        }
        return out.toString();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Flags && ((Flags) other).bits == bits;
    }

    @Override
    public int hashCode() {
        return bits;
    }
}
