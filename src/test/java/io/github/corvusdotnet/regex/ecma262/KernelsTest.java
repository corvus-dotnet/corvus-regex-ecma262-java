package io.github.corvusdotnet.regex.ecma262;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The searches are the Vector API's exactly where the JVM has the module jdk.incubator.vector, from JDK 21 on: the tests
 * run once without it and once with it (pom.xml), and each run checks that it tested the searches it was meant to.
 */
class KernelsTest {
    @Test
    void theVectorApiIsTakenWhereTheModuleIsAdded() {
        boolean recent = Runtime.version().feature() >= Kernels.FIRST_JDK;
        assertEquals(Boolean.getBoolean("corvus.test.vector") && recent, Kernels.vector());
        assertEquals(ModuleLayer.boot().findModule("jdk.incubator.vector").isPresent() && recent, Kernels.vector());
    }
}
