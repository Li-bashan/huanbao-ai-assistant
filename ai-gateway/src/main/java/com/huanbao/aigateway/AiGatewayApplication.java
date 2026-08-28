package com.huanbao.aigateway;

import com.huanbao.aigateway.config.DifyHttpProperties;
import com.huanbao.aigateway.config.DifyPolicyProperties;
import com.huanbao.aigateway.config.DataQueryAdminProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({
    DifyPolicyProperties.class,
    DifyHttpProperties.class,
    DataQueryAdminProperties.class
})
public class AiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiGatewayApplication.class, args);
    }
}
