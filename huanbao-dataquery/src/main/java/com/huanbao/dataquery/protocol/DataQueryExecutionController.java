package com.huanbao.dataquery.protocol;

import com.huanbao.dataquery.core.repository.PartitionTablePruner;
import com.huanbao.dataquery.pipeline.CompositeExecutionResult;
import com.huanbao.dataquery.pipeline.DataToDocPipelineService;
import com.huanbao.dataquery.router.AnalysisPlanDto;
import com.huanbao.dataquery.router.IntentClassifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 内部问数执行接口，负责阶段事件编排，不暴露 Dify 或数据库细节。 */
@RestController
@RequestMapping("/api/query")
public final class DataQueryExecutionController {

    private static final Logger LOGGER = LoggerFactory.getLogger(DataQueryExecutionController.class);

    private static final String DATA_STAGE_MESSAGE =
            "正在调用智能问数，检索生产数据并生成图表...";
    private static final String OFFICE_STAGE_MESSAGE =
            "正在调用智能办公，基于核验数据起草经营简报...";

    private final IntentClassifier intentClassifier;
    private final DataToDocPipelineService pipelineService;
    private final ProtocolAssembler protocolAssembler;
    private final SseStreamDispatcher streamDispatcher;

    @Autowired
    public DataQueryExecutionController(
            IntentClassifier intentClassifier,
            DataToDocPipelineService pipelineService,
            ProtocolAssembler protocolAssembler,
            SseStreamDispatcher streamDispatcher) {
        this.intentClassifier = intentClassifier;
        this.pipelineService = pipelineService;
        this.protocolAssembler = protocolAssembler;
        this.streamDispatcher = streamDispatcher;
    }

    @PostMapping(value = "/execute", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter execute(
            @RequestBody QueryExecuteRequest request,
            @RequestHeader("X-Internal-User-Id") String userId,
            @RequestHeader("X-Internal-Allowed-Orgs") String allowedOrgsHeader) {
        validateRequest(request, userId);
        List<String> allowedOrgs = parseAllowedOrgs(allowedOrgsHeader);
        if (allowedOrgs.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "X-Internal-Allowed-Orgs must not be empty");
        }

        String requestId = UUID.randomUUID().toString();
        String conversationId = request.conversationId().isBlank()
                ? "hb_" + UUID.randomUUID()
                : request.conversationId();
        UserOrganizationContext userContext = new UserOrganizationContext(userId, allowedOrgs);
        SseEmitter emitter = streamDispatcher.createEmitter();
        streamDispatcher.dispatch(emitter, () -> executeAsync(
                emitter, request, requestId, conversationId, userContext));
        return emitter;
    }

    private void executeAsync(
            SseEmitter emitter,
            QueryExecuteRequest request,
            String requestId,
            String conversationId,
            UserOrganizationContext userContext) {
        try {
            if (!sendStage(emitter, requestId, conversationId, "data-query", DATA_STAGE_MESSAGE)) {
                return;
            }

            AnalysisPlanDto plan = intentClassifier.classify(request.query());
            boolean composite = "COMPOSITE".equalsIgnoreCase(plan.primaryIntent());
            if (composite && !sendStage(
                    emitter, requestId, conversationId, "office-ai", OFFICE_STAGE_MESSAGE)) {
                return;
            }

            String conversationKey = userContext.userId() + ":" + conversationId;
            CompositeExecutionResult executionResult = pipelineService.execute(
                    conversationKey,
                    plan,
                    request.query(),
                    userContext.allowedOrgs());
            PresentationResponseV2 response = protocolAssembler.assemble(
                    executionResult,
                    userContext,
                    PartitionTablePruner.DEFAULT_MAX_DATA_DATE,
                    requestId,
                    conversationId);

            Map<String, Object> resultPayload = new LinkedHashMap<>();
            resultPayload.put("requestId", requestId);
            resultPayload.put("conversationId", conversationId);
            resultPayload.put("response", response);
            if (!streamDispatcher.send(emitter, "analysis_result", resultPayload)) {
                return;
            }

                streamDispatcher.send(emitter, "completed", Map.of(
                    "requestId", requestId,
                    "conversationId", conversationId,
                    "messageId", UUID.randomUUID().toString()));
        } catch (Exception exception) {
            LOGGER.error("Query execution failed, requestId={}", requestId, exception);
            boolean unsupported = exception instanceof IllegalArgumentException;
            streamDispatcher.send(emitter, "error", Map.of(
                    "requestId", requestId,
                    "conversationId", conversationId,
                    "code", unsupported ? "QUERY_NOT_SUPPORTED" : "QUERY_EXECUTION_FAILED",
                    "message", unsupported
                            ? "暂未识别出可查询的生产指标，请换用指标库中的名称重试。"
                            : "智能问数执行失败，请稍后重试。"));
        } finally {
            streamDispatcher.complete(emitter);
        }
    }

    private boolean sendStage(
            SseEmitter emitter,
            String requestId,
            String conversationId,
            String stage,
            String message) {
        return streamDispatcher.send(emitter, "stage", Map.of(
                "requestId", requestId,
                "conversationId", conversationId,
                "stage", stage,
                "message", message));
    }

    private static void validateRequest(QueryExecuteRequest request, String userId) {
        if (request == null || request.query().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "query must not be blank");
        }
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "X-Internal-User-Id must not be blank");
        }
    }

    private static List<String> parseAllowedOrgs(String header) {
        if (header == null || header.isBlank()) {
            return List.of();
        }
        return Arrays.stream(header.split("[,，;；]"))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
    }
}
