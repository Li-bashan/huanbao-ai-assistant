package com.huanbao.aigateway.repository;

import com.huanbao.aigateway.dto.ActionAuditCreateRequest;
import com.huanbao.aigateway.dto.ActionAuditResponse;
import com.huanbao.aigateway.dto.PageResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
public class ActionAuditRepository {
    private final JdbcTemplate jdbcTemplate;

    public ActionAuditRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public ActionAuditResponse save(ActionAuditCreateRequest request, String clientIp, String userAgent) {
        String sql = """
            INSERT INTO ai_action_audit
              (action_id, user_id, user_name, query_text, action, form_code, func_id, status,
               has_fields, sent_at, result, error_message, client_ip, user_agent)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING id, action_id, user_id, user_name, query_text, action, form_code, func_id,
              status, has_fields, sent_at, result, error_message, client_ip, user_agent, created_at
            """;

        return jdbcTemplate.queryForObject(sql, this::mapRow,
            request.actionId(),
            request.userId(),
            request.userName(),
            request.query(),
            request.action(),
            request.formCode(),
            request.funcId(),
            request.status(),
            Boolean.TRUE.equals(request.hasFields()),
            toTimestamp(request.sentAt()),
            request.result(),
            request.errorMessage(),
            clientIp,
            userAgent
        );
    }

    public PageResponse<ActionAuditResponse> search(
        String userId,
        String action,
        String formCode,
        String result,
        OffsetDateTime startTime,
        OffsetDateTime endTime,
        int page,
        int size
    ) {
        List<Object> params = new ArrayList<>();
        String where = buildWhereClause(params, userId, action, formCode, result, startTime, endTime);

        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ai_action_audit " + where, Long.class, params.toArray());
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        int offset = (safePage - 1) * safeSize;

        List<Object> queryParams = new ArrayList<>(params);
        queryParams.add(safeSize);
        queryParams.add(offset);

        String sql = """
            SELECT id, action_id, user_id, user_name, query_text, action, form_code, func_id,
              status, has_fields, sent_at, result, error_message, client_ip, user_agent, created_at
            FROM ai_action_audit
            """ + where + " ORDER BY created_at DESC LIMIT ? OFFSET ?";

        List<ActionAuditResponse> records = jdbcTemplate.query(sql, this::mapRow, queryParams.toArray());
        return new PageResponse<>(records, total == null ? 0 : total, safePage, safeSize);
    }

    private String buildWhereClause(
        List<Object> params,
        String userId,
        String action,
        String formCode,
        String result,
        OffsetDateTime startTime,
        OffsetDateTime endTime
    ) {
        List<String> clauses = new ArrayList<>();

        if (StringUtils.hasText(userId)) {
            clauses.add("user_id = ?");
            params.add(userId);
        }
        if (StringUtils.hasText(action)) {
            clauses.add("action = ?");
            params.add(action);
        }
        if (StringUtils.hasText(formCode)) {
            clauses.add("form_code = ?");
            params.add(formCode);
        }
        if (StringUtils.hasText(result)) {
            clauses.add("result = ?");
            params.add(result);
        }
        if (startTime != null) {
            clauses.add("created_at >= ?");
            params.add(toTimestamp(startTime));
        }
        if (endTime != null) {
            clauses.add("created_at <= ?");
            params.add(toTimestamp(endTime));
        }

        return clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
    }

    private ActionAuditResponse mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ActionAuditResponse(
            rs.getLong("id"),
            rs.getString("action_id"),
            rs.getString("user_id"),
            rs.getString("user_name"),
            rs.getString("query_text"),
            rs.getString("action"),
            rs.getString("form_code"),
            rs.getString("func_id"),
            rs.getString("status"),
            rs.getBoolean("has_fields"),
            toOffsetDateTime(rs.getTimestamp("sent_at")),
            rs.getString("result"),
            rs.getString("error_message"),
            rs.getString("client_ip"),
            rs.getString("user_agent"),
            toOffsetDateTime(rs.getTimestamp("created_at"))
        );
    }

    private Timestamp toTimestamp(OffsetDateTime value) {
        return value == null ? null : Timestamp.from(value.toInstant());
    }

    private OffsetDateTime toOffsetDateTime(Timestamp value) {
        return value == null ? null : value.toInstant().atOffset(ZoneOffset.UTC);
    }
}
