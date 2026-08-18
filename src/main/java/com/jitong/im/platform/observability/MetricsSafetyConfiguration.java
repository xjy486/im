package com.jitong.im.platform.observability;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration(proxyBeanMethods = false)
class MetricsSafetyConfiguration {

    @Bean
    MeterFilter sensitiveUriTagFilter() {
        return new MeterFilter() {
            @Override
            public Meter.Id map(Meter.Id id) {
                List<Tag> safeTags = id.getTags().stream()
                        .map(tag -> tag.getKey().equals("uri")
                                ? Tag.of(tag.getKey(), safeUriTag(tag.getValue()))
                                : tag)
                        .toList();
                return id.replaceTags(safeTags);
            }
        };
    }

    private static String safeUriTag(String value) {
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return "EXTERNAL";
        }
        int queryStart = value.indexOf('?');
        return queryStart >= 0 ? value.substring(0, queryStart) : value;
    }
}
