package com.huanbao.dataquery.protocol;

/** 表格列定义，供前端按 schema 渲染。 */
public record TableColumnDto(String key, String label, String type, String unit) {

    public TableColumnDto(String key, String label, String type) {
        this(key, label, type, "");
    }

    public TableColumnDto {
        key = text(key, "key");
        label = text(label, "label");
        type = type == null || type.isBlank() ? "text" : type.trim();
        unit = unit == null ? "" : unit.trim();
    }

    private static String text(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
