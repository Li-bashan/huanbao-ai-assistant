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
const attentionInsights = computed(() => insights.value.filter((item) => item?.type === 'attention'))
const standardInsights = computed(() => insights.value.filter((item) => item?.type !== 'attention'))
const evidence = computed(() => (Array.isArray(content.value.evidence) ? content.value.evidence : []))
const followUps = computed(() => (Array.isArray(content.value.followUps) ? content.value.followUps : []))
const status = computed(() => String(props.status || '').trim().toUpperCase())
const isNoData = computed(() => NO_DATA_STATUSES.has(status.value))
const indicatorName = computed(() => String(
  props.meta?.indicatorName ||
  dataInfo.value.indicatorName ||
  '全厂发电量',
).trim() || '全厂发电量')
const organizationName = computed(() => {
  const metaName = String(props.meta?.organizationName || '').trim()
  if (metaName) return metaName
  const scope = dataInfo.value.organizationScope
  if (Array.isArray(scope?.names)) {
    return scope.names.filter(Boolean).slice(0, 2).join('、')
  }
  return String(scope || '').trim()
})
const title = computed(() => content.value.title || `${organizationName.value ? `${organizationName.value} · ` : ''}${indicatorName.value}走势`)
const periodLabel = computed(() => {
  const metaLabel = String(props.meta?.periodLabel || '').trim()
  if (metaLabel) return metaLabel

  const timeRange = dataInfo.value.timeRange
  return isPlainObject(timeRange) ? String(timeRange.expression || timeRange.label || '').trim() : ''
})
const insightTitle = computed(() => `${periodLabel.value || '近半年'}${indicatorName.value}数据要点`)
const summary = computed(() => {
  const value = String(content.value.summary || '').trim()
  return value || (isNoData.value ? '当前统计期间暂无可用数据。' : '查询已完成。')
})
const summaryText = computed(() => {
  const cutoff = String(dataInfo.value.dataCutoffDate || '').trim()
  return cutoff ? `${summary.value} 当前数据更新至 ${cutoff}` : summary.value
})
const statusLabel = computed(() => (isNoData.value ? '暂无数据' : '已校验'))
const chart = computed(() => {
  const protocolChart = createDataQueryChartOption({ content: content.value })
  return protocolChart || (isPlainObject(props.chartOption) ? props.chartOption : null)
})
const chartTitle = computed(() => `${indicatorName.value}${periodLabel.value}走势`)
const chartSubtitle = computed(() => {
  const timeRange = dataInfo.value.timeRange
  if (!isPlainObject(timeRange)) return ''

  const start = String(timeRange.start || '').trim()
  const end = String(timeRange.end || '').trim()
  return start && end ? `(${start} 至 ${end})` : ''
})
const chartUnit = computed(() => String(dataInfo.value.unit || '').trim())

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
  <article class="data-query-result data-query-analysis-card data-query-trend-view">
    <header class="data-query-analysis-header">
      <div>
        <span class="data-query-analysis-kicker">趋势</span>
        <h3>{{ title }}</h3>
      </div>
      <span class="data-query-analysis-status">{{ statusLabel }}</span>
    </header>

    <p class="data-query-analysis-summary">{{ summaryText }}</p>

    <MetricGrid :metrics="metrics" />
    <DataQueryChart
      v-if="chart"
      :option="chart"
      :title="chartTitle"
      :subtitle="chartSubtitle"
      :unit="chartUnit"
      :window-view="windowView"
    />
    <InsightList :insights="standardInsights" :data-info="dataInfo" :title="insightTitle" />

    <section v-if="attentionInsights.length" class="data-query-attention-card" aria-label="值得关注">
      <div class="data-query-attention-title">⚠ 值得关注</div>
      <ul>
        <li v-for="(insight, index) in attentionInsights" :key="`${insight.text}-${index}`">
          {{ formatListItem(insight) }}
        </li>
      </ul>
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
.data-query-attention-card {
  margin-top: 10px;
  padding: 9px 10px;
  border: 1px solid #f1d49c;
  border-radius: 9px;
  background: #fffaf0;
  color: #8a5a12;
  font-size: 11px;
  line-height: 1.55;
}

.data-query-attention-title {
  font-weight: 800;
}

.data-query-attention-card ul {
  display: grid;
  gap: 4px;
  margin: 5px 0 0;
  padding-left: 17px;
}
</style>
