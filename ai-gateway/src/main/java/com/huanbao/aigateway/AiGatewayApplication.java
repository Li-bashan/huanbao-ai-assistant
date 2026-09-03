package com.huanbao.aigateway;

import com.huanbao.aigateway.config.DifyPolicyProperties;
import com.huanbao.aigateway.config.DifyOfficeProperties;
import com.huanbao.aigateway.config.DifyMasterProperties;
import com.huanbao.aigateway.config.DataQueryAdminProperties;
import com.huanbao.aigateway.config.DataQueryProperties;
import com.huanbao.aigateway.config.DataQueryServiceProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({
    DifyPolicyProperties.class,
    DifyOfficeProperties.class,
    DifyMasterProperties.class,
    DataQueryAdminProperties.class,
    DataQueryProperties.class,
    DataQueryServiceProperties.class
})
public class AiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiGatewayApplication.class, args);
    }
}
