package com.huanbao.aigateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "dify.master")
public record DifyMasterProperties(String apiBase, String apiKey, int timeoutMs) {
}
