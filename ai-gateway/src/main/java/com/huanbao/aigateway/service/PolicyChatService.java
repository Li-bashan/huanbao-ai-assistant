package com.huanbao.aigateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.huanbao.aigateway.config.DifyPolicyProperties;
import com.huanbao.aigateway.dto.PolicyChatRequest;
import com.huanbao.aigateway.dto.PolicyChatResponse;
import com.huanbao.aigateway.exception.BusinessException;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Service
public class PolicyChatService {
    private static final Logger log = LoggerFactory.getLogger(PolicyChatService.class);

    private final RestClient restClient;
    private final DifyPolicyProperties properties;

    public PolicyChatService(RestClient difyRestClient, DifyPolicyProperties properties) {
        this.restClient = difyRestClient;
        this.properties = properties;
    }

    public PolicyChatResponse chat(PolicyChatRequest request) {
        if (!StringUtils.hasText(properties.apiKey())) {
            throw new BusinessException("DIFY_CONFIG_MISSING", "policy Dify API key is not configured");
        }

        Map<String, Object> body = Map.of(
            "inputs", Collections.emptyMap(),
            "query", request.query(),
            "response_mode", "blocking",
            "conversation_id", request.conversationId() == null ? "" : request.conversationId(),
            "user", StringUtils.hasText(request.userId()) ? request.userId() : "anonymous"
        );

        try {
            JsonNode response = restClient.post()
                .uri(normalizeApiBase(properties.apiBase()) + "/chat-messages")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);

            if (response == null) {
                throw new BusinessException("DIFY_EMPTY_RESPONSE", "Dify returned empty response");
            }

            String answer = response.path("answer").asText("");
            String conversationId = response.path("conversation_id").asText("");
            List<JsonNode> retrieverResources = readRetrieverResources(response);

            log.info("Policy chat proxied. userId={}, userName={}, conversationId={}, answerLength={}",
                request.userId(), request.userName(), conversationId, answer.length());

            return new PolicyChatResponse(answer, conversationId, retrieverResources);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 401) {
                throw new BusinessException("DIFY_UNAUTHORIZED", "Dify authorization failed");
            }
            throw new BusinessException("DIFY_HTTP_ERROR", "Dify request failed: " + ex.getStatusCode().value());
        } catch (ResourceAccessException ex) {
            throw new BusinessException("DIFY_TIMEOUT_OR_NETWORK_ERROR", "Dify timeout or network error");
        }
    }

    private String normalizeApiBase(String apiBase) {
        if (!StringUtils.hasText(apiBase)) {
            throw new BusinessException("DIFY_CONFIG_MISSING", "policy Dify API base is not configured");
        }
        return apiBase.endsWith("/") ? apiBase.substring(0, apiBase.length() - 1) : apiBase;
    }

    private List<JsonNode> readRetrieverResources(JsonNode response) {
        JsonNode resources = response.path("metadata").path("retriever_resources");
        if (!resources.isArray()) {
            return Collections.emptyList();
        }

        List<JsonNode> result = new ArrayList<>();
        resources.forEach(result::add);
        return result;
    }
}
