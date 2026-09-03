package com.huanbao.dataquery.core.analysis;

import java.math.BigDecimal;

/**
 * 单个公司的确定性排名结果。
 *
 * <p>{@code rankChange} 使用本期排名减上期排名计算，正数表示排名数字上升，
 * 也就是名次下降。例如上期第 2、本期第 5，变化为 {@code 3}，表示下降 3 位。</p>
 */
public record RankingItem(
        String company,
        BigDecimal value,
        int rank,
        Integer previousRank,
        Integer rankChange) {

    public boolean isNewEntry() {
        return previousRank == null;
    }

    public boolean isImproved() {
        return rankChange != null && rankChange < 0;
    }

    public boolean isDeclined() {
        return rankChange != null && rankChange > 0;
    }
}
