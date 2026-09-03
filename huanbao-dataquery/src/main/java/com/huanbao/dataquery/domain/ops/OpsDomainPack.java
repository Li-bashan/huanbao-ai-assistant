package com.huanbao.dataquery.domain.ops;

import com.huanbao.dataquery.core.engine.CalculationEngine;
import com.huanbao.dataquery.core.engine.MetricDecomposer;
import com.huanbao.dataquery.core.spi.DomainSemanticProviderRegistry;

import java.util.Objects;

/**
 * OPS 领域包的单一装配入口，便于服务端或测试一次性取得语义、拆解和计算能力。
 */
public final class OpsDomainPack {

    private final OpsDomainSemanticProvider semanticProvider;
    private final MetricDecomposer metricDecomposer;
    private final CalculationEngine calculationEngine;

    public OpsDomainPack() {
        this(new OpsDomainSemanticProvider());
    }

    public OpsDomainPack(OpsDomainSemanticProvider semanticProvider) {
        this.semanticProvider = Objects.requireNonNull(semanticProvider, "semanticProvider");
        this.metricDecomposer = new MetricDecomposer(semanticProvider);
        this.calculationEngine = new CalculationEngine(metricDecomposer);
    }

    public OpsDomainSemanticProvider semanticProvider() {
        return semanticProvider;
    }

    public MetricDecomposer metricDecomposer() {
        return metricDecomposer;
    }

    public CalculationEngine calculationEngine() {
        return calculationEngine;
    }

    public DomainSemanticProviderRegistry registry() {
        return new DomainSemanticProviderRegistry(java.util.List.of(semanticProvider));
    }
}
