package com.huanbao.dataquery.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Presentation Model v2 顶层响应。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PresentationResponseV2(
        String protocolVersion,
        String requestId,
        String conversationId,
        String status,
        String messageType,
        String analysisType,
        ContentV2 content,
        Object clarification,
        MetaDto meta) {

    public static final String PROTOCOL_VERSION = "2.0";
    public static final String SUCCESS_WITH_DATA = "SUCCESS_WITH_DATA";
    public static final String NO_DATA = "NO_DATA";

    public PresentationResponseV2 {
        protocolVersion = PROTOCOL_VERSION;
        requestId = requestId == null ? "" : requestId.trim();
        conversationId = conversationId == null ? "" : conversationId.trim();
        status = SUCCESS_WITH_DATA.equals(status) ? SUCCESS_WITH_DATA : NO_DATA;
        messageType = messageType == null || messageType.isBlank()
                ? (NO_DATA.equals(status) ? "empty" : "analysis")
                : messageType.trim();
        analysisType = analysisType == null || analysisType.isBlank() ? "FACT" : analysisType.trim();
        content = content == null
                ? new ContentV2("", "", java.util.List.of(), null, null,
                java.util.List.of(), java.util.List.of(), DataInfoDto.empty(), java.util.List.of())
                : content;
        meta = meta == null ? MetaDto.empty() : meta;
    }
}
