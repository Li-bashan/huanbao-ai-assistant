package com.huanbao.aigateway.dto;

import java.time.OffsetDateTime;

public record ConversationMapping(
    long id,
    String userId,
    String tenantId,
    String assistantMode,
    String clientConversationId,
    String difyUserId,
    String difyConversationId,
    String status,
    String activeRequestId,
    OffsetDateTime lastActiveAt
) {
}
