package com.huanbao.aigateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huanbao.aigateway.config.DifyMasterProperties;
import com.huanbao.aigateway.dto.ConversationMapping;
import com.huanbao.aigateway.dto.DataQueryClarification;
import com.huanbao.aigateway.dto.MasterChatRequest;
import com.huanbao.aigateway.exception.BusinessException;
import com.huanbao.aigateway.security.DataQueryIdentity;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
public class DifyMasterChatService {
    private static final Logger log = LoggerFactory.getLogger(DifyMasterChatService.class);

    private final RestClient restClient;
    private final DifyMasterProperties properties;
    private final ObjectMapper objectMapper;
    private final ConversationMappingService mappingService;

    public DifyMasterChatService(
        @Qualifier("difyMasterRestClient") RestClient restClient,
        DifyMasterProperties properties,
        ObjectMapper objectMapper,
        ConversationMappingService mappingService
    ) {
        this.restClient = restClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.mappingService = mappingService;
    }

    public boolean stream(
        MasterChatRequest request,
        DataQueryIdentity identity,
        ConversationMapping mapping,
        String requestId,
        OutputStream output
    ) {
        StreamState state = new StreamState(mapping.difyConversationId());
        try {
            emit(output, "analysis_started", Map.of(
                "requestId", requestId,
                "conversationId", request.conversationId(),
                "stage", "understanding"
            ));
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
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("requestId", requestId);
            result.put("conversationId", request.conversationId());
            result.put("answer", state.answer);
            result.put("suggestedActions", suggestedActions(state.answer));
            result.put("sources", state.sources);
            emit(output, "analysis_result", result);
            emit(output, "completed", Map.of(
                "requestId", requestId,
                "conversationId", request.conversationId(),
                "messageId", state.messageId
            ));
            return true;
        } catch (Exception ex) {
            BusinessException error = toBusinessException(ex);
            log.warn("MASTER_DIFY_FAILED requestId={} code={}", requestId, error.getCode());
            try {
                emit(output, "error", Map.of(
                    "requestId", requestId,
                    "conversationId", request.conversationId(),
                    "code", error.getCode(),
                    "message", error.getMessage()
                ));
            } catch (IOException ignored) {
                // The client may have disconnected; the controller still releases the mapping.
            }
            return false;
        }
    }

    private Map<String, Object> buildBody(
        MasterChatRequest request,
        DataQueryIdentity identity,
        ConversationMapping mapping
    ) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("query", request.query());
        inputs.put("analysis_state", contextValue(request, "analysisState"));
        inputs.put("auth_context_json", identityJson(identity));
        inputs.put("clarification_json", clarificationJson(request.clarification()));
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

    private String contextValue(MasterChatRequest request, String key) {
        Object value = request.clientContext() == null ? null : request.clientContext().get(key);
        if (value == null) return "";
        return value instanceof String string ? string : writeJson(value);
    }

    private String identityJson(DataQueryIdentity identity) {
        Map<String, String> context = new LinkedHashMap<>();
        context.put("userId", identity.userId());
        context.put("userCode", identity.userCode());
        context.put("userName", identity.userName());
        context.put("orgCode", identity.orgCode());
        context.put("orgName", identity.orgName());
        context.put("tenantId", identity.tenantId());
        return writeJson(context);
    }

    private String clarificationJson(DataQueryClarification clarification) {
        return clarification == null ? "" : writeJson(clarification);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new BusinessException("DIFY_ERROR", "unable to serialize Master workflow inputs");
        }
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
        try {
            data = objectMapper.readTree(raw);
        } catch (Exception ignored) {
            return;
        }
        String eventType = first(event, data.path("event").asText(""), "");
        state.difyConversationId = first(
            data.path("conversation_id").asText(""),
            data.path("data").path("conversation_id").asText(""),
            state.difyConversationId
        );
        state.messageId = first(
            data.path("message_id").asText(""),
            data.path("id").asText(""),
            data.path("data").path("message_id").asText(""),
            state.messageId
        );
        collectSources(data, state.sources);

        if ("workflow_started".equals(eventType) || "node_started".equals(eventType)) {
            emit(output, "analysis_started", Map.of(
                "requestId", requestId,
                "conversationId", "",
                "stage", "generating"
            ));
        }
        if ("error".equals(eventType)) {
            throw new BusinessException("DIFY_STREAM_ERROR", first(
                data.path("message").asText(""),
                data.path("error").asText(""),
                "Master workflow failed"
            ));
        }

