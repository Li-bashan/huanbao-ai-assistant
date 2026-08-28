package com.huanbao.aigateway.service;

import com.huanbao.aigateway.dto.DataQueryAccessResponse;
import com.huanbao.aigateway.exception.BusinessException;
import com.huanbao.aigateway.repository.DataQueryUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class DataQueryAccessService {
    private static final Logger log = LoggerFactory.getLogger(DataQueryAccessService.class);

    private final DataQueryUserRepository repository;

    public DataQueryAccessService(DataQueryUserRepository repository) {
        this.repository = repository;
    }

    public DataQueryAccessResponse check(String userName) {
        String normalized = normalize(userName);
        if (!StringUtils.hasText(normalized)) {
            throw new BusinessException("CURRENT_USER_MISSING", "current user name is required");
        }

        boolean covered = isCovered(normalized);
        log.info("DATA_QUERY_ACCESS_CHECK userName={} covered={}", normalized, covered);
        return new DataQueryAccessResponse(covered, normalized);
    }

    public boolean isCovered(String userName) {
        String normalized = normalize(userName);
        return StringUtils.hasText(normalized)
            && repository.existsByUserNameAndEnabled(normalized, true);
    }

    public void requireCovered(String userName) {
        String normalized = normalize(userName);
        if (!StringUtils.hasText(normalized)) {
            throw new BusinessException("CURRENT_USER_MISSING", "current user name is required");
        }
        if (!repository.existsByUserNameAndEnabled(normalized, true)) {
            log.info("DATA_QUERY_ACCESS_DENIED userName={}", normalized);
            throw new BusinessException(
                "DATA_QUERY_NOT_COVERED",
                "current user is not covered by data query access"
            );
        }
    }

    public String normalize(String userName) {
        String normalized = userName == null ? "" : userName.trim();
        if (normalized.length() > 80) {
            throw new BusinessException("INVALID_USER_NAME", "userName must be at most 80 characters");
        }
        return normalized;
    }
}
