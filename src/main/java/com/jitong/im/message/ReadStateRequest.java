package com.jitong.im.message;

import jakarta.validation.constraints.NotNull;

record ReadStateRequest(
        @NotNull Long readSeq
) {
}