        String answer = extractText(data, eventType, state.answer);
        if (StringUtils.hasText(answer)) {
            String clean = stripThink(answer);
            if ("message_end".equals(eventType) || "workflow_finished".equals(eventType)) {
                if (!clean.equals(state.answer)) {
                    state.answer = clean;
                    emit(output, "text_replace", Map.of("requestId", requestId, "text", clean));
                }
            } else {
                String delta = appendText(state, clean);
                if (StringUtils.hasText(delta)) {
                    emit(output, "text_delta", Map.of("requestId", requestId, "delta", delta));
                }
            }
        }
        if ("workflow_finished".equals(eventType)) {
            String status = first(
                data.path("status").asText(""),
                data.path("data").path("status").asText(""),
                "success"
            );
            if ("failed".equalsIgnoreCase(status) || "error".equalsIgnoreCase(status)) {
                throw new BusinessException("DIFY_STREAM_ERROR", first(
                    data.path("error").asText(""),
                    data.path("data").path("error").asText(""),
                    "Master workflow failed"
                ));
            }
        }
    }

    private String appendText(StreamState state, String incoming) {
        if (!StringUtils.hasText(incoming)) return "";
        if (!StringUtils.hasText(state.answer)) {
            state.answer = incoming;
            return incoming;
        }
        if (incoming.startsWith(state.answer)) {
            String delta = incoming.substring(state.answer.length());
            state.answer = incoming;
            return delta;
        }
        if (state.answer.startsWith(incoming)) return "";
        state.answer += incoming;
        return incoming;
    }

    private String extractText(JsonNode data, String eventType, String currentAnswer) {
        boolean streamedAnswer = "message".equals(eventType)
            || "agent_message".equals(eventType)
            || "text_chunk".equals(eventType);
        boolean finalAnswer = "message_end".equals(eventType)
            || "workflow_finished".equals(eventType);
        if (!streamedAnswer && !finalAnswer) return "";

        List<JsonNode> candidates = new ArrayList<>(List.of(
            data.path("answer"),
            data.path("text"),
            data.path("data").path("answer"),
            data.path("data").path("text")
        ));
        if (finalAnswer || !StringUtils.hasText(currentAnswer)) {
            candidates.add(data.path("data").path("outputs").path("answer"));
            candidates.add(data.path("data").path("outputs").path("text"));
            candidates.add(data.path("data").path("outputs").path("result_text"));
            candidates.add(data.path("outputs").path("answer"));
            candidates.add(data.path("outputs").path("text"));
            candidates.add(data.path("outputs").path("result_text"));
        }
        for (JsonNode candidate : candidates) {
            if (candidate.isTextual() && StringUtils.hasText(candidate.asText())) return candidate.asText();
        }
        return "";
    }

    private void collectSources(JsonNode data, List<JsonNode> sources) {
        for (JsonNode candidate : new JsonNode[] {
            data.path("metadata").path("retriever_resources"),
            data.path("data").path("metadata").path("retriever_resources")
        }) {
            if (!candidate.isArray()) continue;
            candidate.forEach(item -> {
                if (!sources.contains(item)) sources.add(item);
            });
        }
    }

    private List<Map<String, Object>> suggestedActions(String answer) {
        List<Map<String, Object>> actions = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(answer);
            JsonNode followUps = root.path("content").path("followUps");
            if (followUps.isArray()) {
                followUps.forEach(item -> {
                    String label = item.path("label").asText("");
                    String query = item.path("query").asText(label);
                    if (StringUtils.hasText(label) && StringUtils.hasText(query)) {
                        Map<String, Object> action = new LinkedHashMap<>();
                        action.put("id", item.path("id").asText("follow-up-" + actions.size()));
                        action.put("label", label);
                        action.put("query", query);
                        actions.add(action);
                    }
                });
            }
            String actionName = root.path("action").asText("");
            String label = root.path("label").asText("");
            if (StringUtils.hasText(actionName) && StringUtils.hasText(label)) {
                Map<String, Object> action = new LinkedHashMap<>();
                action.put("id", actionName);
                action.put("label", label);
                action.put("action", actionName);
                action.put("payload", root.path("payload"));
                actions.add(action);
            }
        } catch (Exception ignored) {
            // Ordinary office and policy responses are not action JSON.
        }
        return actions;
    }

    private String normalizeApiBase() {
        if (!StringUtils.hasText(properties.apiBase()) || !StringUtils.hasText(properties.apiKey())) {
            throw new BusinessException("DIFY_CONFIG_MISSING", "Master Dify configuration is incomplete");
        }
        return properties.apiBase().endsWith("/")
            ? properties.apiBase().substring(0, properties.apiBase().length() - 1)
            : properties.apiBase();
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
        return new BusinessException("DIFY_STREAM_ERROR", "Master Dify streaming request failed");
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

    private String stripThink(String value) {
        return value.replaceAll("(?is)<think>.*?</think>", "").replaceAll("(?is)<think>.*$", "");
    }

    private static final class StreamState {
        private String difyConversationId;
        private String messageId = "";
        private String answer = "";
        private final List<JsonNode> sources = new ArrayList<>();

        private StreamState(String difyConversationId) {
            this.difyConversationId = difyConversationId == null ? "" : difyConversationId;
        }
    }

    private static final class UpstreamException extends RuntimeException {
        private final int status;

        private UpstreamException(int status) {
            this.status = status;
        }
    }
}
