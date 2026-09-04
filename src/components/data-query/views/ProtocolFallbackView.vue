<script setup>
import { computed } from 'vue'
import { removeDataQueryChartPayload } from '../../../utils/dataQueryChart.js'
import { renderMarkdown } from '../../../utils/markdown.js'

const props = defineProps({
  rawText: { type: String, default: '' },
})

const FRIENDLY_FALLBACK_TEXT = '暂未获取到该维度的结构化分析数据，建议尝试按时间趋势或组织排名提问。'
const safeText = computed(() => {
  const text = removeDataQueryChartPayload(props.rawText).trim()
  return /(协议校验失败|PROTOCOL_VALIDATION_FAILED|protocol validation failed)/i.test(text)
    ? FRIENDLY_FALLBACK_TEXT
    : text || FRIENDLY_FALLBACK_TEXT
})
</script>

<template>
  <section class="data-query-result data-query-analysis-fallback" aria-live="polite">
    <div
      class="markdown-body markdown-content data-query-answer"
      v-html="renderMarkdown(safeText)"
    ></div>
  </section>
</template>
