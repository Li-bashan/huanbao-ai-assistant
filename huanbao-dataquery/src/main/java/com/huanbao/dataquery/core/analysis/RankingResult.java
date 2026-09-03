package com.huanbao.dataquery.core.analysis;

import java.util.AbstractList;
import java.util.List;
import java.util.Objects;

/** 有序排名结果，同时保留一个适合直接遍历的 List 视图。 */
public final class RankingResult extends AbstractList<RankingItem> {

    private final List<RankingItem> items;

    public RankingResult(List<RankingItem> items) {
        this.items = List.copyOf(Objects.requireNonNull(items, "items"));
    }

    public List<RankingItem> items() {
        return items;
    }

    @Override
    public RankingItem get(int index) {
        return items.get(index);
    }

    @Override
    public int size() {
        return items.size();
    }
}
