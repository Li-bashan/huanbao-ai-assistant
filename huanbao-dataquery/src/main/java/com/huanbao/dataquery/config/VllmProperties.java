package com.huanbao.dataquery.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** vLLM OpenAI 兼容接口配置。密钥不在本服务中落地。 */
@ConfigurationProperties(prefix = "vllm")
public class VllmProperties {

    private String baseUrl = "http://121.237.178.23:9002/v1";
    private String model = "Qwen3.8-27B";

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }
}
