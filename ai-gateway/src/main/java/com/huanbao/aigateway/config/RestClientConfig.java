package com.huanbao.aigateway.config;

import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    @Bean("difyPolicyRestClient")
    public RestClient difyPolicyRestClient(DifyPolicyProperties properties) {
        return buildClient(properties.timeoutMs());
    }

    @Bean("difyDataQueryRestClient")
    public RestClient difyDataQueryRestClient(DataQueryProperties properties) {
        return buildClient(properties.safeTimeoutMs());
    }

    @Bean("difyOfficeRestClient")
    public RestClient difyOfficeRestClient(DifyOfficeProperties properties) {
        return buildClient(properties.timeoutMs());
    }

    @Bean("difyMasterRestClient")
    public RestClient difyMasterRestClient(DifyMasterProperties properties) {
        return buildClient(properties.timeoutMs());
    }

    private RestClient buildClient(int configuredTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        int timeout = configuredTimeout > 0 ? configuredTimeout : (int) Duration.ofSeconds(30).toMillis();
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);

        return RestClient.builder()
            .requestFactory(requestFactory)
            .build();
    }
}
