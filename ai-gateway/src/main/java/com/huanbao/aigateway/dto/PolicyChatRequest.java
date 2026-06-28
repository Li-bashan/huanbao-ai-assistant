package com.huanbao.aigateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PolicyChatRequest(
    @NotBlank @Size(max = 2000) String query,
    @Size(max = 100) String conversationId,
    @Size(max = 80) String userId,
    @Size(max = 80) String userName
) {
}
