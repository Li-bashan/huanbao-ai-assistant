package com.huanbao.dataquery.core.repository;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KingbaseQueryExecutorIntegrationTest {

    private static JdbcDataSource dataSource;
    private static KingbaseQueryExecutor executor;

    @BeforeAll
    static void setUpDatabase() {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:kingbase_executor;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("CREATE TABLE metric_rows ("
                + "\"orgcode\" VARCHAR(64), \"ZBZ\" INTEGER)");
        jdbcTemplate.execute("INSERT INTO metric_rows (\"orgcode\", \"ZBZ\") "
                + "SELECT '10004024', X FROM SYSTEM_RANGE(1, 5001)");
        jdbcTemplate.update("INSERT INTO metric_rows (\"orgcode\", \"ZBZ\") VALUES (?, ?)",
                "10004025", 1);
        executor = new KingbaseQueryExecutor((DataSource) dataSource);
    }

    @AfterAll
    static void tearDownDatabase() {
        new JdbcTemplate(dataSource).execute("DROP ALL OBJECTS");
    }

    @Test
    void bindsAllowedOrganizationsAndEnforcesTheMaximumRowLimit() {
        List<Map<String, Object>> rows = executor.queryForList(
                "SELECT \"orgcode\", \"ZBZ\" FROM metric_rows "
                        + "WHERE \"ZBZ\" >= :minimum ORDER BY \"ZBZ\"",
                Map.of("minimum", 0),
                List.of("10004024"));

        assertEquals(5000, rows.size());
        assertTrue(rows.stream().allMatch(row -> "10004024".equals(row.get("orgcode"))));
        assertEquals(1, rows.get(0).get("ZBZ"));
    }
}
