package com.huanbao.dataquery.core.state;

import java.util.Locale;
import java.util.Objects;

/**
 * 一次问数会话中可继承的结构化分析槽位。
 *
 * <p>状态对象本身不可变。状态机每次转移都生成新的快照，避免把正在被其他请求读取的
 * 会话状态原地改写。</p>
 */
public final class ConversationAnalysisState {

    public static final String GROUP = "GROUP";
    public static final String REGION = "REGION";
    public static final String PROJECT_COMPANY = "PROJECT_COMPANY";
    public static final String NONE = "NONE";
    public static final String YOY = "YOY";
    public static final String MOM = "MOM";
    public static final String RANKING = "RANKING";
    public static final String TREND = "TREND";
    public static final String FACT = "FACT";
    public static final String REPORT = "REPORT";

    private final String conversationId;
    private final String currentMetricCode;
    private final String currentMetricName;
    private final String timeExpression;
    private final String currentScopeType;
    private final String focusOrgCode;
    private final String focusOrgName;
    private final Integer topN;
    private final String comparisonType;
    private final String lastAnalysisType;

    public ConversationAnalysisState(
            String conversationId,
            String currentMetricCode,
            String currentMetricName,
            String timeExpression,
            String currentScopeType,
            String focusOrgCode,
            String focusOrgName,
            Integer topN,
            String comparisonType,
            String lastAnalysisType) {
        this.conversationId = requireText(conversationId, "conversationId");
        this.currentMetricCode = nullableText(currentMetricCode);
        this.currentMetricName = nullableText(currentMetricName);
        this.timeExpression = nullableText(timeExpression);
        this.currentScopeType = enumOrNull(currentScopeType, "currentScopeType",
                GROUP, REGION, PROJECT_COMPANY);
        this.focusOrgCode = nullableText(focusOrgCode);
        this.focusOrgName = nullableText(focusOrgName);
        if (topN != null && topN <= 0) {
            throw new IllegalArgumentException("topN must be positive");
        }
        this.topN = topN;
        this.comparisonType = enumOrDefault(comparisonType, NONE, "comparisonType", NONE, YOY, MOM);
        this.lastAnalysisType = enumOrNull(lastAnalysisType, "lastAnalysisType",
                RANKING, TREND, FACT, REPORT);
    }

    public static ConversationAnalysisState empty(String conversationId) {
        return new ConversationAnalysisState(
                conversationId,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                NONE,
                null);
    }

    public String conversationId() {
        return conversationId;
    }

    public String currentMetricCode() {
        return currentMetricCode;
    }

    public String currentMetricName() {
        return currentMetricName;
    }

    public String timeExpression() {
        return timeExpression;
    }

    public String currentScopeType() {
        return currentScopeType;
    }

    public String focusOrgCode() {
        return focusOrgCode;
    }

    public String focusOrgName() {
        return focusOrgName;
    }

    public Integer topN() {
        return topN;
    }

    public String comparisonType() {
        return comparisonType;
    }

    public String lastAnalysisType() {
        return lastAnalysisType;
    }

    // JavaBean 风格别名，方便 Spring/Jackson 编排层读取状态快照。
    public String getConversationId() {
        return conversationId;
    }

    public String getCurrentMetricCode() {
        return currentMetricCode;
    }

    public String getCurrentMetricName() {
        return currentMetricName;
    }

    public String getTimeExpression() {
        return timeExpression;
    }

    public String getCurrentScopeType() {
        return currentScopeType;
    }

    public String getFocusOrgCode() {
        return focusOrgCode;
    }

    public String getFocusOrgName() {
        return focusOrgName;
    }

    public Integer getTopN() {
        return topN;
    }

    public String getComparisonType() {
        return comparisonType;
    }

    public String getLastAnalysisType() {
        return lastAnalysisType;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ConversationAnalysisState that)) {
            return false;
        }
        return Objects.equals(conversationId, that.conversationId)
                && Objects.equals(currentMetricCode, that.currentMetricCode)
                && Objects.equals(currentMetricName, that.currentMetricName)
                && Objects.equals(timeExpression, that.timeExpression)
                && Objects.equals(currentScopeType, that.currentScopeType)
                && Objects.equals(focusOrgCode, that.focusOrgCode)
                && Objects.equals(focusOrgName, that.focusOrgName)
                && Objects.equals(topN, that.topN)
                && Objects.equals(comparisonType, that.comparisonType)
                && Objects.equals(lastAnalysisType, that.lastAnalysisType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                conversationId,
                currentMetricCode,
                currentMetricName,
                timeExpression,
                currentScopeType,
                focusOrgCode,
                focusOrgName,
                topN,
                comparisonType,
                lastAnalysisType);
    }

    @Override
    public String toString() {
        return "ConversationAnalysisState[conversationId=" + conversationId
                + ", currentMetricCode=" + currentMetricCode
                + ", currentMetricName=" + currentMetricName
                + ", timeExpression=" + timeExpression
                + ", currentScopeType=" + currentScopeType
                + ", focusOrgCode=" + focusOrgCode
                + ", focusOrgName=" + focusOrgName
                + ", topN=" + topN
                + ", comparisonType=" + comparisonType
                + ", lastAnalysisType=" + lastAnalysisType + "]";
    }

    private static String requireText(String value, String fieldName) {
        String normalized = nullableText(value);
        if (normalized == null) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    private static String nullableText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String enumOrNull(String value, String fieldName, String... allowed) {
        String normalized = nullableText(value);
        if (normalized == null) {
            return null;
        }
        normalized = normalized.toUpperCase(Locale.ROOT);
        for (String candidate : allowed) {
            if (candidate.equals(normalized)) {
                return normalized;
            }
        }
        throw new IllegalArgumentException(fieldName + " has unsupported value: " + value);
    }

    private static String enumOrDefault(
            String value,
            String defaultValue,
            String fieldName,
            String... allowed) {
        String normalized = enumOrNull(value, fieldName, allowed);
        return normalized == null ? defaultValue : normalized;
    }
}
