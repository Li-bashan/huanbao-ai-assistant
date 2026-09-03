package com.huanbao.dataquery.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Presentation Model v2 的 schema 驱动表格。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record TableDataDto(
        List<TableColumnDto> columns,
        List<Map<String, Object>> rows,
        int total,
        int defaultVisibleRows) {

    public TableDataDto {
        columns = List.copyOf(Objects.requireNonNull(columns, "columns"));
        rows = immutableRows(rows);
        total = Math.max(0, total);
        defaultVisibleRows = Math.max(1, defaultVisibleRows);
    }

    public static TableDataDto empty() {
        return new TableDataDto(List.of(), List.of(), 0, 10);
    }

    private static List<Map<String, Object>> immutableRows(List<Map<String, Object>> values) {
        Objects.requireNonNull(values, "rows");
        return values.stream()
                .map(row -> Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(row, "row"))))
                .toList();
    }
}
