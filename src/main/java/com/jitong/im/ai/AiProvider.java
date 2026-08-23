package com.jitong.im.ai;

public interface AiProvider {

    AiSummary summarize(AiSummaryContext context);

    String model();
}
