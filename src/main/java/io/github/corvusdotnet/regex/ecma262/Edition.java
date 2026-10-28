package io.github.corvusdotnet.regex.ecma262;

/** The edition of ECMA-262 whose grammar a pattern is read with ({@link Regex.Builder#edition}). */
public enum Edition {
    /** Today's grammar, ECMAScript 2025: the default. */
    CURRENT,
    /**
     * The grammar of ECMAScript 2020 (ECMA-262, 11th edition), which JSON Schema 2020-12 names. It refuses what later
     * editions added, and nothing else: the d and v flags, modifier groups such as {@code (?i:...)}, a group name used
     * by two groups, and a property name or value that is not in the edition's tables. A pattern it accepts has the
     * same answers as in today's grammar, under the same version of Unicode.
     */
    ES2020
}
