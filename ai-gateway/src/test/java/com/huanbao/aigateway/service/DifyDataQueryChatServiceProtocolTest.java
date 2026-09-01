package com.huanbao.aigateway.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huanbao.aigateway.config.DataQueryProperties;
import com.huanbao.aigateway.dto.ConversationMapping;
import com.huanbao.aigateway.dto.DataQueryAuthorization;
import com.huanbao.aigateway.dto.DataQueryChatRequest;
import com.huanbao.aigateway.security.DataQueryIdentity;
import java.io.ByteArrayOutputStream;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DifyDataQueryChatServiceProtocolTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void legacyPlainTextEmitsProtocolErrorWithoutSuccessEvents() throws Exception {
        String difyEvent = difyEvent("legacy answer");
        TestContext context = context(difyEvent);

        boolean success = context.service.stream(
            context.request, context.identity, context.authorization, context.mapping,
            "127.0.0.1", "test", context.output
        );

        String output = context.output.toString();
        assertFalse(success);
        assertTrue(output.contains("event: error"));
        assertTrue(output.contains("PROTOCOL_VALIDATION_FAILED"));
        assertFalse(output.contains("event: analysis_result"));
        assertFalse(output.contains("event: completed"));
        verify(context.mappingService, never()).updateDifyConversation(any(), any());
    }

    @Test
    void legacyResponseStatusEmitsProtocolErrorWithoutSuccessEvents() throws Exception {
        String legacyResponse = "{"
            + "\"protocolVersion\":\"2.0\","
            + "\"status\":\"LEGACY_RESPONSE\","
            + "\"messageType\":\"information\","
            + "\"analysisType\":\"\","
            + "\"content\":{"
            + "\"title\":\"\",\"summary\":\"legacy\",\"metrics\":[],"
            + "\"table\":null,\"chart\":null,\"insights\":[],\"evidence\":[],"
            + "\"dataInfo\":{},\"followUps\":[]}}"
            + "}";
        TestContext context = context(difyEvent(legacyResponse));

        boolean success = context.service.stream(
            context.request, context.identity, context.authorization, context.mapping,
            "127.0.0.1", "test", context.output
        );

        String output = context.output.toString();
        assertFalse(success);
        assertTrue(output.contains("PROTOCOL_VALIDATION_FAILED"));
        assertFalse(output.contains("event: analysis_result"));
        assertFalse(output.contains("event: completed"));
    }

    @Test
    void malformedSseDataEmitsProtocolErrorInsteadOfBeingIgnored() throws Exception {
        TestContext context = context("event: message\ndata: {not-json}\n\n");

        boolean success = context.service.stream(
            context.request, context.identity, context.authorization, context.mapping,
            "127.0.0.1", "test", context.output
        );

        String output = context.output.toString();
        assertFalse(success);
        assertTrue(output.contains("PROTOCOL_VALIDATION_FAILED"));
        assertFalse(output.contains("event: completed"));
    }

    @Test
    void intermediateLlmOutputDoesNotCorruptFinalWorkflowProtocol() throws Exception {
        String response = "{"
            + "\"protocolVersion\":\"2.0\",\"requestId\":\"\",\"conversationId\":\"\","
            + "\"status\":\"SUCCESS_WITH_DATA\",\"messageType\":\"analysis\","
            + "\"analysisType\":\"FACT\",\"content\":{"
            + "\"title\":\"发电量\",\"summary\":\"查询完成\",\"metrics\":[],"
            + "\"table\":null,\"chart\":null,\"insights\":[],\"evidence\":[],"
            + "\"dataInfo\":{},\"followUps\":[]},\"clarification\":null,\"meta\":{}}";
        String difySse = workflowEvent("node_finished", "llm", Map.of(
            "text", "{\"analysis_type\":\"FACT\"}"
        )) + workflowEvent("workflow_finished", "workflow", Map.of("answer", response));
        TestContext context = context(difySse);

        boolean success = context.service.stream(
            context.request, context.identity, context.authorization, context.mapping,
            "127.0.0.1", "test", context.output
        );

        String output = context.output.toString();
        assertTrue(success);
        assertTrue(output.contains("event: analysis_result"));
        assertTrue(output.contains("SUCCESS_WITH_DATA"));
        assertFalse(output.contains("PROTOCOL_VALIDATION_FAILED"));
    }

    private TestContext context(String difySse) throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://dify/chat-messages"))
            .andRespond(withSuccess(difySse, MediaType.TEXT_EVENT_STREAM));

        ConversationMappingService mappingService = mock(ConversationMappingService.class);
        DataQueryAuditService auditService = mock(DataQueryAuditService.class);
        DifyUserIdentityService difyUserIdentityService = mock(DifyUserIdentityService.class);
        DifyDataQueryChatService service = new DifyDataQueryChatService(
            builder.build(),
            new DataQueryProperties(
                "http://dify", "key", 1000, "dify-user", "hmac", "SIGNED_HEADER", "identity",
                300, false, 30, 1, "USER_AUTHORIZED", false, "", false
            ),
            OBJECT_MAPPER,
            auditService,
            mappingService,
            difyUserIdentityService
        );

        return new TestContext(
            service,
            new DataQueryChatRequest("查询发电量", "client-1", "request-1", null, Map.of(), null),
            new DataQueryIdentity("user-1", "U1", "张三", "ORG-1", "组织一", "tenant-1", true, "SIGNED_HEADER"),
            new DataQueryAuthorization(
                "user-1", "U1", "张三", "ORG-1", "组织一", "tenant-1", true, "SIGNED_HEADER",
                "USER_AUTHORIZED", false, List.of(), List.of(), false
            ),
            new ConversationMapping(
                1L, "user-1", "tenant-1", "data-query", "client-1", "dify-user-1", "", "ACTIVE",
                "request-1", OffsetDateTime.now()
            ),
            mappingService,
            new ByteArrayOutputStream()
        );
    }

    private String difyEvent(String answer) throws Exception {
        return "event: message\n"
            + "data: " + OBJECT_MAPPER.writeValueAsString(Map.of("answer", answer)) + "\n\n";
    }

    private String workflowEvent(String event, String nodeType, Map<String, Object> outputs) throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("node_type", nodeType);
        data.put("outputs", outputs);
        return "data: " + OBJECT_MAPPER.writeValueAsString(Map.of("event", event, "data", data)) + "\n\n";
    }

    private record TestContext(
        DifyDataQueryChatService service,
        DataQueryChatRequest request,
        DataQueryIdentity identity,
        DataQueryAuthorization authorization,
        ConversationMapping mapping,
        ConversationMappingService mappingService,
        ByteArrayOutputStream output
    ) {
    }
}
