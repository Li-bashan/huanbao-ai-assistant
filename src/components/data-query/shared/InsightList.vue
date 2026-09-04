<script setup>
import { computed } from 'vue'

const props = defineProps({
  insights: { type: Array, default: () => [] },
  dataInfo: { type: Object, default: () => ({}) },
})

const dataInfo = computed(() => (props.dataInfo && typeof props.dataInfo === 'object' ? props.dataInfo : {}))

const insightSubtitle = computed(() => {
  const range = dataInfo.value.timeRange
  const timeRange = range && typeof range === 'object'
    ? String(range.expression || range.label || '').trim()
    : String(range || '').trim()
  const indicatorName = String(dataInfo.value.indicatorName || '').trim()
  const context = [timeRange, indicatorName].filter(Boolean).join(' · ')
  return context ? `${context}数据要点` : '本次结果数据要点'
})

const normalizedInsights = computed(() => (Array.isArray(props.insights) ? props.insights : []).slice(0, 8).map((insight) => {
  if (typeof insight === 'string') return { type: 'fact', text: insight.trim() }
  if (!insight || typeof insight !== 'object' || Array.isArray(insight)) return null
  return {
    type: String(insight.type || 'fact'),
    text: String(insight.text || insight.label || insight.description || '').trim(),
  }
}).filter((insight) => insight?.text))
</script>

<template>
  <section v-if="normalizedInsights.length" class="data-query-insight-section" aria-label="数据要点">
    <div class="data-query-insight-heading">
      <strong>数据要点</strong>
      <span class="data-query-insight-subtitle">{{ insightSubtitle }}</span>
    </div>
    <ul class="data-query-insight-list" aria-label="关键发现">
      <li v-for="(insight, index) in normalizedInsights" :key="`${insight.type}-${insight.text}-${index}`">
        <span class="data-query-insight-mark">{{ insight.type === 'attention' ? '!' : '·' }}</span>
        <span>{{ insight.text }}</span>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.data-query-insight-section {
  margin-top: 11px;
}

.data-query-insight-heading {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 5px 8px;
  color: #294b6d;
}

.data-query-insight-heading strong {
  font-size: 12px;
}

.data-query-insight-subtitle {
  color: #6d8195;
  font-size: 10px;
}
</style>
