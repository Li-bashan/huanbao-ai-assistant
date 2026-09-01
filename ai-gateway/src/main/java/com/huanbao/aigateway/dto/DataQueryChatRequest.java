package com.huanbao.aigateway.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;

public record DataQueryChatRequest(
    @NotBlank @Size(max = 4000) String query,
    @JsonAlias({"clientConversationId"})
    @Size(max = 120) String conversationId,
    @Size(max = 120) String requestId,
    @JsonAlias({"untrustedClientContext"}) DataQueryUserContext userContext,
    Map<String, Object> clientContext,
    DataQueryClarification clarification
) {
    public DataQueryChatRequest withRequestId(String value) {
        return new DataQueryChatRequest(query, conversationId, value, userContext, clientContext, clarification);
    }

    public DataQueryChatRequest withConversationId(String value) {
        return new DataQueryChatRequest(query, value, requestId, userContext, clientContext, clarification);
    }

    public DataQueryUserContext safeUserContext() {
        return userContext == null ? new DataQueryUserContext("", "", "", "", "", "") : userContext;
    }
}
