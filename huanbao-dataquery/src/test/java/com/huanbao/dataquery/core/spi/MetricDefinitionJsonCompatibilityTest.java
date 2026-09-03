package com.huanbao.dataquery.core.spi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricDefinitionJsonCompatibilityTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void deserializesEveryIndicatorObjectFromFirstPhaseDictionary() throws Exception {
        Path dictionaryPath = Path.of("..", "docs", "METRIC_SEMANTIC_DICTIONARY.json").normalize();
        JsonNode indicators = objectMapper.readTree(Files.readString(dictionaryPath)).path("indicators");

        assertEquals(46, indicators.size());
        for (JsonNode indicatorNode : indicators) {
            MetricDefinition metric = objectMapper.treeToValue(indicatorNode, MetricDefinition.class);

            assertEquals(indicatorNode.path("indicatorCode").asText(), metric.indicatorCode());
            assertEquals(indicatorNode.path("formalName").asText(), metric.formalName());
            assertEquals(indicatorNode.path("isNonAdditive").asBoolean(), metric.isNonAdditive());
            assertTrue(metric.matches(metric.indicatorCode()));
            assertTrue(metric.matches(metric.formalName()));
        }
    }
}
