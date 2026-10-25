package io.github.corvusdotnet.regex.ecma262;

/**
 * The byte searches that vector code runs faster than SWAR: those of {@link Swar}, and the pair search's loop. {@link
 * Kernels} takes the Vector API's ({@code VectorBytes}) where the JVM has the module {@code jdk.incubator.vector}, and
 * SWAR otherwise.
 */
interface ByteKernels {
    int indexOf(byte[] h, int from, int n, int b);

    int indexOf(byte[] h, int from, int n, int a, int b);

    int indexOf(byte[] h, int from, int n, int a, int b, int c, int d);

    int indexOfBetween(byte[] h, int from, int n, int lo, int hi);

    /** The pair search's {@link ByteFinder.Pair#find}. */
    long pairFind(ByteFinder.Pair p, byte[] h, int from, int end);

    /**
     * The first unit of {@code c} from {@code from} to {@code n} in one of four ranges (each {@code lohi[2k]} to {@code
     * lohi[2k] + lohi[2k + 1]}), or beyond ASCII where {@code wide}; -1 for none.
     */
    int unitsIndexOf(char[] c, int from, int n, char[] lohi, boolean wide);

    /** Whether any of the first {@code n} units of {@code c} is beyond Latin-1 (0x100 or more). */
    boolean beyondLatin1(char[] c, int n);

    /** The low byte of each of the first {@code n} units of {@code c}, into {@code out}. */
    void narrow(char[] c, int n, byte[] out);

    /** The search of the literals' first two bytes, {@link ByteFinder.Prefixes#find}. */
    long prefixesFind(ByteFinder.Prefixes p, byte[] h, int from, int end);
}
