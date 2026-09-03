package com.huanbao.dataquery.core.engine;

import com.huanbao.dataquery.core.spi.DomainSemanticProvider;
import com.huanbao.dataquery.core.spi.EntityMapping;
import com.huanbao.dataquery.core.spi.MetricDefinition;
import com.huanbao.dataquery.core.spi.SecurityUserContext;
import com.huanbao.dataquery.core.spi.SqlBuildStrategy;
import com.huanbao.dataquery.domain.ops.OpsDomainSemanticProvider;
import net.sf.jsqlparser.expression.Expression;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MetricDecomposerTest {

    private final MetricDecomposer decomposer = new MetricDecomposer(new OpsDomainSemanticProvider());

    @Test
    void extractsNumeratorAndDenominatorDependenciesFromTonElectricGeneration() {
        MetricDecomposition decomposition = decomposer.decompose("吨入厂发电量");

        assertEquals("TON_ELEC_GEN", decomposition.requestedMetric().indicatorCode());
        assertEquals(java.util.List.of("1001", "1201"), decomposition.atomicMetricCodes());
        assertEquals(java.util.List.of("1001"), decomposition.numeratorBaseMetricRefs());
        assertEquals(java.util.List.of("1201"), decomposition.denominatorBaseMetricRefs());
    }

    @Test
    void keepsAtomicMetricAsDirectDependency() {
        MetricDecomposition decomposition = decomposer.decompose("1001");

        assertTrueAtomic(decomposition);
        assertEquals(java.util.List.of("1001"), decomposition.atomicMetricCodes());
        assertEquals(java.util.List.of("1001"), decomposition.baseMetricRefs());
    }

    @Test
    void extractsDenominatorFromTheTrueBranchWhenDivisionIsParenthesized() {
        OpsDomainSemanticProvider ops = new OpsDomainSemanticProvider();
        MetricDefinitionWithProvider fixture = new MetricDefinitionWithProvider(ops);

        MetricDecomposition decomposition = fixture.decomposer().decompose("测试吨发电量");

        assertEquals(List.of("1001"), decomposition.numeratorBaseMetricRefs());
        assertEquals(List.of("1201"), decomposition.denominatorBaseMetricRefs());
    }

    private static void assertTrueAtomic(MetricDecomposition decomposition) {
        if (!decomposition.isAtomic()) {
            throw new AssertionError("Expected an atomic metric");
        }
    }

    private static final class MetricDefinitionWithProvider {

        private final DomainSemanticProvider provider;

        private MetricDefinitionWithProvider(OpsDomainSemanticProvider ops) {
            MetricDefinition testMetric = new MetricDefinition(
                    "TEST_TON_ELEC_GEN",
                    "测试吨发电量",
                    Set.of(),
                    "测试",
                    "DERIVED",
                    true,
                    List.of("1001", "1201"),
                    List.of(),
                    "base_1201 > 0 ? (base_1001 / base_1201) * 10000 : 0",
                    "度/吨",
                    "WEIGHTED_RECALCULATE",
                    List.of(),
                    List.of("GROUP"),
                    List.of(),
                    "PRESENT_IN_DB_FORMULA",
                    List.of());
            this.provider = new DomainSemanticProvider() {
                @Override
                public String getDomainCode() {
                    return "TEST";
                }

                @Override
                public List<String> getDomainKeywords() {
                    return List.of("测试");
                }

                @Override
                public MetricDefinition getMetric(String nameOrAlias) {
                    return testMetric.matches(nameOrAlias) ? testMetric : ops.getMetric(nameOrAlias);
                }

                @Override
                public EntityMapping resolveEntity(String rawName) {
                    return new EntityMapping("GROUP", "集团", "test_scope");
                }

                @Override
                public SqlBuildStrategy getSqlStrategy() {
                    return request -> "SELECT 1";
                }

                @Override
                public void applyDataScope(Expression whereClause, SecurityUserContext userContext) {
                    // Test-only provider.
                }
            };
        }

        private MetricDecomposer decomposer() {
            return new MetricDecomposer(provider);
        }
    }
}
