package com.huanbao.dataquery.core.spi;

/**
 * 用户输入实体到受控领域实体/表的映射，不接受未经领域插件确认的表名。
 */
public record EntityMapping(
        String entityType,
        String canonicalName,
        String tableName) {

    public EntityMapping {
        entityType = requireText(entityType, "entityType");
        canonicalName = requireText(canonicalName, "canonicalName");
        tableName = requireText(tableName, "tableName");
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
