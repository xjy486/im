package com.jitong.im.ai;

public interface AiProvider {

    AiProviderResult summarize(AiSummaryContext context);

    String model();
}
