package io.github.corvusdotnet.regex.ecma262;

/**
 * The version of Unicode whose character database a pattern is read with ({@link Regex.Builder#unicodeVersion}): the
 * members of each {@code \p{...}}, the characters of a group name, and the case folding of the i flag. It is
 * independent of the {@link Edition}.
 */
public enum UnicodeVersion {
    /** The version of the V8 the answers are held to, Unicode 17 today. It moves when that V8 moves. */
    DEFAULT,
    /** Unicode 13.0, the version of the year of ECMAScript 2020. */
    V13,
    /** Unicode 17.0. */
    V17,
    /** Unicode 18.0. */
    V18,
    /** The latest version the library carries, Unicode 18 today. It moves when a later version is added. */
    LATEST;

    /**
     * The version this one stands for: {@link #DEFAULT} is V8's ({@link #V17}) and {@link #LATEST} the newest the
     * library carries ({@link #V18}). Any other is itself.
     */
    public UnicodeVersion resolve() {
        switch (this) {
            case DEFAULT:
                return V17;
            case LATEST:
                return V18;
            default:
                return this;
        }
    }

    /** The major number of the version, resolved: 13, 17 or 18. */
    public int major() {
        switch (resolve()) {
            case V13:
                return 13;
            case V18:
                return 18;
            default:
                return 17;
        }
    }

    /** The tables of the version, resolved. */
    Ucd ucd() {
        return Ucd.byMajor(major());
    }
}
