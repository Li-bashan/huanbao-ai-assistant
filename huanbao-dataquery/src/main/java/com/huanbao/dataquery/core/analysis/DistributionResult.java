package com.huanbao.dataquery.core.analysis;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** P25、中位数、P75 及公司极值标记。 */
public record DistributionResult(
        BigDecimal p25,
        BigDecimal median,
        BigDecimal p75,
        List<DistributionItem> items) {

    public DistributionResult {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
    }

    public BigDecimal minimumValue() {
        return items.stream().map(DistributionItem::value).min(BigDecimal::compareTo).orElse(null);
    }

    public BigDecimal maximumValue() {
        return items.stream().map(DistributionItem::value).max(BigDecimal::compareTo).orElse(null);
    }

    public Set<String> minimumCompanies() {
        return extremeCompanies(false);
    }

    public Set<String> maximumCompanies() {
        return extremeCompanies(true);
    }

    private Set<String> extremeCompanies(boolean maximum) {
        LinkedHashSet<String> companies = items.stream()
                .filter(item -> maximum ? item.maximum() : item.minimum())
                .map(DistributionItem::company)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return Collections.unmodifiableSet(companies);
    }
}
