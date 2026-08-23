package com.jitong.im.ai;

import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResponseErrorHandler;

import java.io.IOException;
import java.net.URI;

@SuppressWarnings("removal")
final class AiProviderResponseErrorHandler implements ResponseErrorHandler {

    @Override
    public boolean hasError(ClientHttpResponse response) throws IOException {
        return response.getStatusCode().isError();
    }

    @Override
    public void handleError(
            URI url,
            HttpMethod method,
            ClientHttpResponse response
    ) throws IOException {
        handleError(response);
    }

    @Override
    public void handleError(ClientHttpResponse response) throws IOException {
        int status = response.getStatusCode().value();
        String message = "AI provider returned HTTP " + status;
        if (status == 408 || status == 429 || status >= 500 && status <= 599) {
            throw new TransientAiException(message);
        }
        throw new NonTransientAiException(message);
    }
}
