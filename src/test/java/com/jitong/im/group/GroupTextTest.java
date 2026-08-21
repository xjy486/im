package com.jitong.im.group;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GroupTextTest {

    @Test
    void normalizes_whitespace_and_case_for_search() {
        assertThat(GroupText.normalize("  JITONG\t群  "))
                .isEqualTo("jitong 群");
    }
}
