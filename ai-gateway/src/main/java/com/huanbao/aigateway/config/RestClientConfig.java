package com.huanbao.aigateway.config;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    @Bean("dataQueryRestClient")
    public RestClient dataQueryRestClient(DataQueryServiceProperties properties) {
        return buildClient(properties.readTimeout());
    }

    @Bean(name = "dataQueryProxyExecutor", destroyMethod = "shutdownNow")
    public ExecutorService dataQueryProxyExecutor() {
        return Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "data-query-proxy");
            thread.setDaemon(true);
            return thread;
        });
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

    private RestClient buildClient(Duration configuredTimeout) {
        long timeoutMs = configuredTimeout == null ? 60_000L : configuredTimeout.toMillis();
        int safeTimeoutMs = (int) Math.min(Math.max(timeoutMs, 1L), Integer.MAX_VALUE);
        return buildClient(safeTimeoutMs);
    }
}
