package com.huanbao.aigateway.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;

public record MasterChatRequest(
    @NotBlank @Size(max = 4000) String query,
    @JsonAlias({"clientConversationId"}) @Size(max = 120) String conversationId,
    @JsonAlias({"untrustedClientContext"}) DataQueryUserContext userContext,
    Map<String, Object> clientContext,
    DataQueryClarification clarification
) {
    public MasterChatRequest withConversationId(String value) {
        return new MasterChatRequest(query, value, userContext, clientContext, clarification);
    }
}
