package com.huanbao.dataquery.router;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huanbao.dataquery.domain.ops.OpsDomainSemanticProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class IntentClassifierTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockRestServiceServer server;
    private IntentClassifier classifier;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("http://vllm.test/v1");
        server = MockRestServiceServer.bindTo(builder).build();
        classifier = new IntentClassifier(
                builder.build(), objectMapper, new OpsDomainSemanticProvider());
    }

    @AfterEach
    void verifyMockServer() {
        server.verify();
    }

    @Test
    void classifiesCompositeDataQueryAndBrief() throws Exception {
        respondWith(new AnalysisPlanDto(
                "COMPOSITE", List.of("垃圾入厂量"), List.of(), "近三个月", "REPORT",
                List.of("QUERY_DATA", "DRAFT_BRIEF")));

        AnalysisPlanDto plan = classifier.classify("查近三个月垃圾入厂量，帮我做一份分析简报");

        assertEquals("COMPOSITE", plan.primaryIntent());
        assertEquals(List.of("QUERY_DATA", "DRAFT_BRIEF"), plan.subTasks());
        assertEquals("近三个月", plan.timeExpression());
        assertEquals("REPORT", plan.analysisType());
    }

    @Test
    void classifiesRankingQuery() throws Exception {
        respondWith(new AnalysisPlanDto(
                "DATA_QUERY", List.of("发电量"), List.of("项目公司"), "今年", "RANKING", List.of()));

        AnalysisPlanDto plan = classifier.classify("今年项目公司发电量排名");

        assertEquals("DATA_QUERY", plan.primaryIntent());
        assertEquals("RANKING", plan.analysisType());
        assertEquals(List.of("发电量"), plan.metricInputs());
    }

    @Test
    void extractsCompanyAndMetricForFactQuery() throws Exception {
        respondWith(new AnalysisPlanDto(
                "DATA_QUERY", List.of("发电量"), List.of("秦皇岛"), "8月份", "FACT", List.of()));

        AnalysisPlanDto plan = classifier.classify("秦皇岛8月份发电量是多少");

        assertEquals("DATA_QUERY", plan.primaryIntent());
        assertEquals(List.of("秦皇岛"), plan.orgInputs());
        assertEquals(List.of("发电量"), plan.metricInputs());
        assertEquals("8月份", plan.timeExpression());
        assertEquals("FACT", plan.analysisType());
    }

    @Test
    void classifiesPolicyQuestion() throws Exception {
        respondWith(new AnalysisPlanDto(
                "POLICY", List.of(), List.of(), "", "FACT", List.of()));

        AnalysisPlanDto plan = classifier.classify("差旅报销住宿标准是多少");

        assertEquals("POLICY", plan.primaryIntent());
        assertEquals("FACT", plan.analysisType());
    }

    @Test
    void classifiesOfficeDraftingRequest() throws Exception {
        respondWith(new AnalysisPlanDto(
                "OFFICE", List.of(), List.of(), "", "REPORT", List.of()));

        AnalysisPlanDto plan = classifier.classify("帮我起草一份周会通知");

        assertEquals("OFFICE", plan.primaryIntent());
        assertEquals("REPORT", plan.analysisType());
        assertTrue(plan.subTasks().isEmpty());
    }

    @Test
    void fallsBackToRulesWhenVllmFails() {
        expectRequest().andRespond(withServerError());

        AnalysisPlanDto plan = classifier.classify("秦皇岛8月份发电量是多少");

        assertEquals("DATA_QUERY", plan.primaryIntent());
        assertEquals(List.of("秦皇岛"), plan.orgInputs());
        assertEquals(List.of("发电量"), plan.metricInputs());
        assertEquals("FACT", plan.analysisType());
        assertEquals("8月份", plan.timeExpression());
    }

    @Test
    void recognizesCommonWasteProcessingAlias() {
        expectRequest().andRespond(withServerError());

        AnalysisPlanDto plan = classifier.classify("查询今年各项目公司垃圾处理量排名，展示前十名。");

        assertEquals("DATA_QUERY", plan.primaryIntent());
        assertEquals(List.of("垃圾处理量"), plan.metricInputs());
        assertEquals(List.of("项目公司"), plan.orgInputs());
        assertEquals("今年", plan.timeExpression());
        assertEquals("RANKING", plan.analysisType());
    }

    @Test
    void keepsSystemPromptWithin250CharactersAndInjectsControlledTerms() {
        String prompt = classifier.buildSystemPrompt("秦皇岛今年发电量排名");

        assertTrue(prompt.length() <= IntentClassifier.MAX_SYSTEM_PROMPT_CHARS);
        assertTrue(prompt.contains("发电量"));
        assertTrue(prompt.contains("秦皇岛"));
        assertTrue(prompt.contains("单行JSON"));
    }

    private void respondWith(AnalysisPlanDto plan) throws Exception {
        String content = objectMapper.writeValueAsString(plan);
        String response = objectMapper.writeValueAsString(Map.of(
                "choices", List.of(Map.of(
                        "message", Map.of("content", content)))));
        expectRequest().andExpect(jsonPath("$.temperature").value(0.1))
                .andExpect(jsonPath("$.max_tokens").value(150))
                .andExpect(jsonPath("$.model").value("Qwen3.8-27B"))
                .andExpect(jsonPath("$.messages[0].content", containsString("单行JSON")))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
    }

    private org.springframework.test.web.client.ResponseActions expectRequest() {
        return server.expect(requestTo("http://vllm.test/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST));
    }
}
