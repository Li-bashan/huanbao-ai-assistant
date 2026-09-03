package com.huanbao.dataquery.protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** 图表序列。 */
public record ChartSeriesDto(String name, String type, List<Object> data) {

    public ChartSeriesDto {
        name = name == null ? "" : name.trim();
        type = type == null || type.isBlank() ? "bar" : type.trim();
        data = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(data, "data")));
    }
}
