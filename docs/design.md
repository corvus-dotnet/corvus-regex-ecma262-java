# corvus-regex-ecma262 for Java: design

An outline of the library as it is: what it offers, how the engine is built and why, and how it is tested and
measured. The [getting-started page](getting-started.md) shows how to use it.

## 1. What it is

A Java regular expression engine with ECMA-262's grammar and answers (ECMAScript 2025), over UTF-16 (a `String`, as
JavaScript holds one) and UTF-8 (a `byte[]` or a `Utf8Text`). It is a port of the corvus-regex-ecma262 Rust crate to
Java 17, with no dependencies.

**Why ECMA-262.** JSON Schema, OpenAPI and many wire formats specify their patterns as ECMAScript regular
expressions. `java.util.regex` has its own grammar and its own meaning for `\d`, `\w`, `\s`, `.`, case folding and
Unicode properties, so a translation to it can never be exact. An engine that reads ECMA-262 directly, held to V8's
answers over a large data set (section 6), needs no translation.

## 2. Public surface

**Names.** `Regex.compile`, `isMatch`, `find`, `findAll`, `captures`, `replace`, `replaceAll` and `split` are the
methods of a `RegExp` in Java's naming, with the position as an argument where JavaScript keeps `lastIndex`. A match is
a `Match`, and what it captured a `Captures`, by number and by name.

**Flags.** ECMA-262's, as a string or as `Flags`, and nothing else.

**Edition and Unicode version.** By default a pattern is read with today's grammar (`Edition.CURRENT`) and V8's
version of Unicode (`UnicodeVersion.DEFAULT`, 17 today). `Regex.builder` overrides either, independently, beside the
flags and the match timeout: `Edition.ES2020` is the grammar JSON Schema 2020-12 names, and the versions are `V13`,
`V17`, `V18` and `LATEST`. Both are resolved when the pattern is compiled.

**Validity.** `Regex.isValid` reads a pattern as compiling it would, so a pattern over a limit of size is not valid
there. `Regex.isValidPattern` asks the grammar alone, as the .NET engine's `IsValidPattern` and the Rust crate's
`is_valid_pattern` do: a pattern too large to compile is valid there when its grammar is. It reads the pattern in place
(`Validator`, the Rust crate's `validate.rs` in Java, which makes the parser's decision at each step), with the groups
and the nested classes of the v flag on stacks of its own, so no pattern can overflow the stack. The stacks are the
thread's, kept between patterns, and a property name is looked up as its place in the pattern, so once they have grown
to what its patterns need it allocates nothing. The data-set runner asks the reader about every record in every grammar
and checks its answer against V8's. This is what a JSON Schema `format: regex` check needs: the grammar, whatever an
engine's limits.

**Errors.** A pattern that does not compile throws `RegexException`, an `IllegalArgumentException`, whose `kind()` is
`INVALID`, `FLAGS` or `TOO_LARGE`; `tryCompile` and `Builder.tryBuild` return a `CompileResult` in place of throwing. A
match that reaches its limit throws `MatchLimitException` (`TIME` or `MEMORY`); bytes that are not UTF-8 throw
`IllegalArgumentException`.

**ECMA-262 algorithms.** `replace` substitutes as `GetSubstitution` does, replacing every match only with `g`, or calls
a function with each `Captures`; `replaceAll` always replaces every match. `split` follows
`RegExp.prototype[Symbol.split]`.

**Input.** A `String` is UTF-16, lone surrogates and all, with positions in code units. A `byte[]` is UTF-8, checked
before it is read and refused when it is not, never read as U+FFFD; `Utf8Text.of` checks a text once. Nothing is
transcoded.

**Allocation.** A warm `isMatch` and `captures(into, text, start)`, which writes into a `Captures` from
`newCaptures()`, allocate nothing: the working memory is the thread's, grown to what a match needs and kept up to a cap.

**Limits.** No pattern and no text can hang a program or overflow the stack.

- A match timeout per pattern, or a process-wide default (`Regex.setDefaultMatchTimeout`, or the system property
  `corvus.regex.defaultMatchTimeout`), bounds every match and caps its backtracking memory; `isMatch`, `find` and
  `captures` take a limit of their own for one match.
- Size limits on the pattern are deterministic.
- A pattern that opens many groups is read and compiled on a thread of its own with a stack for the deepest nesting the
  limits allow; the matcher keeps its choices on a stack of its own.

## 3. Engine

**One matcher, two encodings.** The matcher, the readers of the automata and the text cursor are written once, in
`src/template` (`Matcher.java.in`, `Automata.java.in`, `Text.java.in`), and expanded by `tools/expand.py` into a class
for each encoding (`Matcher8`, `Matcher16` and so on), so the JIT compiles each loop with no call through an
interface. Matching by UTF-16 code unit over UTF-8 places the position between the halves of a four-byte character at
its third byte. Every generated method is kept under the 8,000 bytes of bytecode past which HotSpot never compiles
one.

