package io.github.corvusdotnet.regex.ecma262;

/**
 * One match: where it starts and ends in the text, in the text's units (UTF-16 code units of a {@code String}, or
 * bytes of UTF-8).
 */
public final class Match {
    private final String text16;
    private final byte[] text8;
    private final int start;
    private final int end;

    Match(String text16, byte[] text8, int start, int end) {
        this.text16 = text16;
        this.text8 = text8;
        this.start = start;
        this.end = end;
    }

    /** Where the match starts. */
    public int start() {
        return start;
    }

    /** Where the match ends. */
    public int end() {
        return end;
    }

    /** The length of the match, in units. */
    public int length() {
        return end - start;
    }

    /** Whether the match is empty. */
    public boolean isEmpty() {
        return start == end;
    }

    /**
     * The text of the match. Over UTF-8, without the u or v flag, a match can start or end between the two halves of a
     * character beyond the Basic Multilingual Plane, which has no UTF-8 form; the half character is then written as
     * U+FFFD ({@link #start()} and {@link #end()} are exact).
     */
    public String value() {
        return text16 != null ? text16.substring(start, end) : Text8.string(text8, start, end);
    }

    /** The bytes of the match over UTF-8 text, a half character at either end written as U+FFFD; {@code null} over a String. */
    public byte[] bytes() {
        return text8 == null ? null : Text8.piece(text8, start, end);
    }

    @Override
    public String toString() {
        return "Match(" + start + ".." + end + ")";
    }
}
