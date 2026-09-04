<script setup>
import { computed } from 'vue'
import DataQueryChart from '../../DataQueryChart.vue'
import MetricGrid from '../shared/MetricGrid.vue'
import AnalysisTable from '../shared/AnalysisTable.vue'
import InsightList from '../shared/InsightList.vue'
import FollowUpActions from '../shared/FollowUpActions.vue'
import { createDataQueryChartOption } from '../../../utils/dataQueryProtocol.js'

const NO_DATA_STATUSES = new Set(['NO_DATA', 'NO_DATA_IN_PERIOD', 'SUCCESS_EMPTY', 'EMPTY'])

const isPlainObject = (value) =>
  value !== null && typeof value === 'object' && !Array.isArray(value)

const props = defineProps({
  content: { type: Object, default: () => ({}) },
  dataInfo: { type: Object, default: () => ({}) },
  meta: { type: Object, default: () => ({}) },
  status: { type: String, default: '' },
  messageType: { type: String, default: '' },
  clarification: { type: Object, default: null },
  chartOption: { type: Object, default: null },
  windowView: { type: String, default: 'compact' },
})

const emit = defineEmits(['follow-up', 'clarification'])

const content = computed(() => (isPlainObject(props.content) ? props.content : {}))
const dataInfo = computed(() => (isPlainObject(props.dataInfo) ? props.dataInfo : {}))
const metrics = computed(() => (Array.isArray(content.value.metrics) ? content.value.metrics : []))
const table = computed(() => (isPlainObject(content.value.table) ? content.value.table : null))
const insights = computed(() => (Array.isArray(content.value.insights) ? content.value.insights : []))
const evidence = computed(() => (Array.isArray(content.value.evidence) ? content.value.evidence : []))
const followUps = computed(() => (Array.isArray(content.value.followUps) ? content.value.followUps : []))
const status = computed(() => String(props.status || '').trim().toUpperCase())
const isNoData = computed(() => NO_DATA_STATUSES.has(status.value))
const indicatorName = computed(() => String(dataInfo.value.indicatorName || '指标').trim() || '指标')
const timeRange = computed(() => {
  const value = dataInfo.value.timeRange
  if (isPlainObject(value)) return String(value.expression || value.label || '').trim()
  return String(value || '').trim()
})
const summary = computed(() => {
  const value = String(content.value.summary || '').trim()
  return value || (isNoData.value ? '当前统计期间暂无可用数据。' : '查询已完成。')
})
const statusLabel = computed(() => (isNoData.value ? '暂无数据' : '需核查'))

const normalizeInsight = (item) => {
  if (typeof item === 'string') {
    const text = item.trim()
    return text ? { type: 'fact', text } : null
  }
  if (!isPlainObject(item)) return null
  const text = String(item.text || item.label || item.description || '').trim()
  return text ? { type: String(item.type || 'fact').trim().toLowerCase(), text } : null
}

const normalizedInsights = computed(() => insights.value.map(normalizeInsight).filter(Boolean))
const attentionInsights = computed(() => normalizedInsights.value.filter((item) => item.type === 'attention'))
const otherInsights = computed(() => normalizedInsights.value.filter((item) => item.type !== 'attention'))
const chart = computed(() => {
  const protocolChart = createDataQueryChartOption({ content: content.value })
  return protocolChart || (isPlainObject(props.chartOption) ? props.chartOption : null)
})

const formatListItem = (item) => {
  if (typeof item === 'string') return item.trim()
  if (isPlainObject(item)) return String(item.text || item.label || item.description || '').trim()
  return String(item ?? '').trim()
}

const warnings = computed(() => {
  const value = dataInfo.value.warnings
  return Array.isArray(value) ? value.filter(Boolean).slice(0, 6) : []
})
</script>

