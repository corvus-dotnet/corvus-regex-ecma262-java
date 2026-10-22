package io.github.corvusdotnet.regex.ecma262;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * The byte searches the Rust crate takes from {@code memchr}, written as SWAR: eight bytes of a {@code long} read at a
 * time and tested together, since Java 17 has no vector API that is not incubating. A lane test is exact (no lane is
 * flagged that does not pass), so the first flagged lane is the first byte that passes.
 */
final class Swar {
    private Swar() {}

    private static final VarHandle LONG = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    static final long ONES = 0x0101010101010101L;
    private static final long LOW7 = 0x7F7F7F7F7F7F7F7FL;

    /** The eight bytes at {@code at}, the first in the low byte. */
    static long word(byte[] h, int at) {
        return (long) LONG.get(h, at);
    }

    /** A byte in every lane. */
    static long broadcast(int b) {
        return (b & 0xFFL) * ONES;
    }

    /** The high bit of each lane of {@code x} that is zero. */
    static long zeros(long x) {
        return ~(((x & LOW7) + LOW7) | x | LOW7);
    }

    /** The high bit of each lane of {@code x} that equals the broadcast byte {@code v}. */
    static long eq(long x, long v) {
        return zeros(x ^ v);
    }

    /** The lane of the lowest flagged high bit. */
    static int lane(long flags) {
        return Long.numberOfTrailingZeros(flags) >>> 3;
    }

    /** The first position at or after {@code from} and before {@code n} whose byte is {@code b}; -1 for none. */
    static int indexOf(byte[] h, int from, int n, int b) {
        long v = broadcast(b);
        int at = from;
        for (; at <= n - 16; at += 16) {
            long x = eq(word(h, at), v);
            long y = eq(word(h, at + 8), v);
            if ((x | y) != 0) {
                return x != 0 ? at + lane(x) : at + 8 + lane(y);
            }
        }
        for (; at < n; at++) {
            if (h[at] == (byte) b) {
                return at;
            }
        }
        return -1;
    }

    /** The first position at or after {@code from} and before {@code n} whose byte is {@code a} or {@code b}. */
    static int indexOf(byte[] h, int from, int n, int a, int b) {
        long va = broadcast(a);
        long vb = broadcast(b);
        int at = from;
        for (; at <= n - 8; at += 8) {
            long w = word(h, at);
            long x = eq(w, va) | eq(w, vb);
            if (x != 0) {
                return at + lane(x);
            }
        }
        for (; at < n; at++) {
            if (h[at] == (byte) a || h[at] == (byte) b) {
                return at;
            }
        }
        return -1;
    }

    /** The first position at or after {@code from} and before {@code n} whose byte is {@code a}, {@code b} or {@code c}. */
    static int indexOf(byte[] h, int from, int n, int a, int b, int c) {
        long va = broadcast(a);
        long vb = broadcast(b);
        long vc = broadcast(c);
        int at = from;
        for (; at <= n - 8; at += 8) {
            long w = word(h, at);
            long x = eq(w, va) | eq(w, vb) | eq(w, vc);
            if (x != 0) {
                return at + lane(x);
            }
        }
        for (; at < n; at++) {
            byte x = h[at];
            if (x == (byte) a || x == (byte) b || x == (byte) c) {
                return at;
            }
        }
        return -1;
    }

    /** Whether the bytes of {@code literal} are at {@code at} of {@code h}, before {@code end}. */
    static boolean startsWith(byte[] h, int at, int end, byte[] literal) {
        int n = literal.length;
        if (end - at < n) {
            return false;
        }
        // Compared a byte at a time: the literals are short, and the call that compares a range costs more.
        for (int k = 0; k < n; k++) {
            if (h[at + k] != literal[k]) {
                return false;
            }
        }
        return true;
    }
}
