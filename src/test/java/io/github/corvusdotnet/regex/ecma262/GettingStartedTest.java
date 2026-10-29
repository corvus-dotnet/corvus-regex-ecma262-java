package io.github.corvusdotnet.regex.ecma262;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/**
 * The examples of the getting-started page the documentation site publishes ({@code docs/getting-started.md}), each a
 * class run by the source launcher against this library, so that the page shows only code that works.
 */
class GettingStartedTest {
    @TempDir
    Path work;

    @TestFactory
    Stream<DynamicTest> everyExampleRuns() throws IOException {
        String page = Files.readString(Path.of("docs", "getting-started.md"), StandardCharsets.UTF_8);
        Matcher blocks = Pattern.compile("(?ms)^```java\\n(.*?)^```$").matcher(page);
        List<String> sources = new ArrayList<>();
        while (blocks.find()) {
            sources.add(blocks.group(1));
        }
        assertTrue(sources.size() >= 5, "the page has " + sources.size() + " examples");
        return sources.stream().map(source -> DynamicTest.dynamicTest(className(source), () -> run(source)));
    }

    private static String className(String source) {
        Matcher m = Pattern.compile("(?m)^class (\\w+)").matcher(source);
        return m.find() ? m.group(1) : "an example";
    }

    private void run(String source) throws IOException, InterruptedException {
        Path file = work.resolve(className(source) + ".java");
        Files.writeString(file, source, StandardCharsets.UTF_8);
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process p = new ProcessBuilder(java, "-ea", "-cp", System.getProperty("java.class.path"), file.toString())
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(120, TimeUnit.SECONDS), "the example did not finish");
        assertEquals(0, p.exitValue(), out);
    }
}
