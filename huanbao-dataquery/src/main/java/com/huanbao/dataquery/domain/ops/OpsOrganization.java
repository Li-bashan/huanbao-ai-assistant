package com.huanbao.dataquery.domain.ops;

/** dim_org_mapping 中用于问数范围解析的生产组织。 */
public record OpsOrganization(
        String formalCode,
        String formalName,
        String shortName,
        String businessType,
        String region,
        boolean productionPlant) {

    public OpsOrganization {
        formalName = requireText(formalName, "formalName");
        shortName = requireText(shortName, "shortName");
        businessType = requireText(businessType, "businessType");
        region = region == null || region.isBlank() ? null : region.trim();
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
