package com.huanbao.aigateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.huanbao.aigateway.config.DifyPolicyProperties;
import com.huanbao.aigateway.dto.ConversationMapping;
import com.huanbao.aigateway.dto.PolicyChatRequest;
import com.huanbao.aigateway.dto.PolicyChatResponse;
import com.huanbao.aigateway.exception.BusinessException;
import com.huanbao.aigateway.security.DataQueryIdentity;
import java.util.ArrayList;
import java.util.Collections;
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
public class PolicyChatService {
    private static final Logger log = LoggerFactory.getLogger(PolicyChatService.class);

    private final RestClient restClient;
    private final DifyPolicyProperties properties;
    private final ConversationMappingService mappingService;

    public PolicyChatService(
        @Qualifier("difyPolicyRestClient") RestClient difyRestClient,
        DifyPolicyProperties properties,
        ConversationMappingService mappingService
    ) {
        this.restClient = difyRestClient;
        this.properties = properties;
        this.mappingService = mappingService;
    }

    public PolicyChatResponse chat(
        PolicyChatRequest request,
        DataQueryIdentity identity,
        ConversationMapping mapping,
        String requestId
    ) {
        if (!StringUtils.hasText(properties.apiKey())) {
            throw new BusinessException("DIFY_CONFIG_MISSING", "policy Dify API key is not configured");
        }

        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("auth_user_id", identity.userId());
        inputs.put("auth_user_code", identity.userCode());
        inputs.put("auth_user_name", identity.userName());
        inputs.put("auth_org_code", identity.orgCode());
        inputs.put("auth_org_name", identity.orgName());
        inputs.put("auth_tenant_id", identity.tenantId());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("inputs", inputs);
        body.put("query", request.query());
        body.put("response_mode", "blocking");
        body.put("conversation_id", mapping.difyConversationId() == null ? "" : mapping.difyConversationId());
        body.put("user", mapping.difyUserId());

        try {
            JsonNode response = restClient.post()
                .uri(normalizeApiBase(properties.apiBase()) + "/chat-messages")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);

            if (response == null) throw new BusinessException("DIFY_ERROR", "Dify returned empty response");
            String difyConversationId = response.path("conversation_id").asText("");
            mappingService.updateDifyConversation(mapping, difyConversationId);
            String answer = response.path("answer").asText("");
            List<JsonNode> resources = readRetrieverResources(response);
            log.info("POLICY_CHAT_PROXIED requestId={} answerLength={}", requestId, answer.length());
            return new PolicyChatResponse(answer, request.conversationId(), resources, requestId, request.conversationId());
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 401 || ex.getStatusCode().value() == 403) {
                throw new BusinessException("DIFY_UNAUTHORIZED", "Dify authorization failed");
            }
            throw new BusinessException("DIFY_ERROR", "Dify request failed");
        } catch (ResourceAccessException ex) {
            throw new BusinessException("DIFY_TIMEOUT", "Dify timeout or network error");
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
        if (!resources.isArray()) return Collections.emptyList();
        List<JsonNode> result = new ArrayList<>();
        resources.forEach(result::add);
        return result;
    }
}
