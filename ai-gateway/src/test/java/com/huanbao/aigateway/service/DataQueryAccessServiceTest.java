package com.huanbao.aigateway.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.huanbao.aigateway.config.DataQueryProperties;
import com.huanbao.aigateway.dto.DataQueryAccessResponse;
import com.huanbao.aigateway.dto.DataQueryAuthorization;
import com.huanbao.aigateway.dto.DataQueryUserAccess;
import com.huanbao.aigateway.exception.BusinessException;
import com.huanbao.aigateway.repository.DataQueryUserRepository;
import com.huanbao.aigateway.security.DataQueryIdentity;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DataQueryAccessServiceTest {
    private static final DataQueryIdentity IDENTITY = new DataQueryIdentity(
        "user-1", "U1", "张三", "ORG-1", "组织一", "tenant-1", true, "SIGNED_HEADER"
    );

    private final DataQueryUserRepository repository = org.mockito.Mockito.mock(DataQueryUserRepository.class);
    private final DataQueryProperties properties = defaultProperties(false);
    private final DataQueryAccessService service = new DataQueryAccessService(repository, properties);

    @Test
    void trimsNameAndReturnsCoveredForEnabledUser() {
        when(repository.existsByUserNameAndEnabled("张三", true)).thenReturn(true);

        DataQueryAccessResponse result = service.check("  张三 ");

        assertEquals("张三", result.userName());
        assertTrue(result.covered());
        verify(repository).existsByUserNameAndEnabled("张三", true);
    }

    @Test
    void disabledOrMissingUserIsNotCovered() {
        when(repository.existsByUserNameAndEnabled("李四", true)).thenReturn(false);

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> service.requireCovered("李四")
        );

        assertEquals("DATA_QUERY_NOT_COVERED", exception.getCode());
    }

    @Test
    void missingNameFailsClosedWithBusinessCode() {
        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> service.requireCovered("  ")
        );

        assertEquals("CURRENT_USER_MISSING", exception.getCode());
    }

    @Test
    void identityWithoutAuthorizationRowIsRejectedByDefault() {
        when(repository.findEnabledAccess("tenant-1", "user-1")).thenReturn(Optional.empty());

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> service.requireCovered(IDENTITY)
        );

        assertEquals("DATA_QUERY_NOT_COVERED", exception.getCode());
    }

    @Test
    void identityWithAuthorizationRowKeepsDatabaseScope() {
        DataQueryUserAccess access = new DataQueryUserAccess(
            "user-1", "U1", "张三", "tenant-1", "租户一", "oid-1", "ORG-1", "组织一",
            true, "RESOLVED", "COMPANY", List.of("ORG-1"), List.of(), false, false
        );
        when(repository.findEnabledAccess("tenant-1", "user-1")).thenReturn(Optional.of(access));

        DataQueryAuthorization authorization = service.requireCovered(IDENTITY);

        assertEquals("COMPANY", authorization.organizationScope());
        assertEquals(List.of("ORG-1"), authorization.allowedOrgCodes());
        assertFalse(authorization.allowAllOrganizations());
    }

    @Test
    void openToAllGrantsEveryIdentityTheAuthorizedOrgScope() {
        DataQueryAccessService openService = new DataQueryAccessService(
            repository, defaultProperties(true));
        when(repository.findOpenScopeOrgCodes()).thenReturn(List.of("10004024", "10004025"));

        DataQueryAuthorization authorization = openService.requireCovered(IDENTITY);

        assertEquals("ALL", authorization.organizationScope());
        assertEquals(List.of("10004024", "10004025"), authorization.allowedOrgCodes());
        assertTrue(authorization.allowAllOrganizations());
        assertEquals("user-1", authorization.userId());
        verify(repository).findOpenScopeOrgCodes();
        verify(repository, org.mockito.Mockito.never()).findEnabledAccess(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void openToAllFallsBackToWildcardWhenNoAuthorizedScopeExists() {
        DataQueryAccessService openService = new DataQueryAccessService(
            repository, defaultProperties(true));
        when(repository.findOpenScopeOrgCodes()).thenReturn(List.of());

        DataQueryAuthorization authorization = openService.requireCovered(IDENTITY);

        assertEquals(List.of(DataQueryAccessService.ALL_ORGANIZATIONS_WILDCARD), authorization.allowedOrgCodes());
        assertTrue(authorization.allowAllOrganizations());
    }

    @Test
    void openToAllStillRequiresAuthenticatedIdentity() {
        DataQueryAccessService openService = new DataQueryAccessService(
            repository, defaultProperties(true));

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> openService.requireCovered(new DataQueryIdentity(
                "", "", "张三", "ORG-1", "组织一", "tenant-1", true, "SIGNED_HEADER"))
        );

        assertEquals("UNAUTHENTICATED", exception.getCode());
        verifyNoInteractions(repository);
    }

    private static DataQueryProperties defaultProperties(boolean accessOpenToAll) {
        return new DataQueryProperties(
            "unused", "unused", 1000, null, null, "SIGNED_HEADER", "identity-secret",
            300, false, 30, 1, "USER_AUTHORIZED", false, "", true, accessOpenToAll
        );
    }
}
