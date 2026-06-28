package com.huanbao.aigateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record WorkflowValidateRequest(
    @NotBlank @Size(max = 40) String action,
    @NotBlank @Size(max = 100) String formCode,
    @NotBlank @Size(max = 80) String funcId,
    @NotBlank @Size(max = 40) String status
) {
}
