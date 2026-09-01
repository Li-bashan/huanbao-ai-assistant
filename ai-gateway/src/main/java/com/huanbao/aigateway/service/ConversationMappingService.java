package com.huanbao.aigateway.service;

import com.huanbao.aigateway.config.DataQueryProperties;
import com.huanbao.aigateway.dto.ConversationMapping;
import com.huanbao.aigateway.exception.BusinessException;
import com.huanbao.aigateway.repository.ConversationMappingRepository;
import com.huanbao.aigateway.security.DataQueryIdentity;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class ConversationMappingService {
    private final ConversationMappingRepository repository;
    private final DifyUserIdentityService difyUserService;
    private final DataQueryProperties properties;

    public ConversationMappingService(
        ConversationMappingRepository repository,
        DifyUserIdentityService difyUserService,
        DataQueryProperties properties
    ) {
        this.repository = repository;
        this.difyUserService = difyUserService;
        this.properties = properties;
    }

    @Transactional
    public ConversationMapping claim(DataQueryIdentity identity, String mode, String clientId, String requestId) {
        if (!StringUtils.hasText(clientId)) {
            throw new BusinessException("VALIDATION_ERROR", "clientConversationId is required");
        }
        String tenantId = StringUtils.hasText(identity.tenantId()) ? identity.tenantId().trim() : "default";
        repository.lockOwner(tenantId, identity.userId());
        ConversationMapping existing = repository.findOwnerMapping(tenantId, identity.userId(), mode, clientId).orElse(null);
        if (existing != null && existing.activeRequestId() != null && !isExpired(existing)) {
            throw new BusinessException("RATE_LIMITED", "同一会话已有请求正在处理中");
        }
        if (repository.countActive(tenantId, identity.userId()) >= properties.safeMaxConcurrentPerUser()) {
            throw new BusinessException("RATE_LIMITED", "当前账号已有请求正在处理中");
        }
        return existing == null
            ? repository.insert(tenantId, identity.userId(), mode, clientId,
                difyUserService.getDifyUserId(identity), requestId)
            : repository.claim(existing, requestId);
    }

    public void updateDifyConversation(ConversationMapping mapping, String difyConversationId) {
        if (mapping != null && StringUtils.hasText(difyConversationId)) {
            repository.updateDifyConversation(mapping.id(), difyConversationId);
        }
    }

    public void release(ConversationMapping mapping, String requestId, boolean success) {
        if (mapping != null) repository.release(mapping.id(), requestId, success);
    }

    private boolean isExpired(ConversationMapping mapping) {
        return mapping.lastActiveAt() == null
            || mapping.lastActiveAt().plusMinutes(15).isBefore(OffsetDateTime.now());
    }
}
