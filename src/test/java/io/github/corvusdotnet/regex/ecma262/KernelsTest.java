package io.github.corvusdotnet.regex.ecma262;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The searches are the Vector API's exactly where the JVM has the module jdk.incubator.vector: the tests run once
 * without it and once with it (pom.xml), and each run checks that it tested the searches it was meant to.
 */
class KernelsTest {
    @Test
    void theVectorApiIsTakenWhereTheModuleIsAdded() {
        assertEquals(Boolean.getBoolean("corvus.test.vector"), Kernels.vector());
        assertEquals(ModuleLayer.boot().findModule("jdk.incubator.vector").isPresent(), Kernels.vector());
    }
}
