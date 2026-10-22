package io.github.corvusdotnet.regex.ecma262;

/** Which limit of size a pattern is over (see {@link Regex#MAX_PATTERN_LENGTH} and the others). */
public enum Limit {
    /** {@link Regex#MAX_PATTERN_LENGTH}. */
    LENGTH("length"),
    /** {@link Regex#MAX_NESTING}. */
    NESTING("nesting"),
    /** {@link Regex#MAX_SIZE}. */
    SIZE("size");

    private final String label;

    Limit(String label) {
        this.label = label;
    }

    /** The name of the limit: "length", "nesting" or "size". */
    public String label() {
        return label;
    }
}
