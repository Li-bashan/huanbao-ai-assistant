package com.huanbao.aigateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "dify.office")
public record DifyOfficeProperties(String apiBase, String apiKey, int timeoutMs) {
}
