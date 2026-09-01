package com.huanbao.aigateway.service;

import com.huanbao.aigateway.config.DataQueryProperties;
import com.huanbao.aigateway.repository.DataQueryAuditRepository;
import com.huanbao.aigateway.security.DataQueryIdentity;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
public class DataQueryAuditService {
    private static final Logger log = LoggerFactory.getLogger(DataQueryAuditService.class);

    private final DataQueryAuditRepository repository;
    private final DataQueryProperties properties;

    public DataQueryAuditService(DataQueryAuditRepository repository, DataQueryProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    public void record(
        String requestId,
        String conversationId,
        DataQueryIdentity identity,
        String query,
        String analysisType,
        String resolvedIndicator,
        String organizationScope,
        String timeRange,
        String status,
        long durationMs,
        String dataCutoffDate,
        String difyRequestId,
        String workflowRunId
    ) {
        record(requestId, conversationId, "", "", identity, query, analysisType, resolvedIndicator,
            organizationScope, timeRange, "", status, "", durationMs, dataCutoffDate, difyRequestId,
            workflowRunId, "", "", null, null);
    }

    public void record(
        String requestId,
        String clientConversationId,
        String difyConversationId,
        String difyUserIdHash,
        DataQueryIdentity identity,
        String query,
        String analysisType,
        String resolvedIndicator,
        String organizationScope,
        String timeRange,
        String dataScopeType,
        String status,
        String errorCode,
        long durationMs,
        String dataCutoffDate,
        String difyRequestId,
        String workflowRunId,
        String clientIp,
        String userAgent,
        Instant startedAt,
        Instant completedAt
    ) {
        if (!properties.auditEnabled()) return;

        try {
            repository.save(requestId, clientConversationId, difyConversationId, difyUserIdHash, identity,
                query, analysisType, resolvedIndicator, organizationScope, timeRange, dataScopeType, status,
                errorCode, durationMs, dataCutoffDate, difyRequestId, workflowRunId, clientIp, userAgent,
                startedAt, completedAt);
        } catch (DataAccessException ex) {
            // Auditing must never turn a successful data response into a failed query.
            log.error("DATA_QUERY_AUDIT_WRITE_FAILED requestId={}", requestId, ex);
        }
    }
}
