package com.jitong.im.group;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GroupInviteProperties.class)
class GroupConfiguration {
}
