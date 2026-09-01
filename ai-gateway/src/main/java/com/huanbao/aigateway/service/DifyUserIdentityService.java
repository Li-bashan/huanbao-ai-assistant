package com.huanbao.aigateway.service;

import com.huanbao.aigateway.config.DataQueryProperties;
import com.huanbao.aigateway.exception.BusinessException;
import com.huanbao.aigateway.security.DataQueryIdentity;
import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class DifyUserIdentityService {
    private final DataQueryProperties properties;

    public DifyUserIdentityService(DataQueryProperties properties) {
        this.properties = properties;
    }

    public String getDifyUserId(DataQueryIdentity identity) {
        String tenantId = StringUtils.hasText(identity.tenantId()) ? identity.tenantId().trim() : "default";
        return "igix:" + hmac(tenantId + ":" + identity.userId()).substring(0, 32);
    }

    public String auditHash(String difyUserId) {
        return hmac(difyUserId);
    }

    private String hmac(String value) {
        String secret = StringUtils.hasText(properties.difyUserHmacSecret())
            ? properties.difyUserHmacSecret() : properties.identitySecret();
        if (!StringUtils.hasText(secret)) {
            throw new BusinessException("DIFY_USER_CONFIG_MISSING", "Dify user HMAC secret is not configured");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            StringBuilder result = new StringBuilder();
            for (byte item : mac.doFinal(value.getBytes(StandardCharsets.UTF_8))) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (Exception ex) {
            throw new BusinessException("DIFY_USER_CONFIG_MISSING", "Dify user HMAC is unavailable");
        }
    }
}
