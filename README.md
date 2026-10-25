# corvus-regex-ecma262 for Java

Regular expressions with the grammar and the answers of ECMA-262, the `RegExp` of JavaScript, for Java 17 and later.

- Every flag: `d`, `g`, `i`, `m`, `s`, `u`, `v` and `y`, with the grammar of each. Without `u` or `v` a pattern is
  read by the Annex B grammar and matched by UTF-16 code unit, as ECMA-262 matches it; with them, by code point. The
  `v` flag has nested classes, the difference and the intersection of classes, strings in a class and the seven
  properties of strings.
- ECMAScript 2025: lookbehind, named groups (and a name shared between alternatives), modifier groups such as
  `(?i:...)`, and every Unicode property ECMA-262 lists, from tables of Unicode 17 the library carries, so a pattern
  answers the same whatever JDK runs it.
- Text as a `String` (UTF-16, lone surrogates and all) or as UTF-8 bytes (`Utf8Text`, checked once, or a `byte[]`,
  checked on each call). Nothing is transcoded, and a match allocates nothing once the thread's working memory has
  grown.
- `isMatch`, `find`, `findAll`, `captures`, `capturesAll`, `replace` and `replaceAll` (with every `$` form of
  `GetSubstitution`) and `split`, each as `RegExp` and `String.prototype` define it.
- A pattern without a backreference is matched in time proportional to its size times the text. A pattern can be
  given a match timeout, as a `Regex` of .NET can, which bounds the time and the backtracking memory of every match,
  and a pattern has limits of size, so with a timeout neither a pattern nor a text from outside can make a program
  hang. `Regex.setDefaultMatchTimeout`, or the system property `corvus.regex.defaultMatchTimeout`, gives every
  pattern one.

- Two modes of searching for where a match can start. By default the library's own searches, which read a text
  eight bytes at a time and need nothing of the JVM. Started with `--add-modules jdk.incubator.vector`, the JVM gives
  the library the Vector API, and from JDK 21 on it searches a vector at a time (32 bytes with AVX2); on JDK 17,
  whose JIT allocated for the Vector API's masks, it stays with its own. The module is incubating in every JDK so
  far, so adding it is how a program opts in; without it, nothing of the Vector API is loaded. Both modes give the
  same answers, and CI runs everything in each.

**Compliance.** Over the [ECMA-262 test data](https://github.com/corvus-dotnet/ecma262-regex-test-data) (what V8 says
about its patterns and texts, test262's patterns and `exec` calls, the global search, `replace` and `split` among them,
and texts of bytes that are not UTF-8, which it refuses), the library gives every answer right and none wrong,
refuses no valid pattern, accepts no invalid one and reaches no limit, over UTF-8 and over UTF-16. CI runs the whole
data on every change.

It is a port of the Rust crate [corvus-regex-ecma262](https://github.com/corvus-dotnet/corvus-regex-ecma262-rs) at
v0.1.3, with the crate's search layer (`docs/port-checklist.md`), and it follows the design of the .NET engine
[Corvus.Regex.Ecma262](https://github.com/corvus-dotnet/Corvus.Regex.Ecma262).

```java
import io.github.corvusdotnet.regex.ecma262.Regex;
import java.time.Duration;

Regex re = Regex.compile("(?<year>\\d{4})-(?<month>\\d{2})", "u", Duration.ofMillis(100));
re.isMatch("on 2026-10-22");                              // true
re.captures("on 2026-10-22").group("month");             // "10"
re.replaceAll("2026-10-22", "$<month>/$<year>");          // "10/2026-22"
```

## Installing

From Maven Central, for Java 17 and later:

```xml
<dependency>
  <groupId>io.github.corvus-dotnet</groupId>
  <artifactId>corvus-regex-ecma262</artifactId>
  <version>0.1.0</version>
</dependency>
```

or with Gradle, `implementation("io.github.corvus-dotnet:corvus-regex-ecma262:0.1.0")`. The library has no dependency.
For the Vector API's searches, start the JVM with `--add-modules jdk.incubator.vector` (JDK 21 or later).

## Benchmarks

The [central benchmark site](https://corvus-oss.org/corvus-regex-ecma262-benchmarks/) runs this repository's harness
image, `ghcr.io/corvus-dotnet/corvus-regex-ecma262-java`, each night beside the engines of every other source: this
library over UTF-8 and UTF-16 in both modes, `java.util.regex`, TRegex (the regex engine of GraalJS, run by GraalVM's Node) and V8,
over the Industry workloads of dotnet/performance, with each ECMA-262 engine's compliance over the test data. This
repository publishes no figures of its own. `tools/benchmarks/run.sh <workloads.json> <texts> <out.json>` runs the
same harness anywhere (see the script for what it needs).

TRegex's compliance takes too long to work out every night, so it is worked out once (`TREGEX_COMPLIANCE=1`, which runs
`tools/benchmarks/tregex-compliance.py`) and checked in as `tools/benchmarks/compliance-tregex.json`, its compliance
file: the counts, the script and the calls it makes, and what it leaves out (UTF-8). A JavaScript engine cannot be
stopped in a match, so a watchdog thread in GraalVM's Node ends the process at an answer that passes 2 seconds; that
answer is unanswered, and a new process goes on from the next. Once three answers of a record have reached the limit,
its other answers are unanswered without being asked. The limit is the wall clock's, so the run is made with nothing
else running on the machine. The file gives the commit of the test data it was made at
(`dataCommit`): the page judges the counts against what that commit asks, and CI fails when it is not the commit CI
checks the data out at, so moving CI's pin means working it out again.
A change to the harness alone, such as new counts, is released as a harness release (`gh release create
harness-<version>.<n> --latest=false`), which publishes the harness image and no package.

## Releasing

Set the version in `pom.xml`, merge, and publish a GitHub release tagged `v<version>`: `maven-publish.yml` runs the tests
and the test data in both modes, then signs and publishes the version to Maven Central (a version already there
publishes nothing), and `harness-image.yml` publishes the harness image under the same tag.

## Building

JDK 25 and Maven 3.9: `mvn verify` builds the library for Java 17 with warnings denied (the Vector API's searches
compile against the incubating module, whose warning only JDK 25's javac can leave out) and runs the tests in both
modes. The templates of
`src/template` are expanded into one class per encoding by `python3 tools/expand.py` (the expanded files are checked
in, and CI runs it with `--check`). The test data runs with

```sh
javac -encoding UTF-8 -d tools/dataset/out -cp target/classes tools/dataset/Dataset.java
java -Xss4m -cp target/classes:tools/dataset/out Dataset <ecma262-regex-test-data>/data wrong-cases.tsv
```

## Licence

Apache 2.0.
