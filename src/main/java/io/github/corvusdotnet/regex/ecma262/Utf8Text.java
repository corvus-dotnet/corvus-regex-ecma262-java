package io.github.corvusdotnet.regex.ecma262;

import java.nio.charset.StandardCharsets;

/**
 * Bytes known to be UTF-8, checked once when the text is made, so that the matches over it need not check them again
 * as the methods that take a {@code byte[]} do. It holds the array it was made from without copying it: the bytes must
 * not change while the text is in use.
 */
public final class Utf8Text {
    final byte[] bytes;

    private Utf8Text(byte[] bytes) {
        this.bytes = bytes;
    }

    /** The bytes as a text. It throws {@link IllegalArgumentException} where they are not UTF-8. */
    public static Utf8Text of(byte[] bytes) {
        Text8.check(bytes);
        return new Utf8Text(bytes);
    }

    /** Bytes the engine wrote, which are UTF-8. */
    static Utf8Text wrap(byte[] bytes) {
        return new Utf8Text(bytes);
    }

    /** The UTF-8 of a string. It throws {@link IllegalArgumentException} for a string with a lone surrogate. */
    public static Utf8Text of(String s) {
        return new Utf8Text(Text8.encode(s));
    }

    /** The bytes of the text, which are not copied. */
    public byte[] bytes() {
        return bytes;
    }

    /** The length of the text in bytes. */
    public int length() {
        return bytes.length;
    }

    @Override
    public String toString() {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
