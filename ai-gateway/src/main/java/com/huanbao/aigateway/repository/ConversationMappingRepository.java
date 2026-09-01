package com.huanbao.aigateway.repository;

import com.huanbao.aigateway.dto.ConversationMapping;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ConversationMappingRepository {
    private final NamedParameterJdbcTemplate jdbcTemplate;

    public ConversationMappingRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void lockOwner(String tenantId, String userId) {
        jdbcTemplate.queryForObject(
            "SELECT pg_advisory_xact_lock(hashtextextended(:lockKey, 0))",
            new MapSqlParameterSource("lockKey", tenantId + ":" + userId), Long.class
        );
    }

    public int countActive(String tenantId, String userId) {
        Integer count = jdbcTemplate.queryForObject("""
            SELECT COUNT(1) FROM ai_conversation_mapping
            WHERE tenant_id = :tenantId AND user_id = :userId
              AND active_request_id IS NOT NULL AND active_until > CURRENT_TIMESTAMP
            """, new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("userId", userId), Integer.class);
        return count == null ? 0 : count;
    }

    public Optional<ConversationMapping> findOwnerMapping(String tenantId, String userId, String mode, String clientId) {
        return jdbcTemplate.query("""
            SELECT id, user_id, tenant_id, assistant_mode, client_conversation_id, dify_user_id,
                   dify_conversation_id, status, active_request_id, last_active_at
            FROM ai_conversation_mapping
            WHERE tenant_id = :tenantId AND user_id = :userId
              AND assistant_mode = :mode AND client_conversation_id = :clientId
            LIMIT 1
            """, new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("userId", userId)
                .addValue("mode", mode).addValue("clientId", clientId),
            (rs, rowNum) -> mapRow(rs)).stream().findFirst();
    }

    public ConversationMapping insert(
        String tenantId, String userId, String mode, String clientId, String difyUserId, String requestId
    ) {
        return jdbcTemplate.queryForObject("""
            INSERT INTO ai_conversation_mapping
              (tenant_id, user_id, assistant_mode, client_conversation_id, dify_user_id,
               status, active_request_id, active_until, last_active_at)
            VALUES (:tenantId, :userId, :mode, :clientId, :difyUserId, 'ACTIVE', :requestId,
                    CURRENT_TIMESTAMP + INTERVAL '15 minutes', CURRENT_TIMESTAMP)
            RETURNING id, user_id, tenant_id, assistant_mode, client_conversation_id, dify_user_id,
                      dify_conversation_id, status, active_request_id, last_active_at
            """, new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("userId", userId)
                .addValue("mode", mode).addValue("clientId", clientId).addValue("difyUserId", difyUserId)
                .addValue("requestId", requestId), (rs, rowNum) -> mapRow(rs));
    }

    public ConversationMapping claim(ConversationMapping mapping, String requestId) {
        return jdbcTemplate.queryForObject("""
            UPDATE ai_conversation_mapping
            SET active_request_id = :requestId, active_until = CURRENT_TIMESTAMP + INTERVAL '15 minutes',
                status = 'ACTIVE', updated_at = CURRENT_TIMESTAMP, last_active_at = CURRENT_TIMESTAMP
            WHERE id = :id
            RETURNING id, user_id, tenant_id, assistant_mode, client_conversation_id, dify_user_id,
                      dify_conversation_id, status, active_request_id, last_active_at
            """, new MapSqlParameterSource().addValue("id", mapping.id()).addValue("requestId", requestId),
            (rs, rowNum) -> mapRow(rs));
    }

    public void updateDifyConversation(long id, String difyConversationId) {
        jdbcTemplate.update("""
            UPDATE ai_conversation_mapping
            SET dify_conversation_id = :conversationId, updated_at = CURRENT_TIMESTAMP,
                last_active_at = CURRENT_TIMESTAMP
            WHERE id = :id
            """, new MapSqlParameterSource().addValue("id", id).addValue("conversationId", difyConversationId));
    }

    public void release(long id, String requestId, boolean success) {
        jdbcTemplate.update("""
            UPDATE ai_conversation_mapping
            SET active_request_id = NULL, active_until = NULL, status = :status,
                updated_at = CURRENT_TIMESTAMP, last_active_at = CURRENT_TIMESTAMP
            WHERE id = :id AND active_request_id = :requestId
            """, new MapSqlParameterSource().addValue("id", id).addValue("requestId", requestId)
                .addValue("status", success ? "ACTIVE" : "ERROR"));
    }

    private ConversationMapping mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ConversationMapping(rs.getLong("id"), rs.getString("user_id"), rs.getString("tenant_id"),
            rs.getString("assistant_mode"), rs.getString("client_conversation_id"), rs.getString("dify_user_id"),
            rs.getString("dify_conversation_id"), rs.getString("status"), rs.getString("active_request_id"),
            toTime(rs.getTimestamp("last_active_at")));
    }

    private OffsetDateTime toTime(Timestamp value) {
        return value == null ? null : value.toInstant().atOffset(ZoneOffset.UTC);
    }
}
