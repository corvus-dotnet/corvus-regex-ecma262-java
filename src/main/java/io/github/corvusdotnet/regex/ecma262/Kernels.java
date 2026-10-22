package io.github.corvusdotnet.regex.ecma262;

/**
 * The byte searches the finders call: the Vector API's where the JVM was started with the module {@code
 * jdk.incubator.vector} ({@code --add-modules jdk.incubator.vector}), which is how a program opts in, and SWAR ({@link
 * Swar}) otherwise. The Vector API is an incubating module in every JDK this library runs on, so it is never the
 * default: without the module, no class of it is loaded. A program that adds the module and runs on a JDK whose
 * incubating API this build cannot link to goes on with SWAR.
 */
final class Kernels {
    private Kernels() {}

    /** The Vector API's searches, or {@code null} for SWAR. A constant, so the JIT calls the one there is directly. */
    static final ByteKernels VECTOR = load();

    private static ByteKernels load() {
        if (ModuleLayer.boot().findModule("jdk.incubator.vector").isEmpty()) {
            return null;
        }
        try {
            ByteKernels k = (ByteKernels) Class.forName(Kernels.class.getPackageName() + ".VectorBytes")
                    .getDeclaredConstructor().newInstance();
            // A search, so that an API this build cannot link to is found here rather than in a match.
            byte[] probe = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".getBytes(
                    java.nio.charset.StandardCharsets.US_ASCII);
            if (k.indexOf(probe, 0, probe.length, 'Z') != 61 || k.indexOfBetween(probe, 0, probe.length, 'A', 'Z') != 36) {
                return null;
            }
            return k;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            return null;
        }
    }

    /** Whether the searches are the Vector API's. */
    static boolean vector() {
        return VECTOR != null;
    }

    static int indexOf(byte[] h, int from, int n, int b) {
        return VECTOR != null ? VECTOR.indexOf(h, from, n, b) : Swar.indexOf(h, from, n, b);
    }

    static int indexOf(byte[] h, int from, int n, int a, int b) {
        return VECTOR != null ? VECTOR.indexOf(h, from, n, a, b) : Swar.indexOf(h, from, n, a, b);
    }

    static int indexOf(byte[] h, int from, int n, int a, int b, int c) {
        return indexOf(h, from, n, a, b, c, c);
    }

    static int indexOf(byte[] h, int from, int n, int a, int b, int c, int d) {
        return VECTOR != null ? VECTOR.indexOf(h, from, n, a, b, c, d) : Swar.indexOf(h, from, n, a, b, c, d);
    }

    static int indexOfBetween(byte[] h, int from, int n, int lo, int hi) {
        return VECTOR != null ? VECTOR.indexOfBetween(h, from, n, lo, hi) : Swar.indexOfBetween(h, from, n, lo, hi);
    }
}
