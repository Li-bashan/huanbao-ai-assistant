<script setup>
import { computed } from 'vue'
import { removeDataQueryChartPayload } from '../../../utils/dataQueryChart.js'
import { renderMarkdown } from '../../../utils/markdown.js'

const props = defineProps({
  rawText: { type: String, default: '' },
})

const safeText = computed(() => removeDataQueryChartPayload(props.rawText).trim())
</script>

<template>
  <section class="data-query-result data-query-analysis-fallback" aria-live="polite">
    <div
      v-if="safeText"
      class="markdown-body markdown-content data-query-answer"
      v-html="renderMarkdown(safeText)"
    ></div>
    <p v-else>暂时没有可展示的问数结果，请稍后重试。</p>
  </section>
</template>
