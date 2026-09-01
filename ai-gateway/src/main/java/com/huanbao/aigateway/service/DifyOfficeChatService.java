package com.huanbao.aigateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huanbao.aigateway.config.DifyOfficeProperties;
import com.huanbao.aigateway.dto.ConversationMapping;
import com.huanbao.aigateway.dto.OfficeChatRequest;
import com.huanbao.aigateway.exception.BusinessException;
import com.huanbao.aigateway.security.DataQueryIdentity;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Service
public class DifyOfficeChatService {
    private static final Logger log = LoggerFactory.getLogger(DifyOfficeChatService.class);

    private final RestClient restClient;
    private final DifyOfficeProperties properties;
    private final ObjectMapper objectMapper;
    private final ConversationMappingService mappingService;

    public DifyOfficeChatService(
        @Qualifier("difyOfficeRestClient") RestClient restClient,
        DifyOfficeProperties properties,
        ObjectMapper objectMapper,
        ConversationMappingService mappingService
    ) {
        this.restClient = restClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.mappingService = mappingService;
    }

    public boolean stream(
        OfficeChatRequest request,
        DataQueryIdentity identity,
        ConversationMapping mapping,
        String requestId,
        OutputStream output
    ) {
        StreamState state = new StreamState(mapping.difyConversationId());
        try {
            emit(output, "analysis_started", Map.of("requestId", requestId, "conversationId", request.conversationId()));
            restClient.post()
                .uri(normalizeApiBase() + "/chat-messages")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .body(buildBody(request, identity, mapping))
                .exchange((clientRequest, clientResponse) -> {
                    if (!clientResponse.getStatusCode().is2xxSuccessful()) {
                        throw new UpstreamException(clientResponse.getStatusCode().value());
                    }
                    readStream(clientResponse.getBody(), output, state, requestId);
                    return null;
                });

            mappingService.updateDifyConversation(mapping, state.difyConversationId);
            emit(output, "analysis_result", Map.of(
                "requestId", requestId, "conversationId", request.conversationId(), "answer", state.answer
            ));
            emit(output, "completed", Map.of(
                "requestId", requestId, "conversationId", request.conversationId(), "messageId", state.messageId
            ));
            return true;
        } catch (Exception ex) {
            BusinessException error = toBusinessException(ex);
            log.warn("OFFICE_DIFY_FAILED requestId={} code={}", requestId, error.getCode());
            try {
                emit(output, "error", Map.of("requestId", requestId, "conversationId", request.conversationId(),
                    "code", error.getCode(), "message", error.getMessage()));
            } catch (IOException ignored) {
                // The client may have disconnected; the request is still released by the controller.
            }
            return false;
        }
    }

