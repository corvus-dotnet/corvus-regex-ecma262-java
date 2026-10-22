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

    private static final long HIGHS = 0x8080808080808080L;

    /**
     * Whether some lane of {@code x} is zero: the cheap test, which flags every zero lane but may also flag a lane of 1
     * above a zero lane. The loops use it to know that a block holds a byte they look for, and {@link #zeros} to find
     * which; a finder that tests candidates (every one here) can take its flags as they are.
     */
    static long hasZero(long x) {
        return (x - ONES) & ~x & HIGHS;
    }

    /** The first position at or after {@code from} and before {@code n} whose byte is {@code b}; -1 for none. */
    static int indexOf(byte[] h, int from, int n, int b) {
        long v = broadcast(b);
        int at = from;
        // Thirty-two bytes a step, tested together by the cheap test; the word that holds the byte is found after.
        for (; at <= n - 32; at += 32) {
            long w0 = word(h, at) ^ v;
            long w1 = word(h, at + 8) ^ v;
            long w2 = word(h, at + 16) ^ v;
            long w3 = word(h, at + 24) ^ v;
            if ((hasZero(w0) | hasZero(w1) | hasZero(w2) | hasZero(w3)) != 0) {
                long z = zeros(w0);
                if (z != 0) {
                    return at + lane(z);
                }
                z = zeros(w1);
                if (z != 0) {
                    return at + 8 + lane(z);
                }
                z = zeros(w2);
                if (z != 0) {
                    return at + 16 + lane(z);
                }
                return at + 24 + lane(zeros(w3));
            }
        }
        for (; at <= n - 8; at += 8) {
            long z = zeros(word(h, at) ^ v);
            if (z != 0) {
                return at + lane(z);
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
        for (; at <= n - 16; at += 16) {
            long w0 = word(h, at);
            long w1 = word(h, at + 8);
            if ((hasZero(w0 ^ va) | hasZero(w0 ^ vb) | hasZero(w1 ^ va) | hasZero(w1 ^ vb)) != 0) {
                long z = eq(w0, va) | eq(w0, vb);
                return z != 0 ? at + lane(z) : at + 8 + lane(eq(w1, va) | eq(w1, vb));
            }
        }
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
        return indexOf(h, from, n, a, b, c, c);
    }

    /** The first position at or after {@code from} and before {@code n} whose byte is one of four. */
    static int indexOf(byte[] h, int from, int n, int a, int b, int c, int d) {
        long va = broadcast(a);
        long vb = broadcast(b);
        long vc = broadcast(c);
        long vd = broadcast(d);
        int at = from;
        for (; at <= n - 8; at += 8) {
            long w = word(h, at);
            if ((hasZero(w ^ va) | hasZero(w ^ vb) | hasZero(w ^ vc) | hasZero(w ^ vd)) != 0) {
                return at + lane(eq(w, va) | eq(w, vb) | eq(w, vc) | eq(w, vd));
            }
        }
        for (; at < n; at++) {
            byte x = h[at];
            if (x == (byte) a || x == (byte) b || x == (byte) c || x == (byte) d) {
                return at;
            }
        }
        return -1;
    }

    /**
     * The high bit of each lane of {@code x} that is an ASCII byte from {@code lo} to {@code hi} (both below 0x80):
     * exact, since the seven low bits of a lane plus a constant below 0x80 cannot carry into the next lane.
     */
    static long between(long x, long lo, long hi) {
        long t = x & LOW7;
        return (t + lo) & ~(t + hi) & ~x & HIGHS;
    }

    /** The constants of {@link #between} for the bytes {@code lo} to {@code hi}. */
    static long betweenLo(int lo) {
        return broadcast(0x80 - lo);
    }

    static long betweenHi(int hi) {
        return broadcast(0x7F - hi);
    }

    /** The first position at or after {@code from} and before {@code n} whose byte is from {@code lo} to {@code hi}. */
    static int indexOfBetween(byte[] h, int from, int n, int lo, int hi) {
        long vl = betweenLo(lo);
        long vh = betweenHi(hi);
        int at = from;
        for (; at <= n - 16; at += 16) {
            long z0 = between(word(h, at), vl, vh);
            long z1 = between(word(h, at + 8), vl, vh);
            if ((z0 | z1) != 0) {
                return z0 != 0 ? at + lane(z0) : at + 8 + lane(z1);
            }
        }
        for (; at <= n - 8; at += 8) {
            long z = between(word(h, at), vl, vh);
            if (z != 0) {
                return at + lane(z);
            }
        }
        for (; at < n; at++) {
            int x = h[at];
            if (x >= lo && x <= hi) {
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
