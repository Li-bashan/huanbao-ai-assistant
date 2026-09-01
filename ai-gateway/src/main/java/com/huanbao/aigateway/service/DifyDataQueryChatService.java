package com.huanbao.aigateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.huanbao.aigateway.config.DataQueryProperties;
import com.huanbao.aigateway.dto.ConversationMapping;
import com.huanbao.aigateway.dto.DataQueryAuthorization;
import com.huanbao.aigateway.dto.DataQueryChatRequest;
import com.huanbao.aigateway.exception.BusinessException;
import com.huanbao.aigateway.security.DataQueryIdentity;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
public class DifyDataQueryChatService {
    private static final Logger log = LoggerFactory.getLogger(DifyDataQueryChatService.class);
    private static final String PROTOCOL_VERSION = "2.0";
    private static final long SSE_HEARTBEAT_INTERVAL_MS = 10_000L;
    private static final ScheduledExecutorService SSE_HEARTBEAT_EXECUTOR =
        Executors.newScheduledThreadPool(2, new DaemonThreadFactory());
    private static final Pattern ANALYSIS_STATE_MARKER = Pattern.compile(
        "<!--HUANBAO_ANALYSIS_STATE:([\\s\\S]*?)-->", Pattern.CASE_INSENSITIVE
    );
    private static final Pattern ANALYSIS_CHART_MARKER = Pattern.compile(
        "<!--HUANBAO_ANALYSIS_CHART:([\\s\\S]*?)-->", Pattern.CASE_INSENSITIVE
    );
    private static final Pattern ANALYSIS_FOLLOWUPS_MARKER = Pattern.compile(
        "<!--HUANBAO_ANALYSIS_FOLLOWUPS:([\\s\\S]*?)-->", Pattern.CASE_INSENSITIVE
    );

    private final RestClient restClient;
    private final DataQueryProperties properties;
    private final ObjectMapper objectMapper;
    private final DataQueryAuditService auditService;
    private final ConversationMappingService mappingService;
    private final DifyUserIdentityService difyUserIdentityService;

    public DifyDataQueryChatService(
        @Qualifier("difyDataQueryRestClient") RestClient restClient,
        DataQueryProperties properties,
        ObjectMapper objectMapper,
        DataQueryAuditService auditService,
        ConversationMappingService mappingService,
        DifyUserIdentityService difyUserIdentityService
    ) {
        this.restClient = restClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
        this.mappingService = mappingService;
        this.difyUserIdentityService = difyUserIdentityService;
    }

    public boolean stream(
        DataQueryChatRequest request,
        DataQueryIdentity identity,
        DataQueryAuthorization authorization,
        ConversationMapping mapping,
        String clientIp,
        String userAgent,
        OutputStream output
    ) {
        Instant startedAt = Instant.now();
        StreamState state = new StreamState(request.requestId(), request.conversationId(), mapping.difyConversationId());
        ScheduledFuture<?> heartbeat = startSseHeartbeat(output, request.requestId());
        try {
            emit(output, "analysis_started", Map.of(
                "requestId", request.requestId(),
                "conversationId", safe(request.conversationId()),
                "stage", "understanding"
            ));

            restClient.post()
                .uri(normalizeApiBase(properties.apiBase()) + "/chat-messages")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .body(buildDifyBody(request, identity, authorization, mapping))
                .exchange((clientRequest, clientResponse) -> {
                    if (!clientResponse.getStatusCode().is2xxSuccessful()) {
                        String detail = readText(clientResponse.getBody());
                        throw new DifyUpstreamException(clientResponse.getStatusCode().value(), detail);
                    }
                    readDifyStream(clientResponse.getBody(), output, state);
                    return null;
                });

            ObjectNode response = toProtocolResponse(state.finalAnswer, request, state);
            mappingService.updateDifyConversation(mapping, state.difyConversationId);
            String event = "clarification".equals(response.path("messageType").asText())
                ? "clarification" : "analysis_result";
            emit(output, event, resultPayload(request, response, state));
            emit(output, "completed", Map.of(
                "requestId", request.requestId(),
                "conversationId", safe(state.clientConversationId),
                "messageId", safe(state.messageId)
            ));
            auditService.record(
                request.requestId(), request.conversationId(), state.difyConversationId,
                difyUserIdentityService.auditHash(mapping.difyUserId()), identity, request.query(),
                response.path("analysisType").asText(""), resolvedIndicator(response),
                organizationScope(response), timeRange(response), authorization.organizationScope(),
                response.path("status").asText("SUCCESS_WITH_DATA"), "", elapsedMs(startedAt),
                dataCutoffDate(response), state.difyRequestId, state.workflowRunId, clientIp, userAgent,
                startedAt, Instant.now()
            );
            return true;
        } catch (Exception ex) {
            BusinessException error = toBusinessException(ex);
            log.warn("DATA_QUERY_DIFY_FAILED requestId={} code={}", request.requestId(), error.getCode());
            emitError(output, request, state, error);
            auditService.record(
                request.requestId(), request.conversationId(), state.difyConversationId,
                difyUserIdentityService.auditHash(mapping.difyUserId()), identity, request.query(),
                "", "", "", "", authorization.organizationScope(), error.getCode(), error.getCode(),
                elapsedMs(startedAt), "", state.difyRequestId, state.workflowRunId, clientIp, userAgent,
                startedAt, Instant.now()
            );
            return false;
        } finally {
            heartbeat.cancel(false);
        }
    }

