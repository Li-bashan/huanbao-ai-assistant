package com.huanbao.dataquery.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/** 为意图分类器和公文拟写创建带硬超时边界的 Spring 6 RestClient。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(VllmProperties.class)
public class VllmRestClientConfig {

    /** 公文拟写阶段的硬读取边界，必须和流水线降级契约保持一致。 */
    public static final Duration DOCUMENT_REQUEST_TIMEOUT = Duration.ofSeconds(10);

    @Bean(name = "vllmRestClient")
    public RestClient vllmRestClient(RestClient.Builder builder, VllmProperties properties) {
        String baseUrl = properties.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("vllm.base-url must not be blank");
        }

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(DOCUMENT_REQUEST_TIMEOUT);
        requestFactory.setReadTimeout(DOCUMENT_REQUEST_TIMEOUT);
        return builder
                .baseUrl(stripTrailingSlash(baseUrl.trim()))
                .requestFactory(requestFactory)
                .build();
    }

    private static String stripTrailingSlash(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }
}
