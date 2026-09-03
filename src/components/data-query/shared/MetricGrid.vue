<script setup>
import { computed } from 'vue'

const props = defineProps({
  metrics: { type: Array, default: () => [] },
})

const normalizedMetrics = computed(() => (Array.isArray(props.metrics) ? props.metrics : []).filter((metric) => {
  return metric && typeof metric === 'object' && !Array.isArray(metric) && String(metric.label || '').trim()
}).slice(0, 12))

const formatValue = (value, metric) => {
  if (value === null || value === undefined || value === '') return '-'
  const isNumber = metric?.type === 'number' || typeof value === 'number'
  if (!isNumber || !Number.isFinite(Number(value))) return String(value)
  return Number(value).toLocaleString('en-US', {
    minimumFractionDigits: 0,
    maximumFractionDigits: 4,
  })
}
</script>

<template>
  <div v-if="normalizedMetrics.length" class="data-query-metrics data-query-metric-grid" aria-label="核心指标">
    <div
      v-for="(metric, index) in normalizedMetrics"
      :key="metric.id || `${metric.label}-${index}`"
      class="data-query-metric data-query-metric-pill"
    >
      <span>{{ metric.label }}</span>
      <strong>{{ formatValue(metric.value, metric) }}<small v-if="metric.unit">{{ metric.unit }}</small></strong>
    </div>
  </div>
</template>

<style scoped>
.data-query-metric-grid {
  grid-template-columns: repeat(auto-fit, minmax(120px, 1fr));
}

.data-query-metric-pill {
  border-radius: 999px;
}
</style>
