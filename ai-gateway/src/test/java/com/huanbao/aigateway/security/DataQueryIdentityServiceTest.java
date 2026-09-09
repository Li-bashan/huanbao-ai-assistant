package com.huanbao.aigateway.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huanbao.aigateway.config.DataQueryProperties;
import com.huanbao.aigateway.dto.DataQueryChatRequest;
import com.huanbao.aigateway.dto.DataQueryUserContext;
import com.huanbao.aigateway.exception.BusinessException;
import com.huanbao.aigateway.dto.MasterChatRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class DataQueryIdentityServiceTest {
    private static final String SECRET = "test-secret";

    @Test
    void bodyIdentityIsRejectedUnlessTrialModeIsExplicitlyEnabled() {
        DataQueryChatRequest request = request();

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> service("SIGNED_HEADER", false).resolve(request, "", "", "")
        );

        assertEquals("IDENTITY_UNVERIFIED", exception.getCode());
        DataQueryIdentity trial = service("BODY_TRIAL", true).resolve(request, "", "", "");
        assertFalse(trial.verified());
        assertEquals("BODY_TRIAL", trial.source());
        assertEquals("user-1", trial.userId());
    }

    @Test
    void signedIdentityMustHaveValidHmacAndTimestamp() throws Exception {
        long timestamp = Instant.now().getEpochSecond();
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(
            "{\"userId\":\"user-1\",\"userName\":\"张三\",\"orgCode\":\"ORG-1\",\"tenantId\":\"tenant-1\"}"
                .getBytes(StandardCharsets.UTF_8)
        );
        String signature = hmac(timestamp + "." + encoded);

        DataQueryIdentity identity = service("SIGNED_HEADER", false)
            .resolve(request(), encoded, String.valueOf(timestamp), signature);

        assertTrue(identity.verified());
        assertEquals("SIGNED_HEADER", identity.source());
        assertEquals("tenant-1", identity.tenantId());

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> service("SIGNED_HEADER", false).resolve(request(), encoded, String.valueOf(timestamp), "bad")
        );
        assertEquals("IDENTITY_UNVERIFIED", exception.getCode());
    }

    @Test
    void permissiveModeDefaultsToConfiguredDemoUserWhenIdentityIsAbsent() {
        DataQueryChatRequest request = new DataQueryChatRequest(
            "查询发电量", "", "request-1", null, java.util.Map.of(), null
        );

        DataQueryIdentity identity = service("PERMISSIVE", false)
            .resolve(request, "", "", "");

        assertFalse(identity.verified());
        assertEquals("PERMISSIVE", identity.source());
        assertEquals("liu_haopeng", identity.userId());
        assertEquals("刘昊澎", identity.userName());
    }

    @Test
    void permissiveModePrefersClientUserIdHeader() {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        servletRequest.addHeader("X-User-Id", "chi_quanhu");
        DataQueryChatRequest request = new DataQueryChatRequest(
            "查询发电量", "", "request-1", null, java.util.Map.of(), null
        );

        DataQueryIdentity identity = service("PERMISSIVE", false)
            .resolve(request, "", "", "", servletRequest);

        assertEquals("chi_quanhu", identity.userId());
        assertEquals("迟全虎", identity.userName());
        assertFalse(identity.verified());
    }

    @Test
    void permissiveModeCanonicalizesPortalNameAndIgnoresUntrustedTenant() {
        DataQueryChatRequest request = new DataQueryChatRequest(
            "查询发电量", "", "request-1",
            new DataQueryUserContext(
                "portal-internal-id", "portal-code", "刘昊澎",
                "portal-org", "门户组织", "企业信息系统"
            ),
            java.util.Map.of(), null
        );

        DataQueryIdentity identity = service("PERMISSIVE", false)
            .resolve(request, "", "", "");

        assertEquals("liu_haopeng", identity.userId());
        assertEquals("刘昊澎", identity.userName());
        assertEquals("default", identity.tenantId());
    }

    @Test
    void permissiveModeDoesNotDowngradePartiallyProvidedSignedIdentity() {
        DataQueryChatRequest request = new DataQueryChatRequest(
            "查询发电量", "", "request-1", null, java.util.Map.of(), null
        );

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> service("PERMISSIVE", false).resolve(request, "signed", "", "")
        );

        assertEquals("IDENTITY_UNVERIFIED", exception.getCode());
    }

    @Test
    void permissiveModeResolvesMasterRequestFromUntrustedContext() {
        MasterChatRequest request = new MasterChatRequest(
            "写一份系统维护通知", "", new DataQueryUserContext(
                "portal-user", "portal-code", "迟全虎", "ORG-1", "组织一", "企业信息系统"
            ), java.util.Map.of(), null
        );

        DataQueryIdentity identity = service("PERMISSIVE", false)
            .resolve(request, "", "", "");

        assertFalse(identity.verified());
        assertEquals("PERMISSIVE", identity.source());
        assertEquals("chi_quanhu", identity.userId());
        assertEquals("迟全虎", identity.userName());
    }

    private DataQueryChatRequest request() {
        return new DataQueryChatRequest(
            "查询发电量", "", "request-1",
            new DataQueryUserContext("user-1", "U1", "张三", "ORG-1", "组织一", "tenant-1"),
            java.util.Map.of(), null
        );
    }

    private DataQueryIdentityService service(String mode, boolean allowTrial) {
        return new DataQueryIdentityService(
            new ObjectMapper(),
            new DataQueryProperties("http://dify", "key", 1000, mode, SECRET, 300,
                allowTrial, 30, "USER_AUTHORIZED", false, "", false)
        );
    }

    private String hmac(String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        StringBuilder hex = new StringBuilder();
        for (byte item : mac.doFinal(value.getBytes(StandardCharsets.UTF_8))) {
            hex.append(String.format("%02x", item));
        }
        return hex.toString();
    }
}
