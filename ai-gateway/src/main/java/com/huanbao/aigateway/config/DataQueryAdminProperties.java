package com.huanbao.aigateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "data-query")
public record DataQueryAdminProperties(String adminToken) {
}
