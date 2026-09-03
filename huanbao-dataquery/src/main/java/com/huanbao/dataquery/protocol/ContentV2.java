package com.huanbao.dataquery.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Objects;

/** Presentation Model v2 内容主体。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ContentV2(
        String title,
        String summary,
        List<MetricCardDto> metrics,
        TableDataDto table,
        ChartDataDto chart,
        List<InsightItemDto> insights,
        List<EvidenceV2Dto> evidence,
        DataInfoDto dataInfo,
        List<FollowUpDto> followUps) {

    public ContentV2 {
        title = title == null ? "" : title.trim();
        summary = summary == null ? "" : summary.trim();
        metrics = List.copyOf(Objects.requireNonNull(metrics, "metrics"));
        insights = List.copyOf(Objects.requireNonNull(insights, "insights"));
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        dataInfo = Objects.requireNonNull(dataInfo, "dataInfo");
        followUps = List.copyOf(Objects.requireNonNull(followUps, "followUps"));
    }
}
