# Contributing

This guide is for someone who wants to build the library, run its tests and gates, and open a pull request. It covers
the tools, the build step by step, the gates every change must pass, and how a pull request is released. The
[design document](design.md) describes what is underneath.

## 1. Tools

| Tool | Needed for | Version |
|---|---|---|
| JDK | Everything | 25 to build (its `javac` alone leaves out the incubating module's warning); 17 and 21 to run the tests on |
| Maven | The build and the tests | 3.9 or later |
| Git | The repository and the test data | Any recent version |
| Python 3 | The templates' expander and the check of the Unicode tables against the Rust crate's | 3.10 or later |
| Node.js | The test data for other versions of Unicode and for ECMAScript 2020, and the V8 rows of the harness | 24 (the V8 it carries is the oracle) |
| PowerShell 7 | The release scripts (`tools/release`) | 7.4 or later |

- **Windows:** `winget install EclipseAdoptium.Temurin.25.JDK Apache.Maven Git.Git Python.Python.3.13 OpenJS.NodeJS.LTS
  Microsoft.PowerShell`.
- **Linux:** a JDK 25 from Adoptium or the distribution, Maven 3.9 from maven.apache.org (unpack it and put its `bin` on
  `PATH`), Git and Python 3 from the distribution, Node.js 24 from nodejs.org.
- **macOS:** `brew install openjdk@25 maven git python node@24 powershell`.

`java -version` and `mvn -version` must show JDK 25.

## 2. Getting the sources

```
git clone https://github.com/corvus-dotnet/corvus-regex-ecma262-java.git
git clone https://github.com/corvus-dotnet/ecma262-regex-test-data.git   # beside it: the test data
git clone https://github.com/corvus-dotnet/corvus-regex-ecma262-rs.git   # beside it: for the table check
cd corvus-regex-ecma262-java
```

The test data is the oracle for the engine (design, section 6). It is not vendored; the commands below assume it at
`../ecma262-regex-test-data`.

## 3. Building and testing, step by step

Every command runs from the repository root.

1. **The expanded templates.** After a change to `src/template`, run `python3 tools/expand.py`;
   `python3 tools/expand.py --check` must pass.
2. **Build and test.** `mvn verify` builds the library for Java 17 with every warning an error, and runs the tests in
   both modes (with and without the Vector API's searches), the getting-started page's programs among them.
   `mvn test -DcorvusArgLine=-Dcorvus.regex.memoFromTheStart=true` runs them with the memo of failed places taken into
   use from the first step. To run them on Java 17 or 21, add `-Djvm=<that JDK>/bin/java` to
   `mvn surefire:test@default-test surefire:test@vector`.
3. **The stack probe.**

   ```
   javac -encoding UTF-8 -Xlint:all -Werror -d tools/stackprobe/out -cp target/classes tools/stackprobe/StackProbe.java
   java -Xmx4g -cp target/classes:tools/stackprobe/out StackProbe
   ```

   (`;` in place of `:` on Windows.) None of the deep, wide and long patterns may overflow.
4. **The test data.**

   ```
   javac -encoding UTF-8 -Xlint:all -Werror -d tools/dataset/out -cp target/classes tools/dataset/Dataset.java
   java -Xss4m -cp target/classes:tools/dataset/out Dataset ../ecma262-regex-test-data/data wrong-cases.tsv
   ```

   It must report 0 wrong, 0 refused and 0 at a limit; again with `--add-modules jdk.incubator.vector` (the Vector
   API's searches) and with `-Dcorvus.regex.memoFromTheStart=true`. For the other versions of Unicode and for
   ECMAScript 2020, write the data with its own scripts and run it with the engine set to match:

   ```
   node ../ecma262-regex-test-data/unicode.js apply 13.0 /tmp/u13 ../ecma262-regex-test-data/data
   java -Xss4m -cp target/classes:tools/dataset/out Dataset /tmp/u13 wrong-u13.tsv --unicode 13
   node ../ecma262-regex-test-data/edition.js apply /tmp/es2020 "" ../ecma262-regex-test-data/data
   java -Xss4m -cp target/classes:tools/dataset/out Dataset /tmp/es2020 wrong-es2020.tsv --edition es2020
   ```

## 4. The Unicode tables

The tables of each version of Unicode (`Tables<major>.java`, `Strings<major>.java`) and the property names of
ECMAScript 2020 (`Es2020.java`) are written from the Rust crate's, so that every engine carries the same tables. After
the crate's tables change:

```
src=../corvus-regex-ecma262-rs/corvus-regex-ecma262/src
for major in 13 17 18; do
  python3 tools/unicode/gen_tables.py $src/tables$major.rs
  python3 tools/unicode/gen_strings.py $src/strings$major.rs
done
python3 tools/unicode/gen_es2020.py $src/es2020.rs
```

With `--check`, each script fails when what is checked in differs, as CI runs them at the crate's commit `ci.yml` pins.

## 5. Benchmarks

`tools/benchmarks/run.sh <workloads.json> <texts directory> <out.json>` runs this library, `java.util.regex`, TRegex
and V8 over the Industry workloads; the script lists what it needs and its settings. The published figures are on
[the benchmark page](https://corvus-oss.org/corvus-regex-ecma262-benchmarks/), which runs this repository's harness
image each night beside every other engine; a run on another machine is comparable with itself, never with the page.

TRegex's compliance is worked out once (`TREGEX_COMPLIANCE=1`) and checked in as
`tools/benchmarks/compliance-tregex.json`, with the commit of the test data it was made at; CI fails when that commit is
not the one it checks out.

## 6. What a pull request needs

- **The gates of section 3** passing on the change as pushed: the templates, the build and tests in both modes, the
  stack probe, and the test data at 0 wrong in every setting.
- **The case in the test data first** for a change to the engine's semantics: add the pattern to the data repository's
  family it belongs to, regenerate with its `generate.js`, check with `verify.js`, and pin the new data commit in
  `ci.yml`. V8 is the oracle; a fault V8 has is marked in the data, never copied into the engine.
- **A failing test first** for a bug: the test that reproduces it, then the fix.
- **A measured run** for a change made for speed, with the rows it moved.
- **The design document kept current**, describing the library as it is.
- **Javadoc** on every public class and member, since the API reference on javadoc.io is generated from it.
- **The getting-started page** kept true: its programs are run by the tests.

### Conventions

- Java 17, every warning an error, no dependencies.
- Nothing on the match path allocates once warm, and the allocation test holds that line.
- A change to the matcher or the automata is made in the templates, never in the expanded classes, and no method grows
  past the 8,000 bytes of bytecode HotSpot compiles.

## 7. Releases

Every pull request merged into main releases the library with the next patch version, unless it is labelled
`no_release`: `auto-release.yml` makes a GitHub release tagged `v<version>` when `pom.xml` or `src/main/` changed since
the last tag, and `maven-publish.yml` writes the version into `pom.xml`, runs the tests and the test data, and signs and
publishes the library to Maven Central, then asks javadoc.io to sync the version's javadoc. A change to the benchmark
harness alone is a harness release, which publishes the harness image. A pull request that needs a minor or major
version sets `nextVersion` in `.github/release.json`; a minor version is a breaking change. The documentation site takes
the published documents (the getting-started page, the API reference, the design document and this guide) from `main`,
and they describe the latest release: a change to them alone is merged with `no_release`, and a change to the API
carries its documents, which its release brings into step.

`main` is protected by a ruleset: changes arrive by pull request, and the `CI gate` and `Harness image` checks must be
green on the branch as it will merge.
