package io.github.corvusdotnet.regex.ecma262;

import java.util.Arrays;

/** A growing array of bytes, which replace writes UTF-8 into. */
final class Bytes {
    private byte[] buf;
    private int n;

    Bytes(int capacity) {
        buf = new byte[Math.max(capacity, 16)];
    }

    private void room(int more) {
        if (n + more > buf.length) {
            buf = Arrays.copyOf(buf, Math.max(buf.length * 2, n + more));
        }
    }

    void append(byte[] src, int from, int to) {
        room(to - from);
        System.arraycopy(src, from, buf, n, to - from);
        n += to - from;
    }

    void append(int b) {
        room(1);
        buf[n++] = (byte) b;
    }

    void append(byte[] src) {
        append(src, 0, src.length);
    }

    byte[] toArray() {
        return Arrays.copyOf(buf, n);
    }
}
