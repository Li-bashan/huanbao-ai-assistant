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
    private final DataQueryAccessService accessService;

    public DataQueryUserService(
        DataQueryUserRepository repository,
        DataQueryAccessService accessService
    ) {
        this.repository = repository;
        this.accessService = accessService;
    }

    public List<DataQueryUserResponse> list(String keyword, Boolean enabled) {
        String normalizedKeyword = keyword == null ? null : accessService.normalize(keyword);
        return repository.findAll(
            StringUtils.hasText(normalizedKeyword) ? normalizedKeyword : null,
            enabled
        );
    }

    public DataQueryUserResponse create(DataQueryUserCreateRequest request) {
        String userName = requireUserName(request.userName());
        return repository.insert(userName, request.enabled() == null || request.enabled(), normalizeRemark(request.remark()));
    }

    public DataQueryUserResponse update(long id, DataQueryUserUpdateRequest request) {
        if (id <= 0) {
            throw new BusinessException("INVALID_ID", "id must be positive");
        }
        String userName = requireUserName(request.userName());
        try {
            return repository.update(
                id,
                userName,
                request.enabled(),
                normalizeRemark(request.remark())
            );
        } catch (EmptyResultDataAccessException ex) {
            throw new BusinessException("DATA_QUERY_USER_NOT_FOUND", "data query user not found");
        }
    }

    public void delete(long id) {
        if (id <= 0 || !repository.delete(id)) {
            throw new BusinessException("DATA_QUERY_USER_NOT_FOUND", "data query user not found");
        }
    }

    private String requireUserName(String value) {
        String userName = accessService.normalize(value);
        if (!StringUtils.hasText(userName)) {
            throw new BusinessException("USER_NAME_REQUIRED", "userName must not be blank");
        }
        return userName;
    }

    private String normalizeRemark(String value) {
        if (value == null) return null;
        String remark = value.trim();
        return remark.isEmpty() ? null : remark;
    }
}
