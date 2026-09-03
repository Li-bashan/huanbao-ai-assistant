package com.huanbao.dataquery.protocol;

/** 结构化洞察项；不承载未经证实的因果结论。 */
public record InsightItemDto(String type, String text) {

    public InsightItemDto {
        type = type == null || type.isBlank() ? "fact" : type.trim();
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
        text = text.trim();
    }
}
