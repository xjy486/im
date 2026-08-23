package com.jitong.im.ai;

public interface AiProvider {

    AiProviderResult<AiSummary> summarize(AiSummaryContext context);

    AiProviderResult<AiSmartReplies> smartReplies(AiSummaryContext context);

    AiProviderResult<AiExtraction> extractInformation(AiSummaryContext context);

    String model();
}
