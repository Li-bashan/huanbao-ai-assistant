package com.huanbao.dataquery.core.analysis;

import java.util.List;
import java.util.Objects;

/**
 * 三层证据链：已确认事实、相关线索和建议核实事项。
 */
public record AnalysisEvidenceResult(
        List<FactItem> confirmedFacts,
        List<ClueItem> correlatedClues,
        List<String> pendingVerification) {

    public AnalysisEvidenceResult {
        confirmedFacts = List.copyOf(Objects.requireNonNull(confirmedFacts, "confirmedFacts"));
        correlatedClues = List.copyOf(Objects.requireNonNull(correlatedClues, "correlatedClues"));
        pendingVerification = List.copyOf(Objects.requireNonNull(pendingVerification, "pendingVerification"));
    }
}
