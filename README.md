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

**Compliance.** Over the [ECMA-262 test data](https://github.com/corvus-dotnet/ecma262-regex-test-data) (what V8
says about 117,889 patterns and 5,884,566 texts, test262's and `exec`, the global search, `replace` and `split` among
them), the library gives 26,950,829 right answers and none wrong, refuses no valid pattern, accepts no invalid one and
reaches no limit, over UTF-8 and over UTF-16. CI runs the whole data on every change.

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

## Status

Not yet released: the first release, 0.1.0 on Maven Central as `io.github.corvus-dotnet:corvus-regex-ecma262`, follows
the benchmark harness.

## Benchmarks

The [central benchmark site](https://corvus-oss.org/corvus-regex-ecma262-benchmarks/) runs this repository's harness
image, `ghcr.io/corvus-dotnet/corvus-regex-ecma262-java`, each night beside every other Corvus ECMA-262 engine: this
library over UTF-8 and UTF-16, `java.util.regex`, TRegex (the regex engine of GraalJS, run by GraalVM's Node) and V8,
over the Industry workloads of dotnet/performance, with each ECMA-262 engine's compliance over the test data. This
repository publishes no figures of its own. `tools/benchmarks/run.sh <workloads.json> <texts> <out.json>` runs the
same harness anywhere (see the script for what it needs).

## Building

JDK 17 and Maven 3.9: `mvn verify` builds the library with warnings denied and runs the tests. The templates of
`src/template` are expanded into one class per encoding by `python3 tools/expand.py` (the expanded files are checked
in, and CI runs it with `--check`). The test data runs with

```sh
javac -encoding UTF-8 -d tools/dataset/out -cp target/classes tools/dataset/Dataset.java
java -Xss4m -cp target/classes:tools/dataset/out Dataset <ecma262-regex-test-data>/data wrong-cases.tsv
```

## Licence

Apache 2.0.
