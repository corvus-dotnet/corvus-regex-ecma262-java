# Port checklist: the search layer

Every optimisation of sections 4.3 and 4.4 of the design of the .NET reference engine
([Corvus.Regex.Ecma262](https://github.com/corvus-dotnet/Corvus.Regex.Ecma262), `docs/design/design.md`), and the
working-memory parts of section 4.2 they rest on, then every item the Rust crate added beyond them
([corvus-regex-ecma262-rs](https://github.com/corvus-dotnet/corvus-regex-ecma262-rs), `docs/port-checklist.md`), each
marked **done**, **not applicable** (and why) or **to do**. This engine is a port of the Rust crate at v0.1.3, so the
numbers are the crate's. Section 5 (the source generator) is not applicable to this port as a whole: generated code is
deferred, as the plan records.

The Rust crate takes its searches from `memchr` and `aho-corasick`, whose kernels are vector code. Java 17 has no vector
API outside an incubator module, so the searches here are SWAR (eight bytes of a `long` read at a time and tested
together, `Swar.java`) over UTF-8, and `String.indexOf`, which the JIT compiles to vector code, over UTF-16. Where that
does a thing another way, or less well than the crate, the entry says so. How each search compares with the crate's,
and with the other engines on the JVM, is for the benchmark harness of step 3 to measure.

| Count | |
|---|---|
| Done | 49 |
| Not applicable | 6 |
| To do | 0 |

## Section 4.2: the program and the working memory

| # | Optimisation | State | In this port |
|---|---|---|---|
| 1 | Flat program, per-thread working memory grown to what a match needs and kept up to a cap | done | `State`, one a thread, its stack, groups, loop counters and memo kept up to caps; a warm match allocates nothing (`AllocationTest`, which counts the measuring thread's bytes with escape analysis off). |
| 2 | The memo of failed places, so a pattern without backreferences is linear | done | `State.memo`, taken into use once a match has made sixty-four steps a position. |
| 3 | The clock read every 4,096 steps, and a match that ends before the first check never reads it | done | `State.CHECK_EVERY`. |
| 4 | The quick check of the next character at every choice, as V8 makes it | done | `Compiler.setQuick`. |
| 5 | A counted loop gives up where the rest cannot fit | done | `Compiler.setNeeds`, in bytes of UTF-8 and in UTF-16 code units. |
| 6 | A repetition whose body cannot consume runs once or not at all | done | `Compiler.simplify`. |
| 7 | Small counted repetitions written out | done | `Compiler.simplify` and the unroll, as the crate writes them. |

## Section 4.3: finding where to start

| # | Optimisation | State | In this port |
|---|---|---|---|
| 8 | An anchored pattern starts only at 0 | done | `Program.anchored`. |
| 9 | A literal every match starts with, found by an anchored search | done | Over UTF-8 the pair search of item 52 on the literal's two rarest bytes (`ByteFinder.Pair`, in place of `memmem`); over UTF-16 `String.indexOf(String, int)`. |
| 10 | The needle by the literal's rarest positions, compared over a vector of starts, the literal in full only where they agree | done | Another way: `ByteFinder.Pair` picks the two rarest positions by `memchr`'s table of byte frequencies and compares them at eight starts a `long`, the literal in full only at a candidate. |
| 11 | The lazy third anchor | not applicable | As in the crate: the pair search compares two positions and then the literal. |
| 12 | A unit rare enough alone searched for by the one-value span search | done | `ByteFinder.One` (`Swar.indexOf`); over UTF-16 `String.indexOf(int, int)`. |
| 13 | Over UTF-16, the span search, or the fingerprint search where both ends are common letters | done | Another way: `String.indexOf` for one literal, and for several the packed search of item 45. |
| 14 | A literal at a fixed distance from the start, the start found by walking back over the characters | done | The windows of item 20, with one distance. |
| 15 | A literal at a bounded range of distances | done | The windows of item 20. |
| 16 | A literal after a run of disjoint unbounded loops, with a leading `\b`, the runs walked back to the earliest start | done | `Plan.AFTER_LOOPS` (`searchAfterLoops`). |
| 17 | Several literals at once, by a cost model of one or two positions | done | Another way: up to four literals with one rare first byte by the search for it (`ByteFinder.Shared`); literals two of whose positions take four masked tests or fewer by the pair search; any others by `ByteFinder.Table`, a table of the bytes at their rarest position read a byte at a time. |
| 18 | The pairs of first characters of a sequence of classes as a multi-needle | done | Another way, as the crate: a class of up to four characters is one character of an anchor with several forms. |
| 19 | Fingerprints (Teddy): buckets, nibble tables, up to three positions | not applicable | Teddy rests on a byte shuffle, which no Java 17 code outside the incubating vector API reaches. The pair search's masked tests take what Teddy took in the crate where two positions have few values (a word's cases, a few names); a wider set goes to `ByteFinder.Table`. |
| 20 | The literal every alternative holds, at a fixed or bounded distance, the occurrences opening windows of starts tried in order and never twice | done | `Plan.WINDOWS` (`searchWindows`), through nested alternations. |
| 21 | The case-insensitive search: a letter's forms in one search, masks joining the forms that differ in a bit | done | The forms are written out into literals, and the pair search compares the two cases of a letter (or of a Cyrillic lead byte) with one masked test, as the reference joins them. |
| 22 | The long s and the Kelvin sign, whose UTF-8 is longer than s and k, looked for apart | done | Another way, as the crate: they are forms like any other. Over UTF-16 the packed search takes their low bytes in the one search. |
| 23 | The fingerprint search's positions chosen by rarity | done | Another way: the pair search and the table choose their positions by `memchr`'s table of byte frequencies. |
| 24 | The first set found by a vectorised search for its units | done | Over UTF-8 `Search.FirstBytes`: up to three bytes by SWAR, up to sixty-four by a table a byte at a time. |
| 25 | The same over UTF-16 | done | Another way: the first set's units as up to eight ranges merged as the crate merges them (`Search.firstUnits`); one unit by `String.indexOf(int, int)`, any other set a unit at a time. |
| 26 | A failed start skips the rest of its leading loop's run | done | `Search.skipRun`. |
| 27 | A start inside a word skipped where the pattern starts with `\b` and every first character is a word character | done | `Search.leadingWordBoundary`. |
| 28 | A minimum length rules out short rests | done | `Program.need` and `need16`. |
| 29 | Simple shapes answered without the matcher: a literal alone | done | `Shape.LITERALS`. |
| 30 | Simple shapes: one class alone, a sequence of classes, one unbounded loop, an alternation of literals, a case-insensitive ASCII literal | done | `Shape.SET`, `Shape.LOOP` (its run's end by `RunEnd`), `Shape.LITERALS` and `Shape.FORMS`, answered before the thread's memory is taken (`Matcher.shapeSearch`). A sequence of classes is left to the anchored automaton, as in the crate. |
| 31 | What an enumeration carries: the range known to hold no rare line terminator, so a run of `.` is one search for LF and CR | done | Another way, as the crate: a run of `.` is one SWAR search for LF, CR and the lead byte of U+2028 and U+2029 (`RunEnd.LINE`); over UTF-16 a search for the four units. |
| 32 | What an enumeration carries: the range known to hold no form the search leaves out | not applicable | No form is left out of the search (item 22). |
| 33 | The give-back of a single-character loop by a scan for the units what follows can start with | done | `Compiler.setBack`: where what follows a greedy loop can start with one to three ASCII bytes alone, the give-back goes back to the last of them (`lastOf`), a byte at a time. |
| 34 | The search's kernels as static methods with constants as arguments, no call inside | not applicable | A .NET JIT concern. The matcher and the readers of the automata are expanded once for each encoding (`tools/expand.py`), so no call through an interface is left in them. |

## Section 4.4: the automaton

| # | Optimisation | State | In this port |
|---|---|---|---|
| 35 | An automaton over ASCII for a pattern that needs no backtracking, built on first use, its steps bounded | done | `Dfa`, for `isMatch` from the start of the text. |
| 36 | The automaton takes `\b` and `\B` | done | `Dfa` and `Adfa`. Not a boundary of a case-insensitive pattern with the u flag, which folds. |
| 37 | One table read per unit for both encodings | done | The automata read UTF-8 bytes and UTF-16 units alike. |
| 38 | An automaton over full Unicode | not applicable | Neither the reference nor the crate has one. |

## Beyond the reference: level with the `regex` crate

| # | Optimisation | State | In this port |
|---|---|---|---|
| 39 | Simple shapes and the dot's run by `memchr` | done | Items 30 and 31, by SWAR. |
| 40 | An anchored, leftmost-first automaton over ASCII for where a match from a start ends | done | `Adfa.build`, read by `Automata8` and `Automata16`: the states premultiplied by the stride, a match ending the lower threads; it gives up at the first unit beyond ASCII. Used for a pattern with no group and no backreference, at every start the search tries (`Matcher.run`). |
| 41 | The terms before a window's anchors read backwards from each occurrence, so that only the starts they allow are tried | done | `Adfa.reverse`, run by `searchReverse`, the starts it allows held as a mask of 128 bits in two `long`s. |
| 42 | Case-insensitive anchors cut to one length by `regex`'s literal rule | done | `Search.counts` and `utf8Cuts`. |
| 43 | Two literals by the automaton only when they start with the same rare byte | done | Another way: two to four literals with one rare first byte by `ByteFinder.Shared`; any other pair by the pair search. |
| 44 | A failed start of a leading loop's run passes over the occurrences in the rest of the run | done | `searchAfterLoops` with one loop. |
| 45 | Over UTF-16, literals found by searches of the code units themselves | done | Another way: one literal by `String.indexOf(String, int)`; up to four with one rare first unit by `String.indexOf(int, int)` and a compare of each; several by `UnitSearch.findPacked`, which packs a chunk of units to their low bytes (`String.getBytes`, a copy for a Latin-1 string), searches them with the UTF-8 search of the literals' low bytes, and compares each candidate with the literals in the `String`. The chunk packed last is kept on the thread, keyed by the `String` itself, which cannot change, so it stays true for the next search of that text by any pattern. |
| 46 | The anchor and what follows it checked at each occurrence before any start is walked back to | done | `Search.RestCheck`, dropped for good when fewer than one in eight of its first sixty-four checks ruled an occurrence out. |
| 47 | The reverse automaton's table premultiplied, and the look for a nearer occurrence made without a search call on a few bytes | done | `Adfa.Reverse` and `Literals.findWithin`. |
| 48 | Up to four literals with one rare first byte found by a search for it and a compare of each | done | `ByteFinder.Shared`. |
| 49 | Literals with forms written out in full answered by the search alone | done | `Shape.LITERALS`; an alternation of literals with forms cut short is compared at each occurrence by the anchored automaton where the text is ASCII (`Shape.FORMS`). |
| 50 | A pattern with no literal read once by a forward automaton, not tried at every start its first set allows | done | `Adfa.buildForward`, built on the first search that wants it (`Program.forward`), read by `Automata.scan` from the scan. |

## Beyond the reference: the C library level with the crate

| # | Optimisation | State | In this port |
|---|---|---|---|
| 51 | The matches of a global search a batch at a time | not applicable | A fix for the cost of a call across the C library's boundary; this port has no C library, and a global search through `Matches` calls the matcher directly. |

## Beyond the reference: what PCRE2 and V8 do

| # | Optimisation | State | In this port |
|---|---|---|---|
| 52 | A few literals found by two of their byte positions, each holding a few values, compared over a vector of starts (PCRE2's `fast_forward_char_pair_simd`) | done | `ByteFinder.Pair`: the rarest pair of positions whose values are apart, each a few masked tests (two values that differ in one bit are one test), eight starts a `long`; a word's cases written out are known a literal once the bytes pass the sets of each position. It takes up to four tests a position, and one literal too. |
| 53 | One literal with a byte rare in text found by a search for that byte | done | `ByteFinder.Rare`, for a literal holding one of `ZXQKVJzqjx`. |
| 54 | A window of positions read one unit at a time before the first set is searched for (V8's Boyer-Moore lookahead) | done | `Adfa.skipWindow` and `Automata.skipAhead`, for a pattern the forward automaton reads. |
| 55 | Where every match starts with one literal, the automaton goes on after an occurrence rather than reading the literal again | done | `Adfa.Anchored.after` and `Automata8.findAfter`, over UTF-8. |

## The stack

A pattern that nests deeply is read, and every tree walk made of it, on a thread with a stack of 64 MB
(`Regex.onADeepStack`); the matcher keeps its choices on a stack of its own. `tools/stackprobe/StackProbe.java` asks
the engine about the patterns the crate's stack probe asks about (deep, wide and long, in every grammar, through every
member over UTF-8 and UTF-16) on threads with stacks of 8 MB, 1 MB and 256 KB. It answers 964 probes as the crate's
does: 1,122 answered, 1,770 refused, no fault.
