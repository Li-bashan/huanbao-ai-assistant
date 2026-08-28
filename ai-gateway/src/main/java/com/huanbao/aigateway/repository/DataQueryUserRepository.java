package com.huanbao.aigateway.repository;

import com.huanbao.aigateway.dto.DataQueryUserResponse;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DataQueryUserRepository {
    private final NamedParameterJdbcTemplate jdbcTemplate;

    public DataQueryUserRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<DataQueryUserResponse> findAll(String keyword, Boolean enabled) {
        StringBuilder sql = new StringBuilder("""
            SELECT id, user_name, enabled, remark, created_at, updated_at
            FROM ai_data_query_user
            WHERE 1 = 1
            """);
        MapSqlParameterSource parameters = new MapSqlParameterSource();

        if (keyword != null) {
            sql.append(" AND user_name LIKE :keyword");
            parameters.addValue("keyword", "%" + keyword + "%");
        }
        if (enabled != null) {
            sql.append(" AND enabled = :enabled");
            parameters.addValue("enabled", enabled);
        }
        sql.append(" ORDER BY updated_at DESC, id DESC");
        return jdbcTemplate.query(sql.toString(), parameters, (rs, rowNum) -> mapRow(rs));
    }

    public boolean existsByUserNameAndEnabled(String userName, boolean enabled) {
        String sql = """
            SELECT COUNT(1)
            FROM ai_data_query_user
            WHERE user_name = :userName AND enabled = :enabled
            """;
        Integer count = jdbcTemplate.queryForObject(
            sql,
            new MapSqlParameterSource()
                .addValue("userName", userName)
                .addValue("enabled", enabled),
            Integer.class
        );
        return count != null && count > 0;
    }

    public DataQueryUserResponse insert(String userName, boolean enabled, String remark) {
        String sql = """
            INSERT INTO ai_data_query_user (user_name, enabled, remark)
            VALUES (:userName, :enabled, :remark)
            RETURNING id, user_name, enabled, remark, created_at, updated_at
            """;
        return jdbcTemplate.queryForObject(
            sql,
            new MapSqlParameterSource()
                .addValue("userName", userName)
                .addValue("enabled", enabled)
                .addValue("remark", remark),
            (rs, rowNum) -> mapRow(rs)
        );
    }

    public DataQueryUserResponse update(
        long id,
        String userName,
        boolean enabled,
        String remark
    ) {
        String sql = """
            UPDATE ai_data_query_user
            SET user_name = :userName,
                enabled = :enabled,
                remark = :remark,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :id
            RETURNING id, user_name, enabled, remark, created_at, updated_at
            """;
        return jdbcTemplate.queryForObject(
            sql,
            new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("userName", userName)
                .addValue("enabled", enabled)
                .addValue("remark", remark),
            (rs, rowNum) -> mapRow(rs)
        );
    }

    public boolean delete(long id) {
        String sql = "DELETE FROM ai_data_query_user WHERE id = :id";
        return jdbcTemplate.update(sql, new MapSqlParameterSource("id", id)) > 0;
    }

    private DataQueryUserResponse mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new DataQueryUserResponse(
            rs.getLong("id"),
            rs.getString("user_name"),
            rs.getBoolean("enabled"),
            rs.getString("remark"),
            toOffsetDateTime(rs.getTimestamp("created_at")),
            toOffsetDateTime(rs.getTimestamp("updated_at"))
        );
    }

    private OffsetDateTime toOffsetDateTime(Timestamp value) {
        return value == null ? null : value.toInstant().atOffset(ZoneOffset.UTC);
    }
}
