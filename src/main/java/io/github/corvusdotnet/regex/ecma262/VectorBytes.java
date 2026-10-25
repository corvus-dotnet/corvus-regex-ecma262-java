package io.github.corvusdotnet.regex.ecma262;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShape;
import jdk.incubator.vector.VectorSpecies;

/**
 * The byte searches by the Vector API ({@code jdk.incubator.vector}): a vector of the platform's preferred width (32
 * bytes with AVX2, 64 with AVX-512) compared at a time, where SWAR compares eight. Loaded by {@link Kernels} only where
 * the JVM has the module; nothing else refers to this class. The part of a text shorter than a vector is left to SWAR.
 * Only the API that every JDK from 17 on has is used. No vector crosses a method of this class: each method takes and
 * gives primitives and arrays, and makes and uses its vectors itself, so that none is an object whatever the JIT
 * inlines.
 */
final class VectorBytes implements ByteKernels {
    private static final VectorSpecies<Byte> S = ByteVector.SPECIES_PREFERRED;
    private static final int L = S.length();
    private static final VectorSpecies<Short> U = ShortVector.SPECIES_PREFERRED;
    private static final int LU = U.length();
    /** Bytes as many as {@link #U}'s units, for the narrowing of units to bytes. */
    private static final VectorSpecies<Byte> HALF = VectorSpecies.of(byte.class, VectorShape.forBitSize(U.vectorBitSize() / 2));

    @Override
    public int indexOf(byte[] h, int from, int n, int b) {
        byte x = (byte) b;
        int at = from;
        for (; at <= n - 2 * L; at += 2 * L) {
            VectorMask<Byte> m0 = ByteVector.fromArray(S, h, at).eq(x);
            VectorMask<Byte> m1 = ByteVector.fromArray(S, h, at + L).eq(x);
            if (m0.or(m1).anyTrue()) {
                return m0.anyTrue() ? at + m0.firstTrue() : at + L + m1.firstTrue();
            }
        }
        for (; at <= n - L; at += L) {
            VectorMask<Byte> m = ByteVector.fromArray(S, h, at).eq(x);
            if (m.anyTrue()) {
                return at + m.firstTrue();
            }
        }
        return Swar.indexOf(h, at, n, b);
    }

    @Override
    public int indexOf(byte[] h, int from, int n, int a, int b) {
        byte x = (byte) a;
        byte y = (byte) b;
        int at = from;
        for (; at <= n - L; at += L) {
            ByteVector v = ByteVector.fromArray(S, h, at);
            VectorMask<Byte> m = v.eq(x).or(v.eq(y));
            if (m.anyTrue()) {
                return at + m.firstTrue();
            }
        }
        return Swar.indexOf(h, at, n, a, b);
    }

    @Override
    public int indexOf(byte[] h, int from, int n, int a, int b, int c, int d) {
        byte w = (byte) a;
        byte x = (byte) b;
        byte y = (byte) c;
        byte z = (byte) d;
        int at = from;
        for (; at <= n - L; at += L) {
            ByteVector v = ByteVector.fromArray(S, h, at);
            VectorMask<Byte> m = v.eq(w).or(v.eq(x)).or(v.eq(y)).or(v.eq(z));
            if (m.anyTrue()) {
                return at + m.firstTrue();
            }
        }
        return Swar.indexOf(h, at, n, a, b, c, d);
    }

    @Override
    public int indexOfBetween(byte[] h, int from, int n, int lo, int hi) {
        // Both bounds are ASCII, so a byte beyond ASCII, negative as a Java byte, is below lo.
        byte l = (byte) lo;
        byte u = (byte) hi;
        int at = from;
        for (; at <= n - L; at += L) {
            ByteVector v = ByteVector.fromArray(S, h, at);
            VectorMask<Byte> m = v.compare(VectorOperators.GE, l).and(v.compare(VectorOperators.LE, u));
            if (m.anyTrue()) {
                return at + m.firstTrue();
            }
        }
        return Swar.indexOfBetween(h, at, n, lo, hi);
    }

    /**
     * {@link #bits} for positions of one test each, the commonest (a literal matched exactly, or a letter's two cases
     * joined by a mask): two compares where the four tests of each position make eight.
     */
    private static long bits1(byte[] h, int at, int i, int j, byte mi, byte vi, byte mj, byte vj) {
        VectorMask<Byte> a = ByteVector.fromArray(S, h, at + i).or(mi).eq(vi);
        VectorMask<Byte> b = ByteVector.fromArray(S, h, at + j).or(mj).eq(vj);
        return a.and(b).toLong();
    }

