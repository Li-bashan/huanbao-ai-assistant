package com.huanbao.aigateway.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "dataquery")
public record DataQueryServiceProperties(Service service, Duration readTimeout) {
    public DataQueryServiceProperties {
        service = service == null ? new Service("http://127.0.0.1:8089") : service;
        readTimeout = readTimeout == null || readTimeout.isZero() || readTimeout.isNegative()
            ? Duration.ofSeconds(60)
            : readTimeout;
    }

    public String executeUrl(String path) {
        String baseUrl = service.url();
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://127.0.0.1:8089";
        }
        String normalizedBase = baseUrl.endsWith("/")
            ? baseUrl.substring(0, baseUrl.length() - 1)
            : baseUrl;
        return normalizedBase + (path.startsWith("/") ? path : "/" + path);
    }

    public record Service(String url) {
    }
}
