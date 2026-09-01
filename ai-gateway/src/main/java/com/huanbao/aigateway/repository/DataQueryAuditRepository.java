package com.huanbao.aigateway.repository;

import com.huanbao.aigateway.security.DataQueryIdentity;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DataQueryAuditRepository {
    private final JdbcTemplate jdbcTemplate;

    public DataQueryAuditRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(
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
        String sql = """
            INSERT INTO ai_data_query_audit
              (request_id, conversation_id, dify_conversation_id, dify_user_id_hash,
               user_id, user_code, user_name, org_code, org_name,
               tenant_id, identity_verified, identity_source, query_text, analysis_type,
               resolved_indicator, organization_scope, time_range, data_scope_type, status, error_code, duration_ms,
               data_cutoff_date, dify_request_id, workflow_run_id, client_ip, user_agent,
               started_at, completed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        jdbcTemplate.update(sql,
            requestId,
            clientConversationId,
            truncate(difyConversationId, 120),
            truncate(difyUserIdHash, 160),
            identity.userId(),
            identity.userCode(),
            identity.userName(),
            identity.orgCode(),
            identity.orgName(),
            identity.tenantId(),
            identity.verified(),
            identity.source(),
            truncate(query, 2000),
            truncate(analysisType, 60),
            truncate(resolvedIndicator, 160),
            truncate(organizationScope, 1000),
            truncate(timeRange, 1000),
            truncate(dataScopeType, 40),
            truncate(status, 80),
            truncate(errorCode, 80),
            durationMs,
            truncate(dataCutoffDate, 40),
            truncate(difyRequestId, 120),
            truncate(workflowRunId, 120),
            truncate(clientIp, 80),
            truncate(userAgent, 500),
            startedAt == null ? null : Timestamp.from(startedAt),
            completedAt == null ? null : Timestamp.from(completedAt)
        );
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value;
        return value.substring(0, maxLength);
    }
}