<template>
  <article class="data-query-result data-query-analysis-card data-query-anomaly-view">
    <header class="data-query-analysis-header">
      <div>
        <span class="data-query-analysis-kicker">异常关注</span>
        <h3>{{ content.title || `${indicatorName}异常关注` }}</h3>
      </div>
      <span class="data-query-analysis-status">{{ statusLabel }}</span>
    </header>

    <p class="data-query-analysis-summary">{{ summary }}</p>
    <div v-if="indicatorName || timeRange || dataInfo.dataCutoffDate" class="data-query-view-context">
      <span v-if="indicatorName">指标：{{ indicatorName }}</span>
      <span v-if="timeRange">统计期间：{{ timeRange }}</span>
      <span v-if="dataInfo.dataCutoffDate">数据截至：{{ dataInfo.dataCutoffDate }}</span>
    </div>

    <MetricGrid :metrics="metrics" />
    <DataQueryChart v-if="chart" :option="chart" :window-view="windowView" />
    <InsightList :insights="otherInsights" :data-info="dataInfo" />

    <section v-if="attentionInsights.length" class="data-query-anomaly-attention" aria-label="关注信息与指标偏离提示">
      <div class="data-query-anomaly-section-title">关注信息与指标偏离提示</div>
      <ul>
        <li v-for="(insight, index) in attentionInsights" :key="`${insight.text}-${index}`">
          <span aria-hidden="true">!</span>
          <p>{{ insight.text }}</p>
        </li>
      </ul>
    </section>

    <section class="data-query-anomaly-check-card" aria-label="异常核查提示">
      <strong>建议进一步核查</strong>
      <p>
        当前协议未提供独立异常点、阈值或异常序列，以上内容仅作为同期数据关注线索。建议核对原始数据、统计口径与现场记录。
      </p>
    </section>

    <AnalysisTable v-if="table" :table="table" :window-view="windowView" />

    <section
      v-if="messageType === 'clarification' && clarification?.candidates?.length"
      class="data-query-clarification-card"
      aria-label="请选择候选项"
    >
      <strong>{{ clarification.title || '请选择一个候选项' }}</strong>
      <button
        v-for="candidate in clarification.candidates"
        :key="candidate.id || candidate.label"
        type="button"
        class="data-query-clarification-option"
        @click="emit('clarification', candidate)"
      >
        <span>{{ candidate.label }}</span>
        <small v-if="candidate.description">{{ candidate.description }}</small>
      </button>
    </section>

    <section v-if="evidence.length || warnings.length" class="data-query-evidence-card" aria-label="证据与校验">
      <div class="data-query-evidence-title">证据与校验</div>
      <ul v-if="evidence.length" class="data-query-evidence-list">
        <li v-for="(item, index) in evidence" :key="`${formatListItem(item)}-${index}`">{{ formatListItem(item) }}</li>
      </ul>
      <ul v-if="warnings.length" class="data-query-warning-list">
        <li v-for="(warning, index) in warnings" :key="`${formatListItem(warning)}-${index}`">{{ formatListItem(warning) }}</li>
      </ul>
    </section>

    <FollowUpActions :follow-ups="followUps" @select="emit('follow-up', $event)" />
  </article>
</template>

<style scoped>
.data-query-view-context {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 12px;
  margin: -3px 0 9px;
  color: #6d8195;
  font-size: 11px;
}

.data-query-anomaly-attention,
.data-query-anomaly-check-card {
  margin-top: 10px;
  padding: 10px;
  border-radius: 10px;
}

.data-query-anomaly-attention {
  border: 1px solid #f2d28c;
  background: #fffaf0;
}

.data-query-anomaly-section-title,
.data-query-anomaly-check-card strong {
  color: #865f13;
  font-size: 11px;
  font-weight: 800;
}

.data-query-anomaly-attention ul {
  display: grid;
  gap: 7px;
  margin: 8px 0 0;
  padding: 0;
  list-style: none;
}

.data-query-anomaly-attention li {
  display: flex;
  gap: 7px;
  align-items: flex-start;
  color: #674f24;
  font-size: 12px;
  line-height: 1.55;
}

.data-query-anomaly-attention li > span {
  display: inline-grid;
  flex: 0 0 16px;
  place-items: center;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  color: #fff;
  background: #d2941b;
  font-size: 10px;
  font-weight: 800;
}

.data-query-anomaly-attention p,
.data-query-anomaly-check-card p {
  margin: 0;
}

.data-query-anomaly-check-card {
  border: 1px solid #e2edf7;
  background: #f8fbff;
}

.data-query-anomaly-check-card strong {
  color: #294b6d;
}

.data-query-anomaly-check-card p {
  margin-top: 5px;
  color: #6d8195;
  font-size: 11px;
  line-height: 1.55;
}
</style>
