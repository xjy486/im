package com.jitong.im.group;

import java.util.Locale;

final class GroupText {

    private GroupText() {
    }

    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }
}
