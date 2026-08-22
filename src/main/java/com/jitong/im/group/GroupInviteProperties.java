package com.jitong.im.group;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("jitong.group")
public record GroupInviteProperties(
        String inviteDeepLinkBase
) {
}
