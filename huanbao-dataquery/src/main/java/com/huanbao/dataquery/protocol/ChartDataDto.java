package com.huanbao.dataquery.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Objects;

/** Presentation Model v2 的语义图表配置。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ChartDataDto(
        String type,
        String title,
        String xField,
        String yField,
        List<String> categories,
        List<ChartSeriesDto> series) {

    public ChartDataDto {
        type = type == null || type.isBlank() ? "bar" : type.trim();
        title = title == null ? "" : title.trim();
        xField = xField == null ? "" : xField.trim();
        yField = yField == null ? "" : yField.trim();
        categories = List.copyOf(Objects.requireNonNull(categories, "categories"));
        series = List.copyOf(Objects.requireNonNull(series, "series"));
    }
}
