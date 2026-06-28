package com.huanbao.aigateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

public record ActionAuditCreateRequest(
    @NotBlank @Size(max = 80) String actionId,
    @Size(max = 80) String userId,
    @Size(max = 80) String userName,
    @Size(max = 1000) String query,
    @NotBlank @Size(max = 40) String action,
    @Size(max = 100) String formCode,
    @Size(max = 80) String funcId,
    @Size(max = 40) String status,
    Boolean hasFields,
    OffsetDateTime sentAt,
    @NotBlank @Size(max = 40) String result,
    @Size(max = 1000) String errorMessage
) {
}
