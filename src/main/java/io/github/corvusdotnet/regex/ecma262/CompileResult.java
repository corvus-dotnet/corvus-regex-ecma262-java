package io.github.corvusdotnet.regex.ecma262;

import java.util.Optional;

/**
 * What {@link Regex.Builder#tryBuild()} and {@link Regex#tryCompile} give: the compiled pattern, or why it was not
 * compiled, without an exception being thrown. A validator that compiles patterns from outside takes this form, with a
 * match timeout of its own, and needs neither {@link Regex#setDefaultMatchTimeout} nor a catch.
 */
public final class CompileResult {
    private final Regex regex;
    private final RegexException error;

    private CompileResult(Regex regex, RegexException error) {
        this.regex = regex;
        this.error = error;
    }

    static CompileResult ok(Regex regex) {
        return new CompileResult(regex, null);
    }

    static CompileResult failed(RegexException error) {
        return new CompileResult(null, error);
    }

    /** Whether the pattern was compiled. */
    public boolean isOk() {
        return regex != null;
    }

    /** The compiled pattern, or empty when it was not compiled. */
    public Optional<Regex> regex() {
        return Optional.ofNullable(regex);
    }

    /**
     * Why the pattern was not compiled (its {@link RegexException#kind()}: {@code INVALID}, {@code FLAGS} or
     * {@code TOO_LARGE}), or empty when it was.
     */
    public Optional<RegexException> error() {
        return Optional.ofNullable(error);
    }

    /** The compiled pattern, or the exception of why it was not compiled, thrown. */
    public Regex orElseThrow() {
        if (error != null) {
            throw error;
        }
        return regex;
    }
}
