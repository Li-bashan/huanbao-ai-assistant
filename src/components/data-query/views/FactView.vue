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
const primaryMetric = computed(() => metrics.value[0] || null)
const status = computed(() => String(props.status || '').trim().toUpperCase())
const isNoData = computed(() => NO_DATA_STATUSES.has(status.value))
const summary = computed(() => {
  const value = String(content.value.summary || '').trim()
  return value || (isNoData.value ? '当前统计期间暂无可用数据。' : '查询已完成。')
})
const statusLabel = computed(() => (isNoData.value ? '暂无数据' : '已校验'))
const chart = computed(() => {
  const protocolChart = createDataQueryChartOption({ content: content.value })
  return protocolChart || (isPlainObject(props.chartOption) ? props.chartOption : null)
})

const formatValue = (value) => {
  if (value === null || value === undefined || value === '') return '-'
  if (typeof value !== 'number' || !Number.isFinite(value)) return String(value)
  return value.toLocaleString('en-US', { minimumFractionDigits: 0, maximumFractionDigits: 4 })
}

const formatListItem = (item) => {
  if (typeof item === 'string') return item
  if (isPlainObject(item)) return item.text || item.label || item.description || ''
  return String(item ?? '')
}

const warnings = computed(() => {
  const value = dataInfo.value.warnings
  return Array.isArray(value) ? value.filter(Boolean).slice(0, 6) : []
})

const dataInfoLabels = {
  analysisType: '分析类型',
  indicatorName: '主指标',
  indicatorCode: '指标编码',
  unit: '单位',
  timeRange: '统计期间',
  dataCutoffDate: '数据截止',
  aggregation: '聚合口径',
  organizationScope: '组织范围',
  rowCount: '数据行数',
  statistics: '统计摘要',
  comparison: '对比口径',
  sourceTables: '来源表',
  warnings: '校验提醒',
  validation: '结果校验',
}
const dataInfoEntries = computed(() => Object.entries(dataInfo.value))
const dataInfoLabel = (key) => dataInfoLabels[key] || key
const formatDataInfoValue = (value) => {
  if (typeof value === 'object' && value !== null) {
    try {
      return JSON.stringify(value)
    } catch {
      return '[不可展示]'
    }
  }
  return String(value ?? '')
}
</script>

<template>
  <article class="data-query-result data-query-analysis-card data-query-fact-view">
    <header class="data-query-analysis-header">
      <div>
        <span class="data-query-analysis-kicker">事实</span>
        <h3>{{ content.title || dataInfo.indicatorName || '事实结果' }}</h3>
      </div>
      <span class="data-query-analysis-status">{{ statusLabel }}</span>
    </header>

    <p class="data-query-analysis-summary">{{ summary }}</p>
    <div v-if="dataInfo.indicatorName || dataInfo.dataCutoffDate" class="data-query-view-context">
      <span v-if="dataInfo.indicatorName">指标：{{ dataInfo.indicatorName }}</span>
      <span v-if="dataInfo.dataCutoffDate">数据截至：{{ dataInfo.dataCutoffDate }}</span>
    </div>

    <section v-if="primaryMetric" class="data-query-fact-primary" aria-label="核心事实">
      <span>{{ primaryMetric.label || dataInfo.indicatorName || '指标' }}</span>
      <strong>{{ formatValue(primaryMetric.value) }}<small v-if="primaryMetric.unit">{{ primaryMetric.unit }}</small></strong>
    </section>
    <MetricGrid v-if="metrics.length > 1" :metrics="metrics.slice(1)" />

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

    <InsightList :insights="insights" />
    <section v-if="evidence.length || warnings.length" class="data-query-evidence-card" aria-label="证据与校验">
      <div class="data-query-evidence-title">证据与校验</div>
      <ul v-if="evidence.length" class="data-query-evidence-list">
        <li v-for="(item, index) in evidence" :key="`${formatListItem(item)}-${index}`">{{ formatListItem(item) }}</li>
      </ul>
      <ul v-if="warnings.length" class="data-query-warning-list">
        <li v-for="(warning, index) in warnings" :key="`${formatListItem(warning)}-${index}`">{{ formatListItem(warning) }}</li>
      </ul>
    </section>

    <DataQueryChart v-if="chart" :option="chart" :window-view="windowView" />

    <details v-if="dataInfoEntries.length" class="data-query-data-info">
      <summary>数据说明</summary>
      <dl>
        <template v-for="([key, value]) in dataInfoEntries" :key="key">
          <dt>{{ dataInfoLabel(key) }}</dt>
          <dd>{{ formatDataInfoValue(value) }}</dd>
        </template>
      </dl>
    </details>

    <FollowUpActions :follow-ups="followUps" @select="emit('follow-up', $event)" />
  </article>
</template>

<style scoped>
.data-query-fact-primary {
  display: grid;
  gap: 5px;
  margin: 10px 0;
  padding: 15px 16px;
  border: 1px solid #bfdbfe;
  border-radius: 14px;
  background: linear-gradient(135deg, #f4f9ff, #ffffff);
}

.data-query-fact-primary span {
  color: #5f7f98;
  font-size: 11px;
  font-weight: 700;
}

.data-query-fact-primary strong {
  color: #176dcc;
  font-size: 28px;
  line-height: 1.2;
}

.data-query-fact-primary small {
  margin-left: 4px;
  color: #6d8195;
  font-size: 12px;
  font-weight: 600;
}

.data-query-view-context {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 12px;
  margin: -3px 0 9px;
  color: #6d8195;
  font-size: 11px;
}
</style>
