package com.huanbao.aigateway.service;

import com.huanbao.aigateway.dto.DataQueryUserCreateRequest;
import com.huanbao.aigateway.dto.DataQueryUserResponse;
import com.huanbao.aigateway.dto.DataQueryUserUpdateRequest;
import com.huanbao.aigateway.exception.BusinessException;
import com.huanbao.aigateway.repository.DataQueryUserRepository;
import java.util.List;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class DataQueryUserService {
    private final DataQueryUserRepository repository;

    public DataQueryUserService(DataQueryUserRepository repository) {
        this.repository = repository;
    }

    public List<DataQueryUserResponse> list(String keyword, Boolean enabled) {
        String normalized = keyword == null ? null : keyword.trim();
        if (normalized != null && normalized.length() > 120) {
            throw new BusinessException("VALIDATION_ERROR", "keyword is too long");
        }
        return repository.findAll(StringUtils.hasText(normalized) ? normalized : null, enabled);
    }

    public DataQueryUserResponse create(DataQueryUserCreateRequest request) {
        validate(request.userId(), request.userName(), request.tenantId());
        try {
            return repository.insert(request);
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            throw new BusinessException("DATA_QUERY_USER_CONFLICT", "tenantId and userId must be unique");
        }
    }

    public DataQueryUserResponse update(long id, DataQueryUserUpdateRequest request) {
        if (id <= 0) throw new BusinessException("INVALID_ID", "id must be positive");
        validate(request.userId(), request.userName(), request.tenantId());
        try {
            return repository.update(id, request);
        } catch (EmptyResultDataAccessException ex) {
            throw new BusinessException("DATA_QUERY_USER_NOT_FOUND", "data query user not found");
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            throw new BusinessException("DATA_QUERY_USER_CONFLICT", "tenantId and userId must be unique");
        }
    }

    public void delete(long id) {
        if (id <= 0 || !repository.delete(id)) {
            throw new BusinessException("DATA_QUERY_USER_NOT_FOUND", "data query user not found");
        }
    }

    private void validate(String userId, String userName, String tenantId) {
        if (!StringUtils.hasText(userId)) throw new BusinessException("USER_ID_REQUIRED", "userId must not be blank");
        if (!StringUtils.hasText(userName)) throw new BusinessException("USER_NAME_REQUIRED", "userName must not be blank");
        if (!StringUtils.hasText(tenantId)) throw new BusinessException("TENANT_ID_REQUIRED", "tenantId must not be blank");
    }
}
