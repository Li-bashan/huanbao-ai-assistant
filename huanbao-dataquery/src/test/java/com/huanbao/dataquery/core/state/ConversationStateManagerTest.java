package com.huanbao.dataquery.core.state;

import com.huanbao.dataquery.domain.ops.OpsDomainSemanticProvider;
import com.huanbao.dataquery.router.AnalysisPlanDto;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ConversationStateManagerTest {

    private static final String CONVERSATION_KEY = "tenant-a:user-7:conversation-1";

    private final OpsDomainSemanticProvider provider = new OpsDomainSemanticProvider();
    private final ConversationStateManager manager = new ConversationStateManager(provider);

    @Test
    void carriesContextAcrossFiveFollowUpRounds() {
        AnalysisPlanDto round1 = apply(
                new AnalysisPlanDto(
                        "DATA_QUERY", List.of("发电量"), List.of("项目公司"), "今年", "RANKING", List.of()),
                "今年项目公司发电量排名");
        ConversationAnalysisState state1 = state();
        logSnapshot(1, round1, state1);
        assertEquals("RANKING", round1.analysisType());
        assertEquals(List.of("发电量"), round1.metricInputs());
        assertEquals("1001", state1.currentMetricCode());
        assertEquals("发电量", state1.currentMetricName());
        assertEquals("今年", state1.timeExpression());
        assertEquals("GROUP", state1.currentScopeType());
        assertNull(state1.focusOrgCode());
        assertNull(state1.topN());

        AnalysisPlanDto round2 = apply(
                new AnalysisPlanDto("DATA_QUERY", List.of(), List.of(), "", "FACT", List.of()),
                "只看前5");
        ConversationAnalysisState state2 = state();
        logSnapshot(2, round2, state2);
        assertEquals("RANKING", round2.analysisType());
        assertEquals(List.of("发电量"), round2.metricInputs());
        assertEquals("1001", state2.currentMetricCode());
        assertEquals("今年", state2.timeExpression());
        assertEquals(5, state2.topN());
        assertNull(state2.focusOrgCode());

        AnalysisPlanDto round3 = apply(
                new AnalysisPlanDto(
                        "DATA_QUERY", List.of(), List.of("秦皇岛"), "", "FACT", List.of()),
                "秦皇岛呢");
        ConversationAnalysisState state3 = state();
        logSnapshot(3, round3, state3);
        assertEquals("FACT", round3.analysisType());
        assertEquals(List.of("发电量"), round3.metricInputs());
        assertEquals(List.of("秦皇岛公司"), round3.orgInputs());
        assertEquals("PROJECT_COMPANY", state3.currentScopeType());
        assertEquals("10004024", state3.focusOrgCode());
        assertEquals("秦皇岛公司", state3.focusOrgName());
        assertNull(state3.topN());

        AnalysisPlanDto round4 = apply(
                new AnalysisPlanDto(
                        "DATA_QUERY", List.of(), List.of(), "去年", "FACT", List.of()),
                "跟去年比呢");
        ConversationAnalysisState state4 = state();
        logSnapshot(4, round4, state4);
        assertEquals("FACT", round4.analysisType());
        assertEquals("今年", state4.timeExpression());
        assertEquals("10004024", state4.focusOrgCode());
        assertEquals("秦皇岛公司", state4.focusOrgName());
        assertEquals("YOY", state4.comparisonType());

        AnalysisPlanDto round5 = apply(
                new AnalysisPlanDto(
                        "DATA_QUERY", List.of(), List.of(), "", "FACT", List.of()),
                "看看垃圾量");
        ConversationAnalysisState state5 = state();
        logSnapshot(5, round5, state5);
        assertEquals(List.of("生活垃圾入厂量"), round5.metricInputs());
        assertEquals("1201", state5.currentMetricCode());
        assertEquals("生活垃圾入厂量", state5.currentMetricName());
        assertEquals("今年", state5.timeExpression());
        assertEquals("10004024", state5.focusOrgCode());
        assertEquals("秦皇岛公司", state5.focusOrgName());
        assertEquals("YOY", state5.comparisonType());
    }

    @Test
    void clearsCompanyFocusWhenACompanyContextTurnsIntoRanking() {
        apply(
                new AnalysisPlanDto(
                        "DATA_QUERY", List.of("发电量"), List.of("秦皇岛"), "今年", "FACT", List.of()),
                "秦皇岛今年发电量");
        assertEquals("10004024", state().focusOrgCode());

        AnalysisPlanDto ranking = apply(
                new AnalysisPlanDto("DATA_QUERY", List.of(), List.of(), "", "FACT", List.of()),
                "电厂排名");
        ConversationAnalysisState state = state();
        logSnapshot(6, ranking, state);
        assertEquals("RANKING", ranking.analysisType());
        assertNull(state.focusOrgCode());
        assertNull(state.focusOrgName());
        assertEquals("GROUP", state.currentScopeType());
        assertEquals("1001", state.currentMetricCode());
    }

    @Test
    void expiresIdleStateAfterThirtyMinutesAndRefreshesOnRead() {
        MutableClock clock = new MutableClock();
        ConversationStateManager expiringManager = new ConversationStateManager(provider, clock);
        expiringManager.applyState(
                CONVERSATION_KEY,
                new AnalysisPlanDto(
                        "DATA_QUERY", List.of("发电量"), List.of("秦皇岛"), "今年", "FACT", List.of()),
                "秦皇岛今年发电量");

        clock.advance(Duration.ofMinutes(29));
        assertNotNull(expiringManager.getState(CONVERSATION_KEY));
        clock.advance(Duration.ofMinutes(2));
        assertNotNull(expiringManager.getState(CONVERSATION_KEY));
        clock.advance(Duration.ofMinutes(30));
        assertNull(expiringManager.getState(CONVERSATION_KEY));
    }

    @Test
    void serializesConcurrentTransitionsPerConversationKey() throws Exception {
        ConversationStateManager concurrentManager = new ConversationStateManager(provider);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> workers = java.util.stream.IntStream.rangeClosed(1, 20)
                .mapToObj(number -> new Thread(() -> {
                    try {
                        concurrentManager.applyState(
                                CONVERSATION_KEY,
                                new AnalysisPlanDto(
                                        "DATA_QUERY", List.of("发电量"), List.of(), "今年", "RANKING", List.of()),
                                "今年项目公司发电量前" + number);
                    } catch (Throwable throwable) {
                        failure.compareAndSet(null, throwable);
                    }
                }))
                .toList();
        workers.forEach(Thread::start);
        for (Thread worker : workers) {
            worker.join();
        }

        assertNull(failure.get());
        assertEquals("1001", concurrentManager.getState(CONVERSATION_KEY).currentMetricCode());
        assertEquals("GROUP", concurrentManager.getState(CONVERSATION_KEY).currentScopeType());
    }

    private AnalysisPlanDto apply(AnalysisPlanDto plan, String query) {
        return manager.applyState(CONVERSATION_KEY, plan, query);
    }

    private ConversationAnalysisState state() {
        ConversationAnalysisState state = manager.getState(CONVERSATION_KEY);
        assertNotNull(state);
        return state;
    }

    private void logSnapshot(int round, AnalysisPlanDto plan, ConversationAnalysisState state) {
        System.out.printf("round %d plan=%s state=%s%n", round, plan, state);
    }

    private static final class MutableClock extends Clock {

        private Instant current = Instant.parse("2026-09-02T00:00:00Z");

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }

        private void advance(Duration duration) {
            current = current.plus(duration);
        }
    }
}
