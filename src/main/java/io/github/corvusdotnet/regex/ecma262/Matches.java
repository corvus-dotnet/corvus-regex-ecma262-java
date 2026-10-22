package io.github.corvusdotnet.regex.ecma262;

/**
 * A global search that allocates nothing for each match ({@code matchAll} as a cursor): {@link #next()} moves to the
 * next match, after an empty match one character on, and {@link #start()} and {@link #end()} say where it is. With
 * the y flag each match must start where the one before it ended. It can be pointed at another text with
 * {@link #reset(String)}, so that one cursor serves a loop over many texts. Not safe to share between threads.
 */
public final class Matches {
    private final Regex re;
    private String text16;
    private byte[] text8;
    private int at;
    private boolean done;
    private int start = -1;
    private int end = -1;

    Matches(Regex re) {
        this.re = re;
        this.done = true;
    }

    /** Starts the search again, over a String. */
    public Matches reset(String text) {
        text16 = text;
        text8 = null;
        return restart();
    }

    /** Starts the search again, over UTF-8 text. */
    public Matches reset(Utf8Text text) {
        text16 = null;
        text8 = text.bytes;
        return restart();
    }

    /** Starts the search again, over UTF-8 bytes, which are checked first (see {@link Utf8Text}). */
    public Matches reset(byte[] text) {
        Text8.check(text);
        text16 = null;
        text8 = text;
        return restart();
    }

    private Matches restart() {
        at = 0;
        done = false;
        start = -1;
        end = -1;
        return this;
    }

    private long exec(int[] slots) {
        return text16 != null ? Text16.exec(re, text16, at, re.flags.sticky(), 0, slots)
                : Text8.exec(re, text8, at, re.flags.sticky(), 0, slots);
    }

    private boolean found(long span) {
        if (span < 0) {
            done = true;
            start = -1;
            end = -1;
            return false;
        }
        start = Regex.from(span);
        end = Regex.to(span);
        at = text16 != null ? Text16.nextStart(re, text16, start, end) : Text8.nextStart(re, text8, start, end);
        return true;
    }

    /** Moves to the next match: whether there is one. */
    public boolean next() {
        return !done && found(exec(null));
    }

    /**
     * Moves to the next match, with what each group captured written into {@code into} (made by
     * {@link Regex#newCaptures()} for this pattern): whether there is one.
     */
    public boolean next(Captures into) {
        if (into.info != re.info) {
            throw new IllegalArgumentException("the captures were made for another pattern");
        }
        if (done || !found(exec(into.slots))) {
            return false;
        }
        into.text16 = text16;
        into.text8 = text8;
        return true;
    }

    /** The number of the matches still to come, which it moves past. */
    public int count() {
        int n = 0;
        while (next()) {
            n++;
        }
        return n;
    }

    /** Where the current match starts, or -1 before the first and after the last. */
    public int start() {
        return start;
    }

    /** Where the current match ends, or -1 before the first and after the last. */
    public int end() {
        return end;
    }
}
