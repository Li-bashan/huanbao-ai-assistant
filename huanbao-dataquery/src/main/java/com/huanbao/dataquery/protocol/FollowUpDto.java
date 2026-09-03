package com.huanbao.dataquery.protocol;

/** 可点击的下一轮分析胶囊。 */
public record FollowUpDto(String id, String label, String query) {

    public FollowUpDto {
        id = text(id, "id");
        label = text(label, "label");
        query = text(query, "query");
    }

    private static String text(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
