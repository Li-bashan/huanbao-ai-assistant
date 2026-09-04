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

const isTotalMetric = (metric, index) => index === 0 || /累计|合计/.test(String(metric?.label || ''))

const isChangeMetric = (metric) => /(同比|环比|变动|变化|涨跌)/.test(String(metric?.label || ''))

const getMetricTrend = (metric) => {
  if (!isChangeMetric(metric)) return 'flat'
  const value = Number(metric?.value)
  if (!Number.isFinite(value) || value === 0) return 'flat'
  return value > 0 ? 'up' : 'down'
}

const formatMetricValue = (metric) => {
  const formatted = formatValue(metric?.value, metric)
  const value = Number(metric?.value)
  if (isChangeMetric(metric) && Number.isFinite(value) && value > 0) return `+${formatted}`
  return formatted
}
</script>

<template>
  <div v-if="normalizedMetrics.length" class="data-query-metrics data-query-metric-grid" aria-label="核心指标">
    <div
      v-for="(metric, index) in normalizedMetrics"
      :key="metric.id || `${metric.label}-${index}`"
      class="data-query-metric data-query-metric-card"
      :class="{
        'is-total': isTotalMetric(metric, index),
        'is-up': getMetricTrend(metric) === 'up',
        'is-down': getMetricTrend(metric) === 'down',
      }"
    >
      <span>{{ metric.label }}</span>
      <strong>
        <span v-if="getMetricTrend(metric) === 'up'" class="data-query-metric-trend" aria-hidden="true">▲</span>
        <span v-else-if="getMetricTrend(metric) === 'down'" class="data-query-metric-trend" aria-hidden="true">▼</span>
        <span class="data-query-metric-value">{{ formatMetricValue(metric) }}</span>
        <small v-if="metric.unit">{{ metric.unit }}</small>
      </strong>
    </div>
  </div>
</template>

<style scoped>
.data-query-metric-grid {
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
}

.data-query-metric-card {
  padding: 8px 12px;
  border-color: #e2e8f0;
  border-radius: 6px;
  background: #f8fafc;
}

.data-query-metric-card.is-total {
  border-color: #b9dcf8;
  background: #eaf4ff;
}

.data-query-metric-card strong {
  display: flex;
  align-items: baseline;
  gap: 3px;
  color: #0f172a;
  font-size: 17px;
  font-weight: 800;
}

.data-query-metric-value {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.data-query-metric-trend {
  flex: 0 0 auto;
  font-size: 10px;
}

.is-up .data-query-metric-trend,
.is-up .data-query-metric-value {
  color: #d92d20;
}

.is-down .data-query-metric-trend,
.is-down .data-query-metric-value {
  color: #12876f;
}

@media (max-width: 520px) {
  .data-query-metric-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