    /**
     * Dify workflows can be quiet while an LLM or SQL node is running. A
     * comment frame keeps the browser and any reverse proxy from treating the
     * otherwise healthy streaming response as an idle connection. Comments
     * are deliberately not application events and are ignored by SSE clients.
     */
    private ScheduledFuture<?> startSseHeartbeat(OutputStream output, String requestId) {
        return SSE_HEARTBEAT_EXECUTOR.scheduleAtFixedRate(() -> {
            try {
                synchronized (output) {
                    output.write(": heartbeat\n\n".getBytes(StandardCharsets.UTF_8));
                    output.flush();
                }
            } catch (IOException ex) {
                log.debug("DATA_QUERY_SSE_HEARTBEAT_STOPPED requestId={}", requestId);
            }
        }, SSE_HEARTBEAT_INTERVAL_MS, SSE_HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private Map<String, Object> buildDifyBody(
        DataQueryChatRequest request,
        DataQueryIdentity identity,
        DataQueryAuthorization authorization,
        ConversationMapping mapping
    ) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("auth_request_id", request.requestId());
        inputs.put("auth_user_id", identity.userId());
        inputs.put("auth_user_code", identity.userCode());
        inputs.put("auth_user_name", identity.userName());
        inputs.put("auth_org_code", identity.orgCode());
        inputs.put("auth_org_name", identity.orgName());
        inputs.put("auth_tenant_id", identity.tenantId());
        inputs.put("auth_data_scope_json", writeJson(authorizationPayload(authorization)));
        inputs.put("auth_context_json", writeJson(authorizationPayload(authorization)));
        inputs.put("auth_allowed_org_codes", writeJson(authorization.allowedOrgCodes()));
        inputs.put("auth_allowed_indicator_codes", writeJson(authorization.allowedIndicatorCodes()));
        inputs.put("auth_allow_group_ranking", String.valueOf(authorization.allowGroupRanking()));
        inputs.put("auth_allow_all_organizations", String.valueOf(authorization.allowAllOrganizations()));
        try {
            inputs.put("auth_context_json", objectMapper.writeValueAsString(authorizationPayload(authorization)));
        } catch (Exception ex) {
            throw new BusinessException("DIFY_ERROR", "authorization context is unavailable");
        }
        String analysisState = clientContextValue(request, "analysisState", "analysis_state");
        if (StringUtils.hasText(analysisState)) inputs.put("analysis_state", analysisState);
        if (request.clarification() != null) {
            try {
                inputs.put("clarification_json", objectMapper.writeValueAsString(Map.of(
                    "slot", safe(request.clarification().slot()),
                    "selectedValue", safe(request.clarification().selectedValue()),
                    "selectedLabel", safe(request.clarification().selectedLabel())
                )));
            } catch (Exception ex) {
                throw new BusinessException("DIFY_ERROR", "clarification context is unavailable");
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("inputs", inputs);
        body.put("query", request.query());
        body.put("response_mode", "streaming");
        // Only the Gateway-owned mapping may select Dify's internal conversation.
        body.put("conversation_id", safe(mapping.difyConversationId()));
        // identity has already been verified and authorized by the Gateway;
        // it is never read from the browser body in SIGNED_HEADER mode.
        body.put("user", mapping.difyUserId());
        return body;
    }

    private String clientContextValue(DataQueryChatRequest request, String... keys) {
        if (request.clientContext() == null) return "";
        for (String key : keys) {
            Object value = request.clientContext().get(key);
            if (value instanceof String text && StringUtils.hasText(text)) return text.trim();
        }
        return "";
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new BusinessException("DIFY_ERROR", "unable to serialize authorization context");
        }
    }

    private Map<String, Object> authorizationPayload(DataQueryAuthorization authorization) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("identityVerified", authorization.identityVerified());
        result.put("identitySource", authorization.identitySource());
        result.put("userId", authorization.userId());
        result.put("userCode", authorization.userCode());
        result.put("userName", authorization.userName());
        result.put("orgCode", authorization.orgCode());
        result.put("orgName", authorization.orgName());
        result.put("tenantId", authorization.tenantId());
        result.put("organizationScope", authorization.organizationScope());
        result.put("allowGroupRanking", authorization.allowGroupRanking());
        result.put("allowedIndicatorCodes", authorization.allowedIndicatorCodes());
        result.put("allowedOrgCodes", authorization.allowedOrgCodes());
        result.put("allowAllOrganizations", authorization.allowAllOrganizations());
        return result;
    }

    private void readDifyStream(InputStream input, OutputStream output, StreamState state) throws IOException {
        if (input == null) throw new BusinessException("DIFY_ERROR", "Dify returned an empty stream");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            String event = "";
            StringBuilder data = new StringBuilder();
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    consumeDifyEvent(event, data.toString(), output, state);
                    event = "";
                    data.setLength(0);
                    continue;
                }
                if (line.startsWith("event:")) {
                    event = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    if (data.length() > 0) data.append('\n');
                    data.append(line.substring(5).stripLeading());
                }
            }
            consumeDifyEvent(event, data.toString(), output, state);
        }
    }

    private void consumeDifyEvent(
        String event,
        String rawData,
        OutputStream output,
        StreamState state
    ) throws IOException {
        if (!StringUtils.hasText(rawData) || "[DONE]".equals(rawData)) return;
        JsonNode data;
        try {
            data = objectMapper.readTree(rawData);
        } catch (Exception ex) {
            throw new BusinessException("PROTOCOL_VALIDATION_FAILED", "Dify returned invalid SSE data");
        }

        state.difyConversationId = firstNonBlank(
            text(data, "conversation_id"), text(data.path("data"), "conversation_id"), state.difyConversationId
        );
        state.messageId = firstNonBlank(text(data, "message_id"), text(data.path("data"), "message_id"), state.messageId);
        state.difyRequestId = firstNonBlank(text(data, "task_id"), text(data, "request_id"), state.difyRequestId);
        state.workflowRunId = firstNonBlank(text(data, "workflow_run_id"), text(data.path("data"), "workflow_run_id"), state.workflowRunId);

        if ("error".equals(event) || "error".equals(data.path("event").asText())) {
            String message = firstNonBlank(text(data, "message"), text(data.path("data"), "message"), "Dify returned an error");
            throw new BusinessException("DIFY_ERROR", message);
        }

        if ("workflow_started".equals(event) || "workflow_started".equals(data.path("event").asText())) {
            emit(output, "analysis_started", Map.of("requestId", state.requestId, "stage", "planning"));
        } else if ("node_started".equals(event) || "node_finished".equals(event)) {
            emit(output, "analysis_started", Map.of("requestId", state.requestId, "stage", "querying"));
        }

        String answer = extractAnswer(data);
        if (StringUtils.hasText(answer)) {
            String previous = state.finalAnswer;
            state.finalAnswer = mergeAnswer(previous, answer);
            if (!looksStructured(state.finalAnswer)) {
                String delta = state.finalAnswer.startsWith(previous)
                    ? state.finalAnswer.substring(previous.length()) : answer;
                if (StringUtils.hasText(delta)) emit(output, "text_delta", Map.of("requestId", state.requestId, "delta", delta));
            }
        }
    }

    private ObjectNode toProtocolResponse(String answer, DataQueryChatRequest request, StreamState state) {
        JsonNode parsed;
        try {
            parsed = StringUtils.hasText(answer) ? objectMapper.readTree(answer) : null;
        } catch (Exception ex) {
            throw invalidProtocol();
        }

        if (!isValidProtocolResponse(parsed)) throw invalidProtocol();
        ObjectNode response = (ObjectNode) parsed.deepCopy();

        response.put("requestId", request.requestId());
        // Dify returns the canonical conversation id during the stream. Keep
        // it as the product continuity contract for the next turn.
        response.put("conversationId", safe(state.clientConversationId));
        applyAnalysisMarkers(response, answer);
        return response;
    }

    private boolean isValidProtocolResponse(JsonNode response) {
        if (response == null || !response.isObject()
            || !PROTOCOL_VERSION.equals(response.path("protocolVersion").asText())
            || !response.path("status").isTextual()
            || !StringUtils.hasText(response.path("status").asText())
            || "LEGACY_RESPONSE".equals(response.path("status").asText())
            || !response.path("messageType").isTextual()
            || !StringUtils.hasText(response.path("messageType").asText())
            || !response.path("analysisType").isTextual()
            || !response.path("content").isObject()) {
            return false;
        }

        JsonNode content = response.path("content");
        return content.path("title").isTextual()
            && content.path("summary").isTextual()
            && content.path("metrics").isArray()
            && (content.path("table").isNull() || content.path("table").isObject())
            && (content.path("chart").isNull() || content.path("chart").isObject())
            && content.path("insights").isArray()
            && content.path("evidence").isArray()
            && content.path("dataInfo").isObject()
            && content.path("followUps").isArray();
    }

    private BusinessException invalidProtocol() {
        return new BusinessException(
            "PROTOCOL_VALIDATION_FAILED", "Dify returned an invalid analysis protocol"
        );
    }

    private void applyAnalysisMarkers(ObjectNode response, String answer) {
        JsonNode analysisState = markerJson(answer, ANALYSIS_STATE_MARKER);
        JsonNode chartOption = markerJson(answer, ANALYSIS_CHART_MARKER);
        JsonNode followUps = markerJson(answer, ANALYSIS_FOLLOWUPS_MARKER);
        if (analysisState == null && chartOption == null && followUps == null) return;

        ObjectNode content = response.with("content");
        content.put("summary", stripAnalysisMarkers(answer));
        ObjectNode meta = response.with("meta");

        if (analysisState != null && analysisState.isObject()) {
            response.put("messageType", "analysis");
            response.put("analysisType", normalizedAnalysisType(analysisState.path("analysis_type").asText()));
            meta.set("analysisState", analysisState);
            ObjectNode dataInfo = content.with("dataInfo");
            JsonNode indicator = analysisState.path("indicator");
            dataInfo.put("indicatorCode", indicator.path("code").asText(""));
            dataInfo.put("indicatorName", indicator.path("name").asText(""));
            if (analysisState.has("organization_scope")) {
                dataInfo.set("organizationScope", analysisState.path("organization_scope"));
            }
            if (analysisState.has("time")) dataInfo.set("timeRange", analysisState.path("time"));
        }

        if (chartOption != null && chartOption.isObject()) {
            ObjectNode chart = toProtocolChart(chartOption);
            if (chart != null) {
                content.set("chart", chart);
                meta.set("chartOption", chartOption);
            }
        }

        if (followUps != null && followUps.isArray()) {
            var normalized = objectMapper.createArrayNode();
            int index = 0;
            for (JsonNode item : followUps) {
                String label = item.isTextual()
                    ? item.asText().trim()
                    : firstNonBlank(item.path("label").asText(""), item.path("title").asText(""), item.path("query").asText(""));
                String query = item.isTextual()
                    ? label
                    : firstNonBlank(item.path("query").asText(""), item.path("prompt").asText(""), label);
                if (!StringUtils.hasText(label) || !StringUtils.hasText(query)) continue;
                ObjectNode followUp = normalized.addObject();
                followUp.put("id", firstNonBlank(item.path("id").asText(""), "follow-up-" + index));
                followUp.put("label", label);
                followUp.put("query", query);
                index++;
                if (index >= 6) break;
            }
            content.set("followUps", normalized);
        }
    }

    private JsonNode markerJson(String answer, Pattern pattern) {
        if (!StringUtils.hasText(answer)) return null;
        Matcher matcher = pattern.matcher(answer);
        if (!matcher.find()) return null;
        try {
            return objectMapper.readTree(matcher.group(1).trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private String stripAnalysisMarkers(String answer) {
        String value = answer == null ? "" : answer;
        for (Pattern pattern : List.of(ANALYSIS_STATE_MARKER, ANALYSIS_CHART_MARKER, ANALYSIS_FOLLOWUPS_MARKER)) {
            value = pattern.matcher(value).replaceAll("");
        }
        return value.replaceAll("\\n{3,}", "\\n\\n").trim();
    }

    private String normalizedAnalysisType(String value) {
        if ("EXECUTIVE_OVERVIEW".equals(value)) return "OVERVIEW";
        if ("CORRELATION".equals(value)) return "COMPARISON";
        return StringUtils.hasText(value) ? value : "FACT";
    }

    private ObjectNode toProtocolChart(JsonNode option) {
        JsonNode series = option.path("series");
        if (!series.isArray() || series.isEmpty()) return null;
        ObjectNode chart = objectMapper.createObjectNode();
        chart.put("type", series.get(0).path("type").asText("bar"));
        JsonNode title = option.path("title");
        chart.put("title", title.isObject() ? title.path("text").asText("") : title.asText(""));
        JsonNode xAxis = option.path("xAxis");
        if (xAxis.isArray() && !xAxis.isEmpty()) xAxis = xAxis.get(0);
        var categories = chart.putArray("categories");
        JsonNode categoryData = xAxis.path("data");
        if (categoryData.isArray()) {
            for (int i = 0; i < Math.min(categoryData.size(), 1000); i++) {
                categories.add(categoryData.get(i).asText(""));
            }
        }
        var normalizedSeries = chart.putArray("series");
        for (int i = 0; i < Math.min(series.size(), 12); i++) {
            JsonNode item = series.get(i);
            if (!item.isObject() || !item.path("data").isArray()) continue;
            ObjectNode next = normalizedSeries.addObject();
            next.put("name", item.path("name").asText(""));
            next.put("type", item.path("type").asText("bar"));
            next.set("data", item.path("data").deepCopy());
        }
        return normalizedSeries.isEmpty() ? null : chart;
    }

    private Map<String, Object> resultPayload(
        DataQueryChatRequest request,
        ObjectNode response,
        StreamState state
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestId", request.requestId());
        payload.put("conversationId", safe(state.clientConversationId));
        payload.put("messageId", safe(state.messageId));
        payload.put("response", response);
        return payload;
    }

    private void emitError(
        OutputStream output,
        DataQueryChatRequest request,
        StreamState state,
        BusinessException error
    ) {
        try {
            emit(output, "error", Map.of(
                "requestId", request.requestId(),
                "conversationId", safe(state.clientConversationId),
                "code", error.getCode(),
                "message", error.getMessage() == null ? "智能问数分析失败，请稍后重试。" : error.getMessage()
            ));
        } catch (IOException writeError) {
            log.warn("DATA_QUERY_SSE_ERROR_WRITE_FAILED requestId={}", request.requestId());
        }
    }

    private void emit(OutputStream output, String event, Object data) throws IOException {
        synchronized (output) {
            output.write(("event: " + event + "\n").getBytes(StandardCharsets.UTF_8));
            output.write(("data: " + objectMapper.writeValueAsString(data) + "\n\n").getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
    }

    private BusinessException toBusinessException(Exception error) {
        if (error instanceof BusinessException businessException) return businessException;
        if (error instanceof DifyUpstreamException upstream) {
            if (upstream.status == 401 || upstream.status == 403) {
                return new BusinessException("DIFY_UNAUTHORIZED", "Dify authorization failed");
            }
            if (upstream.status == 408 || upstream.status == 429 || upstream.status >= 500) {
                return new BusinessException("DIFY_TIMEOUT", "Dify request timed out or is unavailable");
            }
            return new BusinessException("DIFY_ERROR", "Dify request failed");
        }
        if (error instanceof ResourceAccessException) {
            return new BusinessException("DIFY_TIMEOUT", "Dify request timed out or is unavailable");
        }
        if (error instanceof RestClientResponseException responseException
            && responseException.getStatusCode().value() == 401) {
            return new BusinessException("DIFY_UNAUTHORIZED", "Dify authorization failed");
        }
        return new BusinessException("DIFY_ERROR", "Dify request failed");
    }

    private String extractAnswer(JsonNode data) {
        for (JsonNode candidate : List.of(
            data.path("answer"), data.path("text"), data.path("data").path("answer"),
            data.path("data").path("text"), data.path("data").path("outputs"), data.path("outputs"),
            data.path("result_text"), data.path("data").path("result_text"), data.path("visible_answer")
        )) {
            String value = extractText(candidate, 0);
            if (StringUtils.hasText(value)) return value;
        }
        return "";
    }

    private String extractText(JsonNode node, int depth) {
        if (node == null || node.isMissingNode() || node.isNull() || depth > 5) return "";
        if (node.isTextual()) return node.asText();
        if (!node.isObject()) return "";
        for (String key : List.of("answer", "text", "response", "output", "result", "result_text", "visible_answer", "content")) {
            String value = extractText(node.path(key), depth + 1);
            if (StringUtils.hasText(value)) return value;
        }
        return "";
    }

    private String mergeAnswer(String previous, String incoming) {
        if (!StringUtils.hasText(previous)) return incoming;
        if (incoming.startsWith(previous)) return incoming;
        if (previous.startsWith(incoming)) return previous;
        return previous + incoming;
    }

    private boolean looksStructured(String value) {
        String normalized = value == null ? "" : value.stripLeading();
        return normalized.startsWith("{") || normalized.startsWith("[") || normalized.startsWith("```json");
    }

    private String resolvedIndicator(JsonNode response) {
        return firstNonBlank(
            response.path("content").path("dataInfo").path("indicatorName").asText(""),
            response.path("content").path("dataInfo").path("indicatorCode").asText(""),
            response.path("meta").path("resolvedIndicator").asText("")
        );
    }

    private String organizationScope(JsonNode response) {
        JsonNode value = response.path("content").path("dataInfo").path("organizationScope");
        return value.isMissingNode() ? response.path("meta").path("organizationScope").asText("") : value.toString();
    }

    private String timeRange(JsonNode response) {
        JsonNode value = response.path("content").path("dataInfo").path("timeRange");
        return value.isMissingNode() ? response.path("meta").path("timeRange").toString() : value.toString();
    }

    private String dataCutoffDate(JsonNode response) {
        return firstNonBlank(
            response.path("content").path("dataInfo").path("dataCutoffDate").asText(""),
            response.path("meta").path("dataCutoffDate").asText("")
        );
    }

    private String normalizeApiBase(String apiBase) {
        if (!StringUtils.hasText(apiBase)) throw new BusinessException("DIFY_CONFIG_MISSING", "data query Dify API base is not configured");
        if (!StringUtils.hasText(properties.apiKey())) throw new BusinessException("DIFY_CONFIG_MISSING", "data query Dify API key is not configured");
        return apiBase.endsWith("/") ? apiBase.substring(0, apiBase.length() - 1) : apiBase;
    }

    private long elapsedMs(Instant startedAt) {
        return Math.max(0, Instant.now().toEpochMilli() - startedAt.toEpochMilli());
    }

    private String text(JsonNode node, String field) {
        return node == null ? "" : node.path(field).asText("");
    }

    private String firstNonBlank(String... values) {
        for (String value : values) if (StringUtils.hasText(value)) return value;
        return "";
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private static final class StreamState {
        private final String requestId;
        private final String clientConversationId;
        private String difyConversationId;
        private String messageId = "";
        private String difyRequestId = "";
        private String workflowRunId = "";
        private String finalAnswer = "";

        private StreamState(String requestId, String conversationId, String difyConversationId) {
            this.requestId = requestId;
            this.clientConversationId = conversationId == null ? "" : conversationId;
            this.difyConversationId = difyConversationId == null ? "" : difyConversationId;
        }
    }

    private static final class DifyUpstreamException extends RuntimeException {
        private final int status;

        private DifyUpstreamException(int status, String ignoredDetail) {
            this.status = status;
        }
    }

    private String readText(InputStream input) {
        if (input == null) return "";
        try {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            return "";
        }
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "data-query-sse-heartbeat");
            thread.setDaemon(true);
            return thread;
        }
    }
}
