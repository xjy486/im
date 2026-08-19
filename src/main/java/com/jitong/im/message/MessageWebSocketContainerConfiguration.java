package com.jitong.im.message;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
class MessageWebSocketContainerConfiguration {

    // Leave enough headroom for the handler to return a stable FRAME_TOO_LARGE
    // error instead of Tomcat closing a modestly oversized business frame.
    static final int MAX_MESSAGE_BYTES = 1024 * 1024;

    @Bean
    ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(MAX_MESSAGE_BYTES);
        container.setMaxBinaryMessageBufferSize(MAX_MESSAGE_BYTES);
        return container;
    }
}
