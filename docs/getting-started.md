# Getting started with Java

`io.github.corvus-dotnet:corvus-regex-ecma262` is a Java engine, with no dependencies, whose patterns have the grammar
and the answers of JavaScript's `RegExp`, held to V8's answers on the ECMA-262 test data. A Java `String` is matched as
UTF-16, as JavaScript holds a string, and a `byte[]` as UTF-8.

## Install

From [Maven Central](https://central.sonatype.com/artifact/io.github.corvus-dotnet/corvus-regex-ecma262), for Java 17
and later:

```xml
<dependency>
  <groupId>io.github.corvus-dotnet</groupId>
  <artifactId>corvus-regex-ecma262</artifactId>
  <version>0.1.2</version>
</dependency>
```

or with Gradle, `implementation("io.github.corvus-dotnet:corvus-regex-ecma262:0.1.2")`. Started with
`--add-modules jdk.incubator.vector` on JDK 21 or later, the library searches a vector at a time; without it, eight
bytes at a time. The answers are the same.

## Compile a pattern

`Regex.compile` takes a pattern and its flags as `new RegExp(pattern, flags)` takes them: `d`, `g`, `i`, `m`, `s`, `u`,
`v` and `y`. Without `u` or `v` a pattern is read by the Annex B grammar and matched by UTF-16 code unit, as ECMA-262
reads it; with them, by code point. A pattern that is not valid throws `RegexException`, whose `kind()` says why;
`tryCompile` returns a `CompileResult` in place of throwing.

```java
import io.github.corvusdotnet.regex.ecma262.CompileResult;
import io.github.corvusdotnet.regex.ecma262.Regex;
import io.github.corvusdotnet.regex.ecma262.RegexException;
import java.time.Duration;

class CompileExample {
    public static void main(String[] args) {
        Regex date = Regex.compile("(?<year>\\d{4})-(?<month>\\d{2})", "gu");
        check(date.capturesLength() == 3);

        try {
            Regex.compile("a{2,1}", "u");
            throw new AssertionError("a pattern that is not valid was compiled");
        } catch (RegexException e) {
            check(e.kind() == RegexException.Kind.INVALID);
        }

        CompileResult result = Regex.tryCompile("a", "uv", Duration.ofSeconds(1));
        check(!result.isOk() && result.error().get().kind() == RegexException.Kind.FLAGS);
    }

    static void check(boolean ok) {
        if (!ok) throw new AssertionError();
    }
}
```

## Match

Positions in a `String` are UTF-16 code units, as `String.charAt` counts them.

```java
import io.github.corvusdotnet.regex.ecma262.Captures;
import io.github.corvusdotnet.regex.ecma262.Match;
import io.github.corvusdotnet.regex.ecma262.Regex;
import java.util.ArrayList;
import java.util.List;

class MatchExample {
    public static void main(String[] args) {
        Regex date = Regex.compile("(?<year>\\d{4})-(?<month>\\d{2})", "gu");
        String text = "due 2026-10, then 2027-01";

        check(date.isMatch(text));

        Match m = date.find(text);
        check(m.start() == 4 && m.end() == 11 && m.value().equals("2026-10"));

        Captures c = date.captures(text);
        check(c.group("year").equals("2026") && c.group(2).equals("10"));

        // Every match of a global search, as matchAll makes it.
        List<String> all = new ArrayList<>();
        for (Match each : date.findAll(text)) {
            all.add(each.value());
        }
        check(all.equals(List.of("2026-10", "2027-01")));

        // replace replaces every match with the g flag, and the first without it, with every $ form of ECMA-262.
        check(date.replace(text, "$<month>/$1").equals("due 10/2026, then 01/2027"));

        // split splices in what the groups captured.
        check(Regex.compile("\\s*(;)\\s*").split("a ; b;c").equals(List.of("a", ";", "b", ";", "c")));
    }

    static void check(boolean ok) {
        if (!ok) throw new AssertionError();
    }
}
```

## UTF-16 and UTF-8

A `String` is UTF-16, lone surrogates and all. A `byte[]` is UTF-8, with positions in bytes, and is checked before it
is read: bytes that are not UTF-8 throw `IllegalArgumentException`, and are never matched or read as U+FFFD.
`Utf8Text.of` checks a text once, for a text matched many times.

```java
import io.github.corvusdotnet.regex.ecma262.Match;
import io.github.corvusdotnet.regex.ecma262.Regex;
import io.github.corvusdotnet.regex.ecma262.Utf8Text;
import java.nio.charset.StandardCharsets;

class EncodingsExample {
    public static void main(String[] args) {
        Regex dotB = Regex.compile(".b", "u");

        Match m16 = dotB.find("a😀b");
        check(m16.start() == 1 && m16.end() == 4);         // UTF-16 code units

        byte[] utf8 = "a😀b".getBytes(StandardCharsets.UTF_8);
        Match m8 = dotB.find(utf8);
        check(m8.start() == 1 && m8.end() == 6);           // bytes of UTF-8
        check(dotB.isMatch(Utf8Text.of(utf8)));

        try {
            dotB.isMatch(new byte[] {(byte) 0xFF});
            throw new AssertionError("bytes that are not UTF-8 were matched");
        } catch (IllegalArgumentException e) {
            // refused before any of it is read
        }
    }

    static void check(boolean ok) {
        if (!ok) throw new AssertionError();
    }
}
```

## Patterns and texts from outside

A match has no limit by default. A program that matches a pattern or a text it did not write gives the pattern a match
timeout, as a .NET `Regex` has: a match that runs past it, or whose backtracking memory reaches its cap, throws
`MatchLimitException`. No pattern can overflow the stack. `Regex.setDefaultMatchTimeout` (or the system property
`corvus.regex.defaultMatchTimeout`) sets the timeout of every pattern compiled after it, and `isMatch`, `find` and
`captures` take a limit of their own.

```java
import io.github.corvusdotnet.regex.ecma262.MatchLimitException;
import io.github.corvusdotnet.regex.ecma262.Regex;
import java.time.Duration;

class LimitsExample {
    public static void main(String[] args) {
        Regex slow = Regex.compile("^(?:(a+)\\1*)+$", "", Duration.ofMillis(100));
        try {
            slow.isMatch("a".repeat(60) + "b");
            throw new AssertionError("the match did not stop at its timeout");
        } catch (MatchLimitException e) {
            // abandoned at the timeout
        }
    }
}
```

## The edition and the version of Unicode

JSON Schema 2020-12 names the grammar of ECMAScript 2020. `Edition.ES2020` refuses what later editions added: the `d`
and `v` flags, modifier groups, a group name used twice, and property names outside the 2020 tables. The version of
Unicode is chosen apart from it: `UnicodeVersion.V13`, `V17`, `V18`, `LATEST` (18) or `DEFAULT` (V8's, 17). Both are
read when the pattern is compiled, so a match costs the same whatever they are.

```java
import io.github.corvusdotnet.regex.ecma262.Edition;
import io.github.corvusdotnet.regex.ecma262.Flags;
import io.github.corvusdotnet.regex.ecma262.Regex;
import io.github.corvusdotnet.regex.ecma262.UnicodeVersion;
import java.time.Duration;

class EditionExample {
    public static void main(String[] args) {
        Regex greek = Regex.builder("^\\p{Script=Greek}+$")
                .flags("u")
                .matchTimeout(Duration.ofSeconds(1))
                .edition(Edition.ES2020)
                .unicodeVersion(UnicodeVersion.V13)
                .build();
        check(greek.isMatch("αβγ"));

        // Vithkuqi is in Unicode 14 and later, so not in Unicode 13.
        check(!Regex.isValid("\\p{Script=Vithkuqi}", Flags.parse("u"), Edition.CURRENT, UnicodeVersion.V13));
    }

    static void check(boolean ok) {
        if (!ok) throw new AssertionError();
    }
}
```

## Differences from java.util.regex

- The grammar is ECMA-262's. A group is named `(?<name>...)` in both, but the flags are a string as `RegExp` takes
  them, and `(?i:...)` groups set them inside a pattern; there is no `(?i)` prefix for the whole pattern, no possessive
  quantifiers, no atomic groups, no `\A`, `\Z` or `\z`, and no `\p{javaLowerCase}` classes.
- `\d` is `[0-9]`, `\w` the 63 ASCII word characters, `\s` ECMAScript's white space and line terminators, and `.`
  excludes LF, CR, U+2028 and U+2029.
- A replacement template writes a named group as `$<name>`, not `${name}`, and `$&`, `` $` `` and `$'` are the match
  and the text before and after it; a backslash is not an escape in it.
- `replace` replaces every match only with the `g` flag; `replaceAll` always does.
- `split` puts what the groups captured between the pieces, and keeps empty pieces at the end, as JavaScript's does.
- `java.util.regex` has no limit on a match's time. Here a pattern without a backreference is matched in time linear in
  the text, and a pattern from outside should be given a timeout, which bounds every match.

## Where next

- [The API reference](https://javadoc.io/doc/io.github.corvus-dotnet/corvus-regex-ecma262) on javadoc.io.
- [The README](../README.md).
- [The benchmark page](https://corvus-oss.org/corvus-regex-ecma262-benchmarks/), with every engine measured nightly.
