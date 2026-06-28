package com.huanbao.aigateway.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record PolicyChatResponse(
    String answer,
    String conversationId,
    List<JsonNode> retrieverResources
) {
}
