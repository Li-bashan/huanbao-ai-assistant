package com.huanbao.aigateway.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;

public record OfficeChatRequest(
    @NotBlank @Size(max = 4000) String query,
    @JsonAlias({"clientConversationId"}) @Size(max = 120) String conversationId,
    @JsonAlias({"untrustedClientContext"}) DataQueryUserContext userContext,
    Map<String, Object> clientContext
) {
    public OfficeChatRequest withConversationId(String value) {
        return new OfficeChatRequest(query, value, userContext, clientContext);
    }
}
