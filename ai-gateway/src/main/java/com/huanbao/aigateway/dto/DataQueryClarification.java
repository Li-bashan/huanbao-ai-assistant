package com.huanbao.aigateway.dto;

import jakarta.validation.constraints.Size;

public record DataQueryClarification(
    @Size(max = 80) String slot,
    @Size(max = 160) String selectedValue,
    @Size(max = 200) String selectedLabel
) {
}
