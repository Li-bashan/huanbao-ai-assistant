package com.huanbao.dataquery.protocol;

import com.huanbao.dataquery.domain.ops.OpsDomainSemanticProvider;
import com.huanbao.dataquery.domain.ops.OpsOrganization;
import com.huanbao.dataquery.pipeline.CompositeExecutionResult;
import com.huanbao.dataquery.pipeline.DataToDocPipelineService;
import com.huanbao.dataquery.router.AnalysisPlanDto;
import com.huanbao.dataquery.router.IntentClassifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(org.mockito.junit.jupiter.MockitoExtension.class)
class DataQueryExecutionControllerTest {

    @Test
    void sendsCompositeStagesResultAndCompletedInOrder() {
        IntentClassifier classifier = mock(IntentClassifier.class);
        DataToDocPipelineService pipeline = mock(DataToDocPipelineService.class);
        ProtocolAssembler assembler = mock(ProtocolAssembler.class);
        SseStreamDispatcher dispatcher = mock(SseStreamDispatcher.class);
        SseEmitter emitter = mock(SseEmitter.class);
        AnalysisPlanDto plan = new AnalysisPlanDto(
                "COMPOSITE", List.of("全厂发电量"), List.of("集团"), "近三个月", "REPORT", List.of());
        CompositeExecutionResult executionResult = new CompositeExecutionResult(
                plan, Map.of(), List.of(), "", false, "");
        PresentationResponseV2 response = new PresentationResponseV2(
                "2.0", "request", "conversation", "NO_DATA", "empty", "OVERVIEW",
                null, null, null);

        when(dispatcher.createEmitter()).thenReturn(emitter);
        when(dispatcher.send(any(), anyString(), any())).thenReturn(true);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return null;
        }).when(dispatcher).dispatch(eq(emitter), any(Runnable.class));
        when(classifier.classify("查数据并写简报")).thenReturn(plan);
        when(pipeline.execute(anyString(), eq(plan), eq("查数据并写简报"), eq(List.of("10004024"))))
                .thenReturn(executionResult);
        when(assembler.assemble(eq(executionResult), any(UserOrganizationContext.class),
                any(java.time.LocalDate.class), anyString(), anyString())).thenReturn(response);

        DataQueryExecutionController controller = new DataQueryExecutionController(
                classifier, pipeline, assembler, dispatcher, mock(OpsDomainSemanticProvider.class));
        controller.execute(
                new QueryExecuteRequest("查数据并写简报", "conversation"),
                "user-7",
                "10004024");

        var eventNames = forClass(String.class);
        verify(dispatcher, org.mockito.Mockito.times(4)).send(
                eq(emitter), eventNames.capture(), any());
        org.junit.jupiter.api.Assertions.assertEquals(
                List.of("stage", "stage", "analysis_result", "completed"),
                eventNames.getAllValues());
        verify(dispatcher).complete(emitter);
    }

    @Test
    void stopsImmediatelyWhenClientDisconnectedDuringFirstStage() {
        IntentClassifier classifier = mock(IntentClassifier.class);
        DataToDocPipelineService pipeline = mock(DataToDocPipelineService.class);
        ProtocolAssembler assembler = mock(ProtocolAssembler.class);
        SseStreamDispatcher dispatcher = mock(SseStreamDispatcher.class);
        SseEmitter emitter = mock(SseEmitter.class);
        when(dispatcher.createEmitter()).thenReturn(emitter);
        when(dispatcher.send(any(), eq("stage"), any())).thenReturn(false);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return null;
        }).when(dispatcher).dispatch(eq(emitter), any(Runnable.class));

        DataQueryExecutionController controller = new DataQueryExecutionController(
                classifier, pipeline, assembler, dispatcher, mock(OpsDomainSemanticProvider.class));
        controller.execute(new QueryExecuteRequest("查数据", "conversation"), "user-7", "10004024");

        verify(classifier, never()).classify(anyString());
        verify(pipeline, never()).execute(anyString(), any(), anyString(), any());
        verify(dispatcher).complete(emitter);
    }

    @Test
    void returnsActionableErrorWhenQueryCannotBeResolved() {
        IntentClassifier classifier = mock(IntentClassifier.class);
        DataToDocPipelineService pipeline = mock(DataToDocPipelineService.class);
        ProtocolAssembler assembler = mock(ProtocolAssembler.class);
        SseStreamDispatcher dispatcher = mock(SseStreamDispatcher.class);
        SseEmitter emitter = mock(SseEmitter.class);
        AnalysisPlanDto plan = new AnalysisPlanDto(
                "DATA_QUERY", List.of(), List.of("项目公司"), "今年", "FACT", List.of());

        when(dispatcher.createEmitter()).thenReturn(emitter);
        when(dispatcher.send(any(), anyString(), any())).thenReturn(true);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return null;
        }).when(dispatcher).dispatch(eq(emitter), any(Runnable.class));
        when(classifier.classify("查一个未知指标")).thenReturn(plan);
        when(pipeline.execute(anyString(), eq(plan), eq("查一个未知指标"), eq(List.of("10004024"))))
                .thenThrow(new IllegalArgumentException("The analysis plan has no metric input"));

        DataQueryExecutionController controller = new DataQueryExecutionController(
                classifier, pipeline, assembler, dispatcher, mock(OpsDomainSemanticProvider.class));
        controller.execute(
                new QueryExecuteRequest("查一个未知指标", "conversation"),
                "user-7",
                "10004024");

        var payload = forClass(Object.class);
        verify(dispatcher).send(eq(emitter), eq("error"), payload.capture());
        Map<?, ?> error = (Map<?, ?>) payload.getValue();
        org.junit.jupiter.api.Assertions.assertEquals("QUERY_NOT_SUPPORTED", error.get("code"));
        org.junit.jupiter.api.Assertions.assertEquals(
                "暂未识别出可查询的生产指标，请换用指标库中的名称重试。",
                error.get("message"));
        verify(dispatcher).complete(emitter);
    }

    @Test
    void expandsWildcardOrgScopeToAllKnownOrganizations() {
        IntentClassifier classifier = mock(IntentClassifier.class);
        DataToDocPipelineService pipeline = mock(DataToDocPipelineService.class);
        ProtocolAssembler assembler = mock(ProtocolAssembler.class);
        SseStreamDispatcher dispatcher = mock(SseStreamDispatcher.class);
        SseEmitter emitter = mock(SseEmitter.class);
        OpsDomainSemanticProvider semanticProvider = mock(OpsDomainSemanticProvider.class);
        AnalysisPlanDto plan = new AnalysisPlanDto(
                "DATA_QUERY", List.of("全厂发电量"), List.of("集团"), "今年", "TREND", List.of());
        CompositeExecutionResult executionResult = new CompositeExecutionResult(
                plan, Map.of(), List.of(), "", false, "");
        PresentationResponseV2 response = new PresentationResponseV2(
                "2.0", "request", "conversation", "OK", "ok", "OVERVIEW",
                null, null, null);

        when(dispatcher.createEmitter()).thenReturn(emitter);
        when(dispatcher.send(any(), anyString(), any())).thenReturn(true);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return null;
        }).when(dispatcher).dispatch(eq(emitter), any(Runnable.class));
        when(classifier.classify("查询集团发电量")).thenReturn(plan);
        when(semanticProvider.getOrganizations()).thenReturn(List.of(
                new OpsOrganization("10004024", "秦皇岛公司", "秦皇岛", "垃圾焚烧发电项目", null, true),
                new OpsOrganization("10004025", "测试公司", "测试", "垃圾焚烧发电项目", null, false)));
        when(pipeline.execute(anyString(), eq(plan), eq("查询集团发电量"),
                eq(List.of("10004024", "10004025")))).thenReturn(executionResult);
        when(assembler.assemble(eq(executionResult), any(UserOrganizationContext.class),
                any(java.time.LocalDate.class), anyString(), anyString())).thenReturn(response);

        DataQueryExecutionController controller = new DataQueryExecutionController(
                classifier, pipeline, assembler, dispatcher, semanticProvider);
        controller.execute(
                new QueryExecuteRequest("查询集团发电量", "conversation"),
                "user-7",
                DataQueryExecutionController.ALL_ORGS_WILDCARD);

        verify(pipeline).execute(anyString(), eq(plan), eq("查询集团发电量"),
                eq(List.of("10004024", "10004025")));
        verify(dispatcher).complete(emitter);
    }
}
