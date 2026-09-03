package com.huanbao.dataquery.core.spi;

import net.sf.jsqlparser.expression.Expression;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DomainSemanticProviderRegistryTest {

    @Test
    void registersAndResolvesDomainProviderByCaseInsensitiveCode() {
        DomainSemanticProvider provider = new StubProvider("OPS");
        DomainSemanticProviderRegistry registry = new DomainSemanticProviderRegistry(List.of(provider));

        assertSame(provider, registry.require("ops"));
        assertEquals(Set.of("OPS"), registry.view().keySet());
        assertTrue(registry.find("unknown").isEmpty());
    }

    @Test
    void rejectsDuplicateDomainCodes() {
        assertThrows(IllegalStateException.class, () ->
                new DomainSemanticProviderRegistry(List.of(
                        new StubProvider("PROCUREMENT"),
                        new StubProvider("procurement"))));
    }

    private static final class StubProvider implements DomainSemanticProvider {

        private final String domainCode;

        private StubProvider(String domainCode) {
            this.domainCode = domainCode;
        }

        @Override
        public String getDomainCode() {
            return domainCode;
        }

        @Override
        public List<String> getDomainKeywords() {
            return List.of("运维", "指标");
        }

        @Override
        public MetricDefinition getMetric(String nameOrAlias) {
            return new MetricDefinition(
                    "OPS_ONLINE_RATE",
                    "设备在线率",
                    Set.of("在线率"),
                    "效率",
                    "DERIVED",
                    true,
                    List.of("ONLINE", "TOTAL"),
                    List.of(),
                    "safeDivide(base_ONLINE, base_TOTAL)",
                    "%",
                    "WEIGHTED_RECALCULATE",
                    List.of("垃圾焚烧发电项目"),
                    List.of("GROUP", "REGION", "PROJECT_COMPANY"),
                    List.of(),
                    "PRESENT_IN_DB_FORMULA",
                    List.of());
        }

        @Override
        public EntityMapping resolveEntity(String rawName) {
            return new EntityMapping("ORGANIZATION", rawName, "v_org_scope");
        }

        @Override
        public SqlBuildStrategy getSqlStrategy() {
            return request -> "SELECT 1";
        }

        @Override
        public void applyDataScope(Expression whereClause, SecurityUserContext userContext) {
            // 领域实现负责使用 JsqlparserSqlHelper 合并自己的权限谓词。
        }
    }
}