    private Map<String, Object> buildBody(OfficeChatRequest request, DataQueryIdentity identity, ConversationMapping mapping) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("auth_user_id", identity.userId());
        inputs.put("auth_user_code", identity.userCode());
        inputs.put("auth_user_name", identity.userName());
        inputs.put("auth_org_code", identity.orgCode());
        inputs.put("auth_org_name", identity.orgName());
        inputs.put("auth_tenant_id", identity.tenantId());
        return Map.of(
            "inputs", inputs,
            "query", request.query(),
            "response_mode", "streaming",
            "conversation_id", mapping.difyConversationId() == null ? "" : mapping.difyConversationId(),
            "user", mapping.difyUserId()
        );
    }

    private void readStream(InputStream input, OutputStream output, StreamState state, String requestId) throws IOException {
        if (input == null) throw new BusinessException("DIFY_STREAM_ERROR", "Dify returned an empty stream");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            String event = "";
            StringBuilder data = new StringBuilder();
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    consume(event, data.toString(), output, state, requestId);
                    event = "";
                    data.setLength(0);
                } else if (line.startsWith("event:")) {
                    event = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    if (data.length() > 0) data.append('\n');
                    data.append(line.substring(5).stripLeading());
                }
            }
            consume(event, data.toString(), output, state, requestId);
        }
    }

    private void consume(String event, String raw, OutputStream output, StreamState state, String requestId) throws IOException {
        if (!StringUtils.hasText(raw) || "[DONE]".equals(raw)) return;
        JsonNode data;
        try { data = objectMapper.readTree(raw); } catch (Exception ignored) { return; }
        state.difyConversationId = first(data.path("conversation_id").asText(""), data.path("data").path("conversation_id").asText(""), state.difyConversationId);
        state.messageId = first(data.path("message_id").asText(""), data.path("data").path("message_id").asText(""), state.messageId);
        String eventType = first(event, data.path("event").asText(""), "");
        if ("workflow_started".equals(eventType) || "node_started".equals(eventType) || "node_finished".equals(eventType)) {
            emit(output, "analysis_started", Map.of("requestId", requestId, "conversationId", "", "stage", "generating"));
        }
        String answer = extractText(data);
        if (StringUtils.hasText(answer)) {
            String delta = answer.startsWith(state.answer) ? answer.substring(state.answer.length()) : answer;
            state.answer = answer.startsWith(state.answer) ? answer : state.answer + answer;
            if (StringUtils.hasText(delta)) emit(output, "text_delta", Map.of("requestId", requestId, "delta", stripThink(delta)));
        }
    }

    private String extractText(JsonNode data) {
        for (JsonNode node : new JsonNode[] { data.path("answer"), data.path("text"), data.path("data").path("answer"), data.path("data").path("text") }) {
            if (node.isTextual() && StringUtils.hasText(node.asText())) return stripThink(node.asText());
        }
        return "";
    }

    private String stripThink(String value) {
        return value.replaceAll("(?is)<think>.*?</think>", "").replaceAll("(?is)<think>.*$", "");
    }

    private String normalizeApiBase() {
        if (!StringUtils.hasText(properties.apiBase()) || !StringUtils.hasText(properties.apiKey())) {
            throw new BusinessException("DIFY_CONFIG_MISSING", "office Dify configuration is incomplete");
        }
        return properties.apiBase().endsWith("/") ? properties.apiBase().substring(0, properties.apiBase().length() - 1) : properties.apiBase();
    }

    private BusinessException toBusinessException(Exception error) {
        if (error instanceof BusinessException value) return value;
        if (error instanceof UpstreamException value) {
            if (value.status == 401 || value.status == 403) return new BusinessException("DIFY_UNAUTHORIZED", "Dify authorization failed");
            if (value.status == 408 || value.status == 429 || value.status >= 500) return new BusinessException("DIFY_TIMEOUT", "Dify request timed out or is unavailable");
            return new BusinessException("DIFY_ERROR", "Dify request failed");
        }
        if (error instanceof ResourceAccessException) return new BusinessException("DIFY_TIMEOUT", "Dify request timed out or is unavailable");
        if (error instanceof RestClientResponseException value && (value.getStatusCode().value() == 401 || value.getStatusCode().value() == 403)) {
            return new BusinessException("DIFY_UNAUTHORIZED", "Dify authorization failed");
        }
        return new BusinessException("DIFY_STREAM_ERROR", "Dify streaming request failed");
    }

    private void emit(OutputStream output, String event, Object value) throws IOException {
        output.write(("event: " + event + "\n").getBytes(StandardCharsets.UTF_8));
        output.write(("data: " + objectMapper.writeValueAsString(value) + "\n\n").getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private String first(String... values) {
        for (String value : values) if (StringUtils.hasText(value)) return value;
        return "";
    }

    private static final class StreamState {
        private String difyConversationId;
        private String messageId = "";
        private String answer = "";
        private StreamState(String difyConversationId) { this.difyConversationId = difyConversationId == null ? "" : difyConversationId; }
    }

    private static final class UpstreamException extends RuntimeException {
        private final int status;
        private UpstreamException(int status) { this.status = status; }
    }
}
