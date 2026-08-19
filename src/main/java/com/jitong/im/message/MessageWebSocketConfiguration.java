package com.jitong.im.message;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
class MessageWebSocketConfiguration implements WebSocketConfigurer {

    private final MessageWebSocketHandler handler;
    private final MessageWebSocketHandshakeInterceptor handshakeInterceptor;

    MessageWebSocketConfiguration(
            MessageWebSocketHandler handler,
            MessageWebSocketHandshakeInterceptor handshakeInterceptor
    ) {
        this.handler = handler;
        this.handshakeInterceptor = handshakeInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/api/v1/ws")
                .addInterceptors(handshakeInterceptor);
    }
}
