package com.huanbao.aigateway.dto;

import java.util.List;
import java.util.Map;

public record DifyDataQueryResponse(
    String queryCode,
    int rowCount,
    List<Map<String, Object>> records
) {
}