**The matcher.** A backtracking matcher over a flat program, with V8's quick check of the next character at each
choice. A memo of the places that failed, taken into use once a match has made sixty-four steps a position, makes a
pattern without backreferences linear in the text. The clock is read every 4,096 steps.

**Where a match can start.** The search layer is the Rust crate's, with Java's tools in place of `memchr` and
`aho-corasick`. Over UTF-8 the searches are SWAR (`Swar.java`: eight bytes of a `long` read and tested together); over
UTF-16 one unit by `String.indexOf`, which the JIT compiles to vector code. With the incubating Vector API added
(`--add-modules jdk.incubator.vector`, JDK 21 and later), the byte searches are vector code too (`VectorBytes.java`),
chosen when the class loads; the answers are the same either way. A literal is found by its rarest byte or by two of
its rarest positions compared over eight starts at once; several literals by their bytes at one position, by that pair
search, or by a table; a literal at a fixed or bounded distance opens windows of starts.

**The automata.** For a pattern that needs no backtracking: an automaton over ASCII for a test from the start, an
anchored leftmost-first automaton for where a match from a start ends, a reverse automaton for the terms before a
window's anchors, and a forward automaton for a pattern with no literal. Each is built on first use, with its states
bounded.

## 4. Generated code

The expanded classes are Java source checked in beside the templates, and CI checks they are current. The Unicode
tables of each version (`Tables<major>.java`, `Strings<major>.java`) and the property names of ECMAScript 2020
(`Es2020.java`) are written from the Rust crate's by `tools/unicode`, and CI checks them against the crate, so the
engines carry the same tables.

## 5. Targets and layout

Java 17 and later, built with JDK 25 (whose `javac` alone can leave out the incubating module's warning) and Maven 3.9,
and published to Maven Central as `io.github.corvus-dotnet:corvus-regex-ecma262`. CI tests on Linux (x64 and arm64) and
Windows, and runs the tests on Java 17 and 21.

- `src/main/java`: the library; `src/template`: the templates; `src/test/java`: the tests.
- `tools/expand.py`: the templates' expander; `tools/unicode`: the tables' writers; `tools/dataset`: the test-data
  runner; `tools/stackprobe`: the stack probe; `tools/benchmarks`: the benchmark harness.

## 6. Testing

**The ECMA-262 test data.** [`ecma262-regex-test-data`](https://github.com/corvus-dotnet/ecma262-regex-test-data)
holds V8's answers: validity under each grammar, `test` answers, the positions of `exec`, the global search, `y`,
`replace` and `split`, and every `exec` call of test262. `tools/dataset` runs all of it over UTF-16 and UTF-8, with none
wrong, refused or at a limit: on every change, with and without the Vector API's searches, with the memo from the first
step, and as the data's own scripts write it for Unicode 13 and 18 and for ECMAScript 2020.

**Other tests.** JUnit tests over the API, the editions, the limits, the searches and their kernels, in both modes (with
and without the Vector API); a test that a warm match allocates nothing, counting the measuring thread's allocations
over windows; the getting-started page's programs; and the stack probe, which asks 964 deep, wide and long patterns
through every method on threads with stacks of 8 MB, 1 MB and 256 KB, none overflowing.

## 7. Benchmarks

The harness image, `ghcr.io/corvus-dotnet/corvus-regex-ecma262-java`, runs the Industry workloads of
`dotnet/performance` over this library (UTF-8 and UTF-16, with and without the Vector API), `java.util.regex`, TRegex
(GraalJS's engine) and V8. The published figures are on
[the benchmark page](https://corvus-oss.org/corvus-regex-ecma262-benchmarks/), which runs every engine's image each
night; this repository publishes none of its own.

Three rows are not like for like with an engine that is not ECMA-262's, as the price of its rules under u: `.*` (`.`
excludes CR, so the CRLF text has more matches), `Sher[a-z]+|Hol[a-z]+` under i (the search must also find the long s
and the Kelvin sign), and `[a-q][^u-z]{13}x` (a supplementary character is two units, so the distance to the `x` is
not fixed).

## 8. Differences a Java user will meet

- The grammar is ECMA-262's: `(?i:...)` groups but no `(?i)` prefix, no possessive quantifiers, atomic groups, `\A`,
  `\Z`, `\z` or `\p{javaLowerCase}`. `\d` is `[0-9]`, `\w` the 63 ASCII word characters, and `.` excludes LF, CR,
  U+2028 and U+2029.
- A replacement template writes a named group as `$<name>`, and a backslash is not an escape in it.
- `replace` replaces every match only with the `g` flag; `replaceAll` always does.

The [getting-started page](getting-started.md) has the full list.
