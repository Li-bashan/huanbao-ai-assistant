package com.huanbao.aigateway.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.huanbao.aigateway.config.DataQueryProperties;
import com.huanbao.aigateway.security.DataQueryIdentity;
import org.junit.jupiter.api.Test;

class DifyUserIdentityServiceTest {
    private final DifyUserIdentityService service = new DifyUserIdentityService(
        new DataQueryProperties(
            "http://dify", "key", 1000, null, "hmac-secret", "SIGNED_HEADER", "identity-secret",
            300, false, 30, 1, "USER_AUTHORIZED", false, "", true, false
        )
    );

    @Test
    void derivesStableTenantScopedOpaqueUserId() {
        DataQueryIdentity userA = identity("tenant-1", "10086");
        DataQueryIdentity sameUser = identity("tenant-1", "10086");
        DataQueryIdentity otherUser = identity("tenant-1", "10087");
        DataQueryIdentity sameIdOtherTenant = identity("tenant-2", "10086");

        String first = service.getDifyUserId(userA);
        assertEquals(first, service.getDifyUserId(sameUser));
        assertNotEquals(first, service.getDifyUserId(otherUser));
        assertNotEquals(first, service.getDifyUserId(sameIdOtherTenant));
        org.junit.jupiter.api.Assertions.assertTrue(first.startsWith("igix:"));
        org.junit.jupiter.api.Assertions.assertEquals(37, first.length());
    }

    private DataQueryIdentity identity(String tenantId, String userId) {
        return new DataQueryIdentity(userId, "U" + userId, "测试用户", "ORG-1", "组织一", tenantId, true, "SIGNED_HEADER");
    }
}
