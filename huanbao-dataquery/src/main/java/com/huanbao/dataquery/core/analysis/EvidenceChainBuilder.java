package com.huanbao.dataquery.core.analysis;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 构造三层证据链。
 *
 * <p>确认事实只能通过 {@link FactItem} 的数值字段进入；因果、设备状态等尚未被数据库
 * 直接证明的信息，应分别通过 {@link ClueItem} 或 {@link #addPendingVerification(String)}
 * 进入后两层。</p>
 */
public final class EvidenceChainBuilder {

    private final List<FactItem> confirmedFacts = new ArrayList<>();
    private final List<ClueItem> correlatedClues = new ArrayList<>();
    private final Set<String> pendingVerification = new LinkedHashSet<>();

    public EvidenceChainBuilder addConfirmedFact(FactItem fact) {
        confirmedFacts.add(Objects.requireNonNull(fact, "fact"));
        return this;
    }

    public EvidenceChainBuilder addCorrelatedClue(ClueItem clue) {
        correlatedClues.add(Objects.requireNonNull(clue, "clue"));
        return this;
    }

    public EvidenceChainBuilder addPendingVerification(String item) {
        if (item == null || item.isBlank()) {
            throw new IllegalArgumentException("pending verification must not be blank");
        }
        pendingVerification.add(item.trim());
        return this;
    }

    public AnalysisEvidenceResult build() {
        return new AnalysisEvidenceResult(
                confirmedFacts,
                correlatedClues,
                List.copyOf(pendingVerification));
    }
}
