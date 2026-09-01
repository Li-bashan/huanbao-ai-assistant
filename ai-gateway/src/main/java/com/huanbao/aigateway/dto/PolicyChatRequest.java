package com.huanbao.aigateway.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;

public record PolicyChatRequest(
    @NotBlank @Size(max = 2000) String query,
    @JsonAlias({"clientConversationId"}) @Size(max = 120) String conversationId,
    @JsonAlias({"untrustedClientContext"}) DataQueryUserContext userContext,
    Map<String, Object> clientContext
) {
    public PolicyChatRequest(
        String query,
        String conversationId,
        String userId,
        String userName
    ) {
        this(query, conversationId, new DataQueryUserContext(userId, "", userName, "", "", ""), Map.of());
    }

    public String userId() {
        return userContext == null ? "" : userContext.userId();
    }

    public String userName() {
        return userContext == null ? "" : userContext.userName();
    }

    public PolicyChatRequest withConversationId(String value) {
        return new PolicyChatRequest(query, value, userContext, clientContext);
    }

}
