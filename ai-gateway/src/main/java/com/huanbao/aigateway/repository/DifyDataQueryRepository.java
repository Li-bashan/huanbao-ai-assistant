package com.huanbao.aigateway.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DifyDataQueryRepository {
    private final NamedParameterJdbcTemplate jdbcTemplate;

    public DifyDataQueryRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Map<String, Object>> findRecentActionAudits(
        String userId,
        String action,
        int limit
    ) {
        StringBuilder sql = new StringBuilder("""
            SELECT action_id, user_id, user_name, query_text, action, form_code,
                   status, result, created_at
            FROM ai_action_audit
            WHERE 1 = 1
            """);

        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("limit", limit);
        if (userId != null) {
            sql.append(" AND user_id = :userId");
            parameters.addValue("userId", userId);
        }
        if (action != null) {
            sql.append(" AND action = :action");
            parameters.addValue("action", action);
        }
        sql.append(" ORDER BY created_at DESC LIMIT :limit");
        return jdbcTemplate.queryForList(sql.toString(), parameters);
    }

    public List<Map<String, Object>> summarizeActionAudits(Instant startTime) {
        String sql = """
            SELECT action, result, COUNT(*) AS count
            FROM ai_action_audit
            WHERE created_at >= :startTime
            GROUP BY action, result
            ORDER BY count DESC
            """;

        return jdbcTemplate.queryForList(
            sql,
            new MapSqlParameterSource("startTime", Timestamp.from(startTime))
        );
    }
}
