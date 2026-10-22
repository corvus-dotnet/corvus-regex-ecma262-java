package io.github.corvusdotnet.regex.ecma262;

import java.util.Arrays;

/** A growable list of ints: the engine's vector of code points, ranges and program words. */
final class IntList {
    int[] a;
    int n;

    IntList() {
        a = new int[8];
    }

    IntList(int capacity) {
        a = new int[Math.max(capacity, 1)];
    }

    static IntList of(int... values) {
        IntList list = new IntList(values.length);
        System.arraycopy(values, 0, list.a, 0, values.length);
        list.n = values.length;
        return list;
    }

    int size() {
        return n;
    }

    boolean isEmpty() {
        return n == 0;
    }

    int get(int i) {
        return a[i];
    }

    void set(int i, int v) {
        a[i] = v;
    }

    int last() {
        return a[n - 1];
    }

    void add(int v) {
        if (n == a.length) {
            a = Arrays.copyOf(a, a.length * 2);
        }
        a[n++] = v;
    }

    void add(int v, int w) {
        if (n + 2 > a.length) {
            a = Arrays.copyOf(a, Math.max(a.length * 2, n + 2));
        }
        a[n++] = v;
        a[n++] = w;
    }

    void addAll(int[] values) {
        addAll(values, 0, values.length);
    }

    void addAll(int[] values, int from, int to) {
        int count = to - from;
        if (n + count > a.length) {
            a = Arrays.copyOf(a, Math.max(a.length * 2, n + count));
        }
        System.arraycopy(values, from, a, n, count);
        n += count;
    }

    void addAll(IntList other) {
        addAll(other.a, 0, other.n);
    }

    int pop() {
        return a[--n];
    }

    void clear() {
        n = 0;
    }

    void truncate(int size) {
        n = size;
    }

    int[] toArray() {
        return Arrays.copyOf(a, n);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof IntList)) {
            return false;
        }
        IntList other = (IntList) o;
        return Arrays.equals(a, 0, n, other.a, 0, other.n);
    }

    @Override
    public int hashCode() {
        int h = 1;
        for (int i = 0; i < n; i++) {
            h = 31 * h + a[i];
        }
        return h;
    }
}
