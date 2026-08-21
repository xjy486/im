package com.jitong.im.group;

import java.util.Locale;

enum GroupVisibility {
    PUBLIC,
    UNLISTED,
    PRIVATE;

    static GroupVisibility parse(String value) {
        if (value == null) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    boolean searchableByName() {
        return this == PUBLIC;
    }

    boolean searchableByGroupNo() {
        return this != PRIVATE;
    }
}
