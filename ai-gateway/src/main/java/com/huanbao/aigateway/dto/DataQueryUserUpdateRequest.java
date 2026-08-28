package com.huanbao.aigateway.dto;

import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotNull;

public record DataQueryUserUpdateRequest(
    @Size(max = 80) String userName,
    @NotNull Boolean enabled,
    @Size(max = 255) String remark
) {
}
