package com.huanbao.aigateway.dto;

public record WorkflowValidateResponse(
    boolean allow,
    String reason
) {
}
