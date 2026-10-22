package io.github.corvusdotnet.regex.ecma262;

import java.util.Arrays;

/**
 * What a match captured: the match as group 0, then each capturing group, which may not have taken part. One made by
 * {@link Regex#newCaptures()} can be filled again and again by {@link Regex#captures(Captures, String, int)} without
 * allocating.
 */
public final class Captures {
    String text16;
    byte[] text8;
    final int[] slots;
    final GroupInfo info;

    Captures(GroupInfo info) {
        this.info = info;
        this.slots = new int[2 * (info.groups + 1)];
        Arrays.fill(slots, -1);
    }

    /** The number of groups, the whole match among them. */
    public int length() {
        return slots.length / 2;
    }

    /** Whether the group {@code g} took part in the match (0 is the whole match). */
    public boolean matched(int g) {
        return g >= 0 && g < length() && slots[2 * g] >= 0;
    }

    /** Where the group {@code g} starts, or -1 when it did not take part. */
    public int start(int g) {
        return slots[2 * g];
    }

    /** Where the group {@code g} ends, or -1 when it did not take part. */
    public int end(int g) {
        return slots[2 * g + 1];
    }

    /** The group {@code g}, or {@code null} when it did not take part or there is no such group. */
    public Match get(int g) {
        return matched(g) ? new Match(text16, text8, slots[2 * g], slots[2 * g + 1]) : null;
    }

    /** The text of the group {@code g} (see {@link Match#value()}), or {@code null} when it did not take part. */
    public String group(int g) {
        if (!matched(g)) {
            return null;
        }
        return text16 != null ? text16.substring(slots[2 * g], slots[2 * g + 1])
                : Text8.string(text8, slots[2 * g], slots[2 * g + 1]);
    }

    /** The number of the group of the name that took part, or -1. */
    public int named(String name) {
        int[] groups = info.named(name, 0, name.length());
        if (groups != null) {
            for (int g : groups) {
                if (slots[2 * g] >= 0) {
                    return g;
                }
            }
        }
        return -1;
    }

    /** The group of the name that took part, or {@code null}. */
    public Match get(String name) {
        int g = named(name);
        return g < 0 ? null : get(g);
    }

    /** The text of the group of the name that took part, or {@code null}. */
    public String group(String name) {
        int g = named(name);
        return g < 0 ? null : group(g);
    }

    /** The whole match. */
    public Match match() {
        return get(0);
    }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder("[");
        for (int g = 0; g < length(); g++) {
            out.append(g == 0 ? "" : ", ").append(matched(g) ? slots[2 * g] + ".." + slots[2 * g + 1] : "None");
        }
        return out.append(']').toString();
    }
}