    /**
     * The candidates of the block at {@code at}, a bit each: the starts whose bytes at {@code i} and {@code j} pass their
     * positions' four tests (a byte {@code x} passes one where {@code (x | mask) == value}; a position of fewer repeats
     * its last). Every vector is made and used here and none leaves it: a vector the JIT does not keep in a register
     * (one passed or returned between methods it did not inline, which it does not where the callee's own code is
     * large, or one carried round a loop) is an object, and allocates.
     */
    private static long bits(byte[] h, int at, int i, int j, byte[] ti, byte[] tj) {
        ByteVector x = ByteVector.fromArray(S, h, at + i);
        ByteVector y = ByteVector.fromArray(S, h, at + j);
        VectorMask<Byte> a = x.or(ti[0]).eq(ti[1]).or(x.or(ti[2]).eq(ti[3])).or(x.or(ti[4]).eq(ti[5]))
                .or(x.or(ti[6]).eq(ti[7]));
        VectorMask<Byte> b = y.or(tj[0]).eq(tj[1]).or(y.or(tj[2]).eq(tj[3])).or(y.or(tj[4]).eq(tj[5]))
                .or(y.or(tj[6]).eq(tj[7]));
        return a.and(b).toLong();
    }

    @Override
    public long pairFind(ByteFinder.Pair p, byte[] h, int from, int end) {
        int last = end - p.shortest;
        if (from > last) {
            return -1;
        }
        int i = p.i;
        int j = p.j;
        byte[] ti = p.testsI;
        byte[] tj = p.testsJ;
        // The last start whose two vectors are within the text.
        int blocks = end - L - Math.max(i, j);
        int at = from;
        // Each block's candidates taken as bits, once: where most blocks hold candidates (the of common letters),
        // finding a block and then taking its bits again took a fifth more time. Testing a mask for any and then taking
        // its bits, so that a block of none costs less (a Han literal, 1.17 times as long), made the mask an object
        // the JIT kept in memory, which allocated: each mask has one use.
        if (p.mi.length == 1 && p.mj.length == 1) {
            // A loop of its own for positions of one test each, so that the loop the JIT compiles holds no choice.
            byte mi = ti[0];
            byte vi = ti[1];
            byte mj = tj[0];
            byte vj = tj[1];
            for (; at <= blocks; at += L) {
                long m = bits1(h, at, i, j, mi, vi, mj, vj);
                while (m != 0) {
                    int s = at + Long.numberOfTrailingZeros(m);
                    if (s <= last) {
                        int e = p.literalAt(h, s, end);
                        if (e >= 0) {
                            return ByteFinder.span(s, e);
                        }
                    }
                    m &= m - 1;
                }
            }
            return p.findFrom(h, at, end);
        }
        for (; at <= blocks; at += L) {
            long m = bits(h, at, i, j, ti, tj);
            while (m != 0) {
                int s = at + Long.numberOfTrailingZeros(m);
                if (s <= last) {
                    int e = p.literalAt(h, s, end);
                    if (e >= 0) {
                        return ByteFinder.span(s, e);
                    }
                }
                m &= m - 1;
            }
        }
        return p.findFrom(h, at, end);
    }

    @Override
    public long prefixesFind(ByteFinder.Prefixes p, byte[] h, int from, int end) {
        int last = end - p.shortest;
        if (from > last) {
            return -1;
        }
        short[] v = p.values;
        short v0 = v[0];
        short v1 = v[1];
        short v2 = v[2];
        short v3 = v[3];
        short v4 = v[4];
        short v5 = v[5];
        short v6 = v[6];
        short v7 = v[7];
        // Each block's bytes taken as sixteen-bit units twice, from its start and from one byte on: the starts at even
        // and at odd offsets, each of whose first two bytes are compared with every literal's at once.
        int blocks = end - L - 1;
        int at = from;
        for (; at <= blocks; at += L) {
            ShortVector e = ByteVector.fromArray(S, h, at).reinterpretAsShorts();
            ShortVector o = ByteVector.fromArray(S, h, at + 1).reinterpretAsShorts();
            long me = e.eq(v0).or(e.eq(v1)).or(e.eq(v2)).or(e.eq(v3)).or(e.eq(v4)).or(e.eq(v5)).or(e.eq(v6)).or(e.eq(v7))
                    .toLong();
            long mo = o.eq(v0).or(o.eq(v1)).or(o.eq(v2)).or(o.eq(v3)).or(o.eq(v4)).or(o.eq(v5)).or(o.eq(v6)).or(o.eq(v7))
                    .toLong();
            while ((me | mo) != 0) {
                int se = me != 0 ? 2 * Long.numberOfTrailingZeros(me) : Integer.MAX_VALUE;
                int so = mo != 0 ? 2 * Long.numberOfTrailingZeros(mo) + 1 : Integer.MAX_VALUE;
                int s;
                if (se < so) {
                    s = at + se;
                    me &= me - 1;
                } else {
                    s = at + so;
                    mo &= mo - 1;
                }
                if (s <= last) {
                    int e2 = p.literalAt(h, s, end);
                    if (e2 >= 0) {
                        return ByteFinder.span(s, e2);
                    }
                }
            }
        }
        return p.findFrom(h, at, end);
    }

