package com.huanbao.aigateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "dify.policy")
public record DifyPolicyProperties(
    String apiBase,
    String apiKey,
    int timeoutMs
) {
}
