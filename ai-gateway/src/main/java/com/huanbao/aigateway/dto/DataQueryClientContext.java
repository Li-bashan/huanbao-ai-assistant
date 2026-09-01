package com.huanbao.aigateway.dto;

import jakarta.validation.constraints.Size;

public record DataQueryClientContext(
    @Size(max = 40) String assistantMode,
    @Size(max = 80) String timezone
) {
}
