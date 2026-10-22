package io.github.corvusdotnet.regex.ecma262;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** What every match of a pattern shares: the number of groups and their names ({@code GroupInfo} of regex.rs). */
final class GroupInfo {
    /** A group name, in UTF-16 and in UTF-8, and the groups that have it (more than one only in separate alternatives). */
    static final class Name {
        final String name;
        final byte[] name8;
        final int[] groups;

        Name(String name, int[] groups) {
            this.name = name;
            this.name8 = name.getBytes(StandardCharsets.UTF_8);
            this.groups = groups;
        }
    }

    final int groups;
    final Name[] names;

    GroupInfo(int groups, List<Parser.GroupName> parsed) {
        this.groups = groups;
        this.names = new Name[parsed.size()];
        for (int k = 0; k < names.length; k++) {
            Parser.GroupName g = parsed.get(k);
            StringBuilder name = new StringBuilder(g.name.size());
            for (int j = 0; j < g.name.size(); j++) {
                name.appendCodePoint(g.name.get(j));
            }
            names[k] = new Name(name.toString(), g.groups);
        }
    }

    /** The groups that have the name, or {@code null}. */
    int[] named(CharSequence name, int from, int to) {
        for (Name n : names) {
            if (n.name.length() == to - from && n.name.contentEquals(name.subSequence(from, to))) {
                return n.groups;
            }
        }
        return null;
    }

    /** The groups that have the name, given in UTF-8, or {@code null}. */
    int[] named(byte[] name, int from, int to) {
        for (Name n : names) {
            if (n.name8.length == to - from
                    && java.util.Arrays.equals(n.name8, 0, n.name8.length, name, from, to)) {
                return n.groups;
            }
        }
        return null;
    }
}