    @Override
    public int unitsIndexOf(char[] c, int from, int n, char[] lohi, boolean wide) {
        short lo0 = (short) lohi[0];
        short w0 = (short) lohi[1];
        short lo1 = (short) lohi[2];
        short w1 = (short) lohi[3];
        short lo2 = (short) lohi[4];
        short w2 = (short) lohi[5];
        short lo3 = (short) lohi[6];
        short w3 = (short) lohi[7];
        // A unit in a range is one whose distance from the range's start is at most its width, unsigned; JDK 17's API
        // has signed comparisons alone, so both sides have their sign bit flipped, which orders them as unsigned.
        short b0 = (short) (w0 ^ 0x8000);
        short b1 = (short) (w1 ^ 0x8000);
        short b2 = (short) (w2 ^ 0x8000);
        short b3 = (short) (w3 ^ 0x8000);
        short flip = (short) 0x8000;
        // Beyond ASCII where the set has such members: a unit of 0x80 or more (as a signed unit, 0x80 up or below
        // zero) is a candidate. Every candidate is compared with the set by the caller, so where the set has no such
        // member the test only has to pass next to nothing: 0x7FFF alone.
        short beyond = wide ? (short) 0x80 : Short.MAX_VALUE;
        short below = wide ? (short) 0 : Short.MIN_VALUE;
        int at = from;
        for (; at <= n - LU; at += LU) {
            ShortVector v = ShortVector.fromCharArray(U, c, at);
            VectorMask<Short> m = v.sub(lo0).lanewise(VectorOperators.XOR, flip).compare(VectorOperators.LE, b0)
                    .or(v.sub(lo1).lanewise(VectorOperators.XOR, flip).compare(VectorOperators.LE, b1))
                    .or(v.sub(lo2).lanewise(VectorOperators.XOR, flip).compare(VectorOperators.LE, b2))
                    .or(v.sub(lo3).lanewise(VectorOperators.XOR, flip).compare(VectorOperators.LE, b3))
                    .or(v.compare(VectorOperators.GE, beyond))
                    .or(v.compare(VectorOperators.LT, below));
            long bits = m.toLong();
            if (bits != 0) {
                return at + Long.numberOfTrailingZeros(bits);
            }
        }
        for (; at < n; at++) {
            int u = c[at];
            if (u - (lohi[0]) >= 0 && u - lohi[0] <= lohi[1] || u - lohi[2] >= 0 && u - lohi[2] <= lohi[3]
                    || u - lohi[4] >= 0 && u - lohi[4] <= lohi[5] || u - lohi[6] >= 0 && u - lohi[6] <= lohi[7]
                    || wide && u >= 0x80) {
                return at;
            }
        }
        return -1;
    }

    @Override
    public void narrow(char[] c, int n, byte[] out) {
        int i = 0;
        for (; i <= n - LU; i += LU) {
            ((ByteVector) ShortVector.fromCharArray(U, c, i).convertShape(VectorOperators.S2B, HALF, 0)).intoArray(out, i);
        }
        for (; i < n; i++) {
            out[i] = (byte) c[i];
        }
    }

    @Override
    public boolean beyondLatin1(char[] c, int n) {
        int i = 0;
        for (; i <= n - LU; i += LU) {
            ShortVector v = ShortVector.fromCharArray(U, c, i);
            // As signed units, beyond Latin-1 is above 0xFF or below zero (0x8000 up).
            if (v.compare(VectorOperators.GT, (short) 0xFF).or(v.compare(VectorOperators.LT, (short) 0)).anyTrue()) {
                return true;
            }
        }
        for (; i < n; i++) {
            if (c[i] > 0xFF) {
                return true;
            }
        }
        return false;
    }
}
