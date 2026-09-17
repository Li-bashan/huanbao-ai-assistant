package com.huanbao.aigateway.repository;

import com.huanbao.aigateway.dto.DataQueryUserAccess;
import com.huanbao.aigateway.dto.DataQueryUserCreateRequest;
import com.huanbao.aigateway.dto.DataQueryUserResponse;
import com.huanbao.aigateway.dto.DataQueryUserUpdateRequest;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
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
            SELECT id, user_id, user_code, user_name, tenant_id, tenant_name, org_id, org_code, org_name,
                   enabled, migration_status, scope_type, allowed_org_codes, allowed_indicator_codes,
                   allow_group_ranking, allow_all_organizations, remark, created_at, updated_at
            FROM ai_data_query_user
            WHERE 1 = 1
            """);
        MapSqlParameterSource parameters = new MapSqlParameterSource();
        if (keyword != null) {
            sql.append(" AND (user_id LIKE :keyword OR user_code LIKE :keyword OR user_name LIKE :keyword OR org_name LIKE :keyword)");
            parameters.addValue("keyword", "%" + keyword + "%");
        }
        if (enabled != null) {
            sql.append(" AND enabled = :enabled");
            parameters.addValue("enabled", enabled);
        }
        sql.append(" ORDER BY updated_at DESC, id DESC");
        return jdbcTemplate.query(sql.toString(), parameters, (rs, rowNum) -> mapRow(rs));
    }

    public Optional<DataQueryUserAccess> findEnabledAccess(String tenantId, String userId) {
        String sql = """
            SELECT user_id, user_code, user_name, tenant_id, tenant_name, org_id, org_code, org_name,
                   enabled, migration_status, scope_type, allowed_org_codes, allowed_indicator_codes,
                   allow_group_ranking, allow_all_organizations
            FROM ai_data_query_user
            WHERE tenant_id = :tenantId
              AND user_id = :userId
              AND enabled = TRUE
              AND COALESCE(migration_status, 'UNRESOLVED') = 'RESOLVED'
            ORDER BY id DESC
            LIMIT 1
            """;
        return jdbcTemplate.query(sql,
            new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("userId", userId),
            (rs, rowNum) -> new DataQueryUserAccess(
                rs.getString("user_id"), rs.getString("user_code"), rs.getString("user_name"),
                rs.getString("tenant_id"), rs.getString("tenant_name"), rs.getString("org_id"),
                rs.getString("org_code"), rs.getString("org_name"), rs.getBoolean("enabled"),
                rs.getString("migration_status"), defaultScope(rs.getString("scope_type")),
                readTextArray(rs.getArray("allowed_org_codes")),
                readTextArray(rs.getArray("allowed_indicator_codes")),
                rs.getBoolean("allow_group_ranking"), rs.getBoolean("allow_all_organizations")
            )
        ).stream().findFirst();
    }

    /** Name fallback is only used by the explicitly enabled BODY_TRIAL mode. */
    public Optional<DataQueryUserAccess> findEnabledAccessByUserName(String userName) {        String sql = """
            SELECT user_id, user_code, user_name, tenant_id, tenant_name, org_id, org_code, org_name,
                   enabled, migration_status, scope_type, allowed_org_codes, allowed_indicator_codes,
                   allow_group_ranking, allow_all_organizations
            FROM ai_data_query_user
            WHERE user_name = :userName
              AND enabled = TRUE
              AND COALESCE(migration_status, 'UNRESOLVED') = 'RESOLVED'
            ORDER BY id DESC
            LIMIT 1
            """;
        return jdbcTemplate.query(sql,
            new MapSqlParameterSource().addValue("userName", userName),
            (rs, rowNum) -> new DataQueryUserAccess(
                rs.getString("user_id"), rs.getString("user_code"), rs.getString("user_name"),
                rs.getString("tenant_id"), rs.getString("tenant_name"), rs.getString("org_id"),
                rs.getString("org_code"), rs.getString("org_name"), rs.getBoolean("enabled"),
                rs.getString("migration_status"), defaultScope(rs.getString("scope_type")),
                readTextArray(rs.getArray("allowed_org_codes")),
                readTextArray(rs.getArray("allowed_indicator_codes")),
                rs.getBoolean("allow_group_ranking"), rs.getBoolean("allow_all_organizations")
            )
        ).stream().findFirst();
    }

    /**
     * 全员开放模式的组织范围清单：取最新一条启用中的全范围授权记录
     * （scope_type=GROUP 或 allow_all_organizations）的 allowed_org_codes。
     * 该清单是现场人工核对过的权威组织全集；查不到时返回空列表，由调用方回退通配。
     */
    public List<String> findOpenScopeOrgCodes() {
        String sql = """
            SELECT allowed_org_codes
            FROM ai_data_query_user
            WHERE enabled = TRUE
              AND COALESCE(migration_status, 'UNRESOLVED') = 'RESOLVED'
              AND (scope_type = 'GROUP' OR allow_all_organizations = TRUE)
            ORDER BY id DESC
            LIMIT 1
            """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> readTextArray(rs.getArray("allowed_org_codes")))
            .stream().findFirst().orElse(List.of());
    }

    /** Compatibility query for the retired name-only audit endpoint. */
    public boolean existsByUserNameAndEnabled(String userName, boolean enabled) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(1) FROM ai_data_query_user WHERE user_name = :userName AND enabled = :enabled",
            new MapSqlParameterSource().addValue("userName", userName).addValue("enabled", enabled),
            Integer.class
        );
        return count != null && count > 0;
    }

    public DataQueryUserResponse insert(DataQueryUserCreateRequest request) {
        String sql = """
            INSERT INTO ai_data_query_user
              (user_id, user_code, user_name, tenant_id, tenant_name, org_id, org_code, org_name,
               enabled, migration_status, scope_type, allowed_org_codes, allowed_indicator_codes,
               allow_group_ranking, allow_all_organizations, remark)
            VALUES (:userId, :userCode, :userName, :tenantId, :tenantName, :orgId, :orgCode, :orgName,
                    :enabled, 'RESOLVED', :scopeType, CAST(:allowedOrgCodes AS text[]),
                    CAST(:allowedIndicatorCodes AS text[]), :allowGroupRanking, :allowAllOrganizations, :remark)
            RETURNING id, user_id, user_code, user_name, tenant_id, tenant_name, org_id, org_code, org_name,
                      enabled, migration_status, scope_type, allowed_org_codes, allowed_indicator_codes,
                      allow_group_ranking, allow_all_organizations, remark, created_at, updated_at
            """;
        return jdbcTemplate.queryForObject(sql, params(request), (rs, rowNum) -> mapRow(rs));
    }

    public DataQueryUserResponse update(long id, DataQueryUserUpdateRequest request) {
        String sql = """
            UPDATE ai_data_query_user
            SET user_id = :userId, user_code = :userCode, user_name = :userName,
                tenant_id = :tenantId, tenant_name = :tenantName, org_id = :orgId,
                org_code = :orgCode, org_name = :orgName, enabled = :enabled,
                migration_status = 'RESOLVED', scope_type = :scopeType,
                allowed_org_codes = CAST(:allowedOrgCodes AS text[]),
                allowed_indicator_codes = CAST(:allowedIndicatorCodes AS text[]),
                allow_group_ranking = :allowGroupRanking,
                allow_all_organizations = :allowAllOrganizations,
                remark = :remark, updated_at = CURRENT_TIMESTAMP
            WHERE id = :id
            RETURNING id, user_id, user_code, user_name, tenant_id, tenant_name, org_id, org_code, org_name,
                      enabled, migration_status, scope_type, allowed_org_codes, allowed_indicator_codes,
                      allow_group_ranking, allow_all_organizations, remark, created_at, updated_at
            """;
        return jdbcTemplate.queryForObject(sql, params(request).addValue("id", id), (rs, rowNum) -> mapRow(rs));
    }

    public boolean delete(long id) {
        return jdbcTemplate.update("DELETE FROM ai_data_query_user WHERE id = :id",
            new MapSqlParameterSource("id", id)) > 0;
    }

    private MapSqlParameterSource params(DataQueryUserCreateRequest request) {
        return new MapSqlParameterSource()
            .addValue("userId", clean(request.userId())).addValue("userCode", clean(request.userCode()))
            .addValue("userName", clean(request.userName())).addValue("tenantId", tenant(request.tenantId()))
            .addValue("tenantName", clean(request.tenantName())).addValue("orgId", clean(request.orgId()))
            .addValue("orgCode", clean(request.orgCode())).addValue("orgName", clean(request.orgName()))
            .addValue("enabled", request.enabled() == null || request.enabled())
            .addValue("scopeType", scope(request.scopeType()))
            .addValue("allowedOrgCodes", arrayLiteral(request.allowedOrgCodes()))
            .addValue("allowedIndicatorCodes", arrayLiteral(request.allowedIndicatorCodes()))
            .addValue("allowGroupRanking", request.allowGroupRanking() != null && request.allowGroupRanking())
            .addValue("allowAllOrganizations", request.allowAllOrganizations() != null && request.allowAllOrganizations())
            .addValue("remark", clean(request.remark()));
    }

    private MapSqlParameterSource params(DataQueryUserUpdateRequest request) {
        return new MapSqlParameterSource()
            .addValue("userId", clean(request.userId())).addValue("userCode", clean(request.userCode()))
            .addValue("userName", clean(request.userName())).addValue("tenantId", tenant(request.tenantId()))
            .addValue("tenantName", clean(request.tenantName())).addValue("orgId", clean(request.orgId()))
            .addValue("orgCode", clean(request.orgCode())).addValue("orgName", clean(request.orgName()))
            .addValue("enabled", request.enabled()).addValue("scopeType", scope(request.scopeType()))
            .addValue("allowedOrgCodes", arrayLiteral(request.allowedOrgCodes()))
            .addValue("allowedIndicatorCodes", arrayLiteral(request.allowedIndicatorCodes()))
            .addValue("allowGroupRanking", request.allowGroupRanking() != null && request.allowGroupRanking())
            .addValue("allowAllOrganizations", request.allowAllOrganizations() != null && request.allowAllOrganizations())
            .addValue("remark", clean(request.remark()));
    }

    private DataQueryUserResponse mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new DataQueryUserResponse(
            rs.getLong("id"), rs.getString("user_id"), rs.getString("user_code"), rs.getString("user_name"),
            rs.getString("tenant_id"), rs.getString("tenant_name"), rs.getString("org_id"), rs.getString("org_code"),
            rs.getString("org_name"), rs.getBoolean("enabled"), rs.getString("migration_status"),
            defaultScope(rs.getString("scope_type")), readTextArray(rs.getArray("allowed_org_codes")),
            readTextArray(rs.getArray("allowed_indicator_codes")), rs.getBoolean("allow_group_ranking"),
            rs.getBoolean("allow_all_organizations"), rs.getString("remark"),
            toOffsetDateTime(rs.getTimestamp("created_at")), toOffsetDateTime(rs.getTimestamp("updated_at"))
        );
    }

    private List<String> readTextArray(java.sql.Array value) throws java.sql.SQLException {
        if (value == null || !(value.getArray() instanceof Object[] values)) return List.of();
        return Arrays.stream(values).map(String::valueOf).map(String::trim)
            .filter(item -> !item.isEmpty()).distinct().limit(1000).toList();
    }

    private String arrayLiteral(List<String> values) {
        if (values == null || values.isEmpty()) return "{}";
        return "{" + values.stream()
            .map(this::clean)
            .filter(item -> item != null && !item.isEmpty())
            .map(item -> "\"" + item.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
            .reduce((a, b) -> a + "," + b)
            .orElse("") + "}";
    }

    private String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private String tenant(String value) { return value == null || value.isBlank() ? "default" : value.trim(); }
    private String scope(String value) { return value == null || value.isBlank() ? "COMPANY" : value.trim().toUpperCase(); }
    private String defaultScope(String value) { return value == null || value.isBlank() ? "NONE" : value; }
    private OffsetDateTime toOffsetDateTime(Timestamp value) { return value == null ? null : value.toInstant().atOffset(ZoneOffset.UTC); }
}
