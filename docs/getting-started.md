# Getting started with Java

## Introduction

`io.github.corvus-dotnet:corvus-regex-ecma262` is a Java engine whose patterns answer as ECMAScript does. Where
`java.util.regex` has its own grammar and its own meaning for `\d`, `\w`, `\s`, `.`, case folding and Unicode
properties, this engine has ECMA-262's, so a pattern written for JavaScript, or taken from a JSON Schema, matches the
same texts here as it does there. It is a port of the corvus-regex-ecma262 Rust crate, held to V8's answers on the
ECMA-262 test data, with no dependencies.

This guide covers:

- **Setup**: the Maven or Gradle dependency, and the vector module.
- **Patterns and flags**: the flags are ECMA-262's letters, and what each one does.
- **The edition and the version of Unicode**: the grammar of ECMAScript 2020, and the Unicode tables a pattern reads.
- **Matching**: `isMatch`, `find`, `captures`, the global search, `replace` and `split`, and the forms that allocate
  nothing.
- **Timeouts**: the limit of time and of memory a match may take.
- **UTF-8 and UTF-16**: a `String` as UTF-16, as JavaScript holds one, and a `byte[]` or `Utf8Text` as UTF-8.
- **Differences from java.util.regex**: what to expect when the two meet.

## Setup

