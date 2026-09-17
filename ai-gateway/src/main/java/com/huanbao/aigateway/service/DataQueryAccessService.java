package com.huanbao.aigateway.service;

import com.huanbao.aigateway.config.DataQueryProperties;
import com.huanbao.aigateway.dto.DataQueryAccessResponse;
import com.huanbao.aigateway.dto.DataQueryAuthorization;
import com.huanbao.aigateway.exception.BusinessException;
import com.huanbao.aigateway.repository.DataQueryUserRepository;
import com.huanbao.aigateway.security.DataQueryIdentity;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class DataQueryAccessService {
    public static final String ALL_ORGANIZATIONS_WILDCARD = "*";

    private static final Logger log = LoggerFactory.getLogger(DataQueryAccessService.class);

    private final DataQueryUserRepository repository;
    private final DataQueryProperties properties;

    public DataQueryAccessService(DataQueryUserRepository repository, DataQueryProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    public DataQueryAuthorization requireCovered(DataQueryIdentity identity) {
        validateIdentity(identity);
        String tenantId = normalizeTenant(identity.tenantId());
        if (properties.accessOpenToAll()) {
            log.info("DATA_QUERY_ACCESS_OPEN_TO_ALL userIdHash={} tenantId={}",
                shortHash(identity.userId()), tenantId);
            return new DataQueryAuthorization(
                identity.userId(),
                identity.userCode(),
                identity.userName(),
                identity.orgCode(),
                identity.orgName(),
                tenantId,
                identity.verified(),
                identity.source(),
                "ALL",
                properties.allowGroupRanking(),
                List.of(),
                List.of(ALL_ORGANIZATIONS_WILDCARD),
                true
            );
        }
        Optional<com.huanbao.aigateway.dto.DataQueryUserAccess> access =
            repository.findEnabledAccess(tenantId, identity.userId());
        if (access.isEmpty() && "BODY_TRIAL".equals(identity.source())) {
            access = repository.findEnabledAccessByUserName(identity.userName());
        }
        if (access.isEmpty()) {
            log.info("DATA_QUERY_ACCESS_DENIED userIdHash={} tenantId={}",
                shortHash(identity.userId()), tenantId);
            throw new BusinessException(
                "DATA_QUERY_NOT_COVERED",
                "当前账号无权使用智能问数或身份映射尚未完成"
            );
        }

        var value = access.get();
        return new DataQueryAuthorization(
            identity.userId(),
            identity.userCode(),
            identity.userName(),
            value.orgCode() == null ? identity.orgCode() : value.orgCode(),
            value.orgName() == null ? identity.orgName() : value.orgName(),
            tenantId,
            identity.verified(),
            identity.source(),
            value.scopeType(),
            value.allowGroupRanking(),
            value.allowedIndicatorCodes(),
            value.allowedOrgCodes(),
            value.allowAllOrganizations()
        );
    }

    public DataQueryAccessResponse check(DataQueryIdentity identity) {
        DataQueryAuthorization authorization = requireCovered(identity);
        return new DataQueryAccessResponse(
            true,
            authorization.userName(),
            authorization.identityVerified(),
            authorization.identitySource(),
            authorization.userId(),
            authorization.organizationScope(),
            authorization.allowedOrgCodes(),
            authorization.allowedIndicatorCodes(),
            authorization.allowGroupRanking(),
            authorization.allowAllOrganizations()
        );
    }

    /** Name-only access is retained solely for the old audit-query API. */
    @Deprecated
    public DataQueryAccessResponse check(String userName) {
        String normalized = normalize(userName);
        if (!StringUtils.hasText(normalized)) {
            throw new BusinessException("CURRENT_USER_MISSING", "current user name is required");
        }
        boolean covered = repository.existsByUserNameAndEnabled(normalized, true);
        return new DataQueryAccessResponse(covered, normalized);
    }

    @Deprecated
    public boolean isCovered(String userName) {
        return check(userName).covered();
    }

    @Deprecated
    public void requireCovered(String userName) {
        DataQueryAccessResponse result = check(userName);
        if (!result.covered()) {
            throw new BusinessException("DATA_QUERY_NOT_COVERED", "legacy user-name access is not covered");
        }
    }

    public String normalize(String userName) {
        String normalized = userName == null ? "" : userName.trim();
        if (normalized.length() > 80) {
            throw new BusinessException("INVALID_USER_NAME", "userName must be at most 80 characters");
        }
        return normalized;
    }

    private void validateIdentity(DataQueryIdentity identity) {
        if (identity == null || !StringUtils.hasText(identity.userId())) {
            throw new BusinessException("UNAUTHENTICATED", "authenticated userId is required");
        }
        if (!StringUtils.hasText(identity.userName())) {
            throw new BusinessException("CURRENT_USER_MISSING", "authenticated userName is required");
        }
    }

    private String normalizeTenant(String tenantId) {
        return StringUtils.hasText(tenantId) ? tenantId.trim() : "default";
    }

    private String shortHash(String value) {
        int hash = value == null ? 0 : value.hashCode();
        return Integer.toHexString(hash);
    }
}