From [Maven Central](https://central.sonatype.com/artifact/io.github.corvus-dotnet/corvus-regex-ecma262):

```xml
<dependency>
  <groupId>io.github.corvus-dotnet</groupId>
  <artifactId>corvus-regex-ecma262</artifactId>
  <version>0.1.2</version>
</dependency>
```

or with Gradle, `implementation("io.github.corvus-dotnet:corvus-regex-ecma262:0.1.2")`. It needs Java 17 or later.
Started with `--add-modules jdk.incubator.vector` on JDK 21 or later, the library searches a vector at a time; without
it, eight bytes at a time. The answers are the same.

```java
import io.github.corvusdotnet.regex.ecma262.Regex;
```

## Patterns and flags

A pattern is written as it would be in JavaScript, and the flags are the letters `RegExp` takes, as a string or as
`Flags`:

| Flag | `Flags` | Meaning |
|---|---|---|
| `d` | `hasIndices()` | Accepted for completeness; indices are always kept. |
| `g` | `global()` | `replace` replaces every match; without it, the first, as `String.prototype.replace` does. |
| `i` | `ignoreCase()` | Canonicalize of the mode in force: simple case folding with `u` or `v`, `toUpperCase` without. Never the locale. |
| `m` | `multiline()` | `^` and `$` at line terminators. |
| `s` | `dotAll()` | The dot matches line terminators. |
| `u` | `unicode()` | The `u` flag grammar; matched by code point. |
| `v` | `unicodeSets()` | Set notation and properties of strings; matched by code point. `u` and `v` together is an error, as in ECMA-262. |
| `y` | `sticky()` | A match only at the start position. |

```java
import io.github.corvusdotnet.regex.ecma262.Regex;
import java.util.List;

class PatternsExample {
    public static void main(String[] args) {
        Regex a = Regex.compile("\\p{Script=Greek}+", "u");
        Regex b = Regex.compile("[[a-z]--[aeiou]]+", "v");
        Regex c = Regex.compile("^(?<year>\\d{4})-(?<month>\\d{2})$", "u");

        check(a.isMatch("αβγ"));
        check(b.isMatch("rhythm"));
        check(c.groupNames().equals(List.of("year", "month")));
    }

    static void check(boolean ok) {
        if (!ok) throw new AssertionError();
    }
}
```

With no flag the pattern is read with the Annex B grammar and matched by UTF-16 code unit, which is what ECMA-262 does
with no flag. JSON Schema's reading, the `u` flag first and Annex B when that refuses, is two calls of
`Regex.tryCompile`, which costs no exception and takes the match timeout, so no default timeout is needed:

```java
import io.github.corvusdotnet.regex.ecma262.CompileResult;
import io.github.corvusdotnet.regex.ecma262.Regex;
import java.time.Duration;

class SchemaExample {
    static CompileResult schemaPattern(String pattern) {
        CompileResult result = Regex.tryCompile(pattern, "u", Duration.ofSeconds(1));
        return result.isOk() ? result : Regex.tryCompile(pattern, "", Duration.ofSeconds(1));
    }

    public static void main(String[] args) {
        check(schemaPattern("^\\p{L}+$").orElseThrow().flags().unicode());
        // An identity escape, which only Annex B allows.
        check(!schemaPattern("\\-").orElseThrow().flags().unicode());
    }

    static void check(boolean ok) {
        if (!ok) throw new AssertionError();
    }
}
```

A pattern that is not valid throws `RegexException`, an `IllegalArgumentException`, whose `kind()` is `INVALID` and
whose message says why; invalid flags are `FLAGS`. Every pattern has deterministic limits of size, and `TOO_LARGE`
says when one is passed, with the `limit()` it is over. `tryCompile` gives the same exception in its `error()`.

```java
import io.github.corvusdotnet.regex.ecma262.Regex;
import io.github.corvusdotnet.regex.ecma262.RegexException;
import java.time.Duration;

class ErrorsExample {
    public static void main(String[] args) {
        try {
            Regex.compile("a{2,1}", "u");
            throw new AssertionError("a pattern that is not valid was compiled");
        } catch (RegexException e) {
            check(e.kind() == RegexException.Kind.INVALID);
        }
        check(Regex.tryCompile("a", "uv", Duration.ofSeconds(1)).error().get().kind() == RegexException.Kind.FLAGS);
    }

    static void check(boolean ok) {
        if (!ok) throw new AssertionError();
    }
}
```

## The edition and the version of Unicode

JSON Schema 2020-12 names the grammar of ECMAScript 2020. `Edition.ES2020` refuses what later editions added, and
nothing else: the `d` and `v` flags, modifier groups such as `(?i:...)`, a group name used by two groups, and a
property name or value that is not in the 2020 tables (such as `\p{Script=Yezidi}`). A pattern it accepts has the same
answers as in today's grammar.

The version of Unicode is chosen apart from the edition: `UnicodeVersion.V13`, `V17`, `V18`, `LATEST` (18 today) or
`DEFAULT`, the version of the V8 the answers are held to (17 today). It decides the members of each `\p{...}`, the
characters of a group name and the case folding of the `i` flag. `unicodeVersion()` reports the version a pattern was
compiled with. Both are set on a `Regex.builder` and read when the pattern is compiled, so a match costs the same
whichever is chosen.

```java
import io.github.corvusdotnet.regex.ecma262.Edition;
import io.github.corvusdotnet.regex.ecma262.Flags;
import io.github.corvusdotnet.regex.ecma262.Regex;
import io.github.corvusdotnet.regex.ecma262.UnicodeVersion;
import java.time.Duration;

class EditionExample {
    public static void main(String[] args) {
        // ECMAScript 2020 as its edition was published, with the Unicode of its year.
        Regex greek = Regex.builder("^\\p{Script=Greek}+$")
                .flags("u")
                .matchTimeout(Duration.ofSeconds(1))
                .edition(Edition.ES2020)
                .unicodeVersion(UnicodeVersion.V13)
                .build();
        check(greek.isMatch("αβγ") && greek.unicodeVersion() == UnicodeVersion.V13);

        // Vithkuqi is in Unicode 14 and later, so not in Unicode 13.
        check(!Regex.isValid("\\p{Script=Vithkuqi}", Flags.parse("u"), Edition.CURRENT, UnicodeVersion.V13));
    }

    static void check(boolean ok) {
        if (!ok) throw new AssertionError();
    }
}
```

## Matching

The methods are those of a `RegExp`, with the position as an argument where JavaScript keeps `lastIndex`:

```java
import io.github.corvusdotnet.regex.ecma262.Captures;
import io.github.corvusdotnet.regex.ecma262.Match;
import io.github.corvusdotnet.regex.ecma262.Regex;
import java.util.ArrayList;
import java.util.List;

class MatchingExample {
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

        // replace replaces every match with the g flag, and the first without it, with every $ form of ECMA-262, or a function.
        check(date.replace(text, "$<month>/$1").equals("due 10/2026, then 01/2027"));
        check(date.replace(text, (Captures each) -> each.group("year")).equals("due 2026, then 2027"));

        // split puts what the groups captured between the pieces; a group that did not take part is null.
        check(Regex.compile("\\s*(;)\\s*").split("a ; b;c").equals(List.of("a", ";", "b", ";", "c")));
    }

    static void check(boolean ok) {
        if (!ok) throw new AssertionError();
    }
}
```

`find(text, start)` is `exec` with `lastIndex` set to `start`, and `y` anchors it there. `find`, `captures`, `replace`
and `split` allocate what they return. `isMatch` and `captures(into, text, start)`, which writes a match's groups into
a `Captures` from `newCaptures()`, reused from match to match, allocate nothing once the working memory has grown:

```java
import io.github.corvusdotnet.regex.ecma262.Captures;
import io.github.corvusdotnet.regex.ecma262.Regex;

class CapturesExample {
    public static void main(String[] args) {
        Regex date = Regex.compile("(?<year>\\d{4})-(?<month>\\d{2})", "u");
        Captures into = date.newCaptures();
        check(date.captures(into, "due 2026-10", 0));
        check(into.start(1) == 4 && into.end(1) == 8);
    }

    static void check(boolean ok) {
        if (!ok) throw new AssertionError();
    }
}
```

A `Regex` holds no mutable state, so one instance is safe to share between threads.

## Timeouts

A match has no limit by default. A pattern compiled with a match timeout abandons a match that reaches it, or that
reaches the cap on backtracking memory the timeout brings, with `MatchLimitException`, whose `kind()` is `TIME` or
`MEMORY`, whatever the pattern:

```java
import io.github.corvusdotnet.regex.ecma262.MatchLimitException;
import io.github.corvusdotnet.regex.ecma262.Regex;
import java.time.Duration;

class TimeoutsExample {
    public static void main(String[] args) {
        Regex slow = Regex.compile("^(?:(a+)\\1*)+$", "", Duration.ofMillis(100));
        check(slow.matchTimeout().equals(Duration.ofMillis(100)));
        try {
            slow.isMatch("a".repeat(60) + "b");
            throw new AssertionError("the match did not stop at its timeout");
        } catch (MatchLimitException e) {
            check(e.kind() == MatchLimitException.Kind.TIME);
        }
    }

    static void check(boolean ok) {
        if (!ok) throw new AssertionError();
    }
}
```

A pattern compiled without a timeout takes `Regex.defaultMatchTimeout()`, which is none unless
`Regex.setDefaultMatchTimeout` (or the system property `corvus.regex.defaultMatchTimeout`) sets it. A program that
matches patterns or texts from outside should set one, for example with
`Regex.setDefaultMatchTimeout(Duration.ofSeconds(1))` when it starts. `isMatch`, `find` and `captures` take a limit of
their own for one match.

A pattern without a backreference is matched in time linear in the text, and no pattern can overflow the stack, so the
timeout is what bounds the others.

## UTF-8 and UTF-16

A `String` is UTF-16, as JavaScript holds a string, lone surrogates and all, with positions in code units as
`String.charAt` counts them. A `byte[]` is UTF-8, matched as its bytes with no transcoding, with positions in bytes. It
is checked before it is read: bytes that are not UTF-8 throw `IllegalArgumentException`, and are never matched or read
as U+FFFD. `Utf8Text.of` checks a text once, for a text matched many times.

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

Under the `u` and `v` flags a character is a code point in both encodings, so a class, a dot or a quantifier takes a
four-byte sequence as one character. With no flag ECMA-262 matches by UTF-16 code unit; over UTF-8 the engine keeps
that meaning, so a match can start or end between the halves of a character beyond the Basic Multilingual Plane, at
the character's third byte.

## Differences from java.util.regex

The methods follow `RegExp`; the meaning follows ECMA-262. The differences a user coming from `java.util.regex` will
meet:

- **The grammar.** No `(?i)` inline option (ECMA-262 has `(?i:...)` groups), no atomic groups, no possessive
  quantifiers, no `\A`, `\Z` or `\z`, no `\p{javaLowerCase}` classes, and no conditionals. The flags are a separate
  string.
- **The character classes.** `\d` is `[0-9]`, `\w` is the 63 ASCII word characters, `\s` is ECMAScript's white space
  and line terminators, and `.` excludes the four line terminators (LF, CR, U+2028, U+2029).
- **Replacement.** A template writes a group as `$1` or `$<name>`, not `${name}`, and `$&`, `` $` `` and `$'` are the
  match and the text before and after it; a backslash is not an escape in it. `replace` replaces every match only with
  the `g` flag, and `replaceAll` always does.
- **Split.** `split` is JavaScript's, which puts what the groups captured between the pieces and keeps empty pieces at
  the end.
- **Timeouts.** `java.util.regex` has no limit on a match's time, and can overflow the stack. Here no pattern can, a
  pattern without a backreference is matched in time linear in the text, and a timeout bounds the others.
