<script setup>
import { computed } from 'vue'
import DataQueryChart from '../../DataQueryChart.vue'
import MetricGrid from '../shared/MetricGrid.vue'
import AnalysisTable from '../shared/AnalysisTable.vue'
import InsightList from '../shared/InsightList.vue'
import FollowUpActions from '../shared/FollowUpActions.vue'
import { createDataQueryChartOption } from '../../../utils/dataQueryProtocol.js'

const NO_DATA_STATUSES = new Set(['NO_DATA', 'NO_DATA_IN_PERIOD', 'SUCCESS_EMPTY', 'EMPTY'])
const DISCLAIMER = '⚠️ 免责声明：本系统仅呈现同期数据变动线索，具体生产因果判定需结合现场检修台账核实。'

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
const statusLabel = computed(() => (isNoData.value ? '暂无数据' : '线索诊断'))

const formatClue = (item) => {
  if (typeof item === 'string') return item.trim()
  if (!isPlainObject(item)) return String(item ?? '').trim()

  const label = String(item.label || item.name || item.indicatorName || '').trim()
  const value = item.value
  const unit = String(item.unit || '').trim()
  if (label && value !== undefined && value !== null && value !== '') {
    return `${label}：${String(value)}${unit}`
  }
  return String(item.text || item.description || label || '').trim()
}

const relatedMetricClues = computed(() => {
  const value = content.value.relatedMetrics
  return Array.isArray(value)
    ? value.map(formatClue).filter(Boolean).slice(0, 8)
    : []
})

const sourceClues = computed(() => [
  ...insights.value,
  ...evidence.value,
].map(formatClue).filter(Boolean).slice(0, 8))

const clues = computed(() => relatedMetricClues.value.length ? relatedMetricClues.value : sourceClues.value)
const hasIndependentRelatedMetrics = computed(() => relatedMetricClues.value.length > 0)
const chart = computed(() => {
  const protocolChart = createDataQueryChartOption({ content: content.value })
  return protocolChart || (isPlainObject(props.chartOption) ? props.chartOption : null)
})

const warnings = computed(() => {
  const value = dataInfo.value.warnings
  return Array.isArray(value) ? value.filter(Boolean).slice(0, 6) : []
})
</script>

<template>
  <article class="data-query-result data-query-analysis-card data-query-drilldown-view">
    <header class="data-query-analysis-header">
      <div>
        <span class="data-query-analysis-kicker">归因诊断</span>
        <h3>{{ content.title || `${indicatorName}归因诊断` }}</h3>
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
    <InsightList :insights="insights" :data-info="dataInfo" />

    <section class="data-query-drilldown-boundary" aria-label="归因诊断边界">
      <strong>同期变动线索</strong>
      <p>{{ indicatorName }}变动期间，同步观察到关联指标变动线索，建议结合现场排查。</p>
    </section>

    <section v-if="clues.length" class="data-query-drilldown-clues" aria-label="关联指标变动线索">
      <div class="data-query-drilldown-section-title">
        {{ hasIndependentRelatedMetrics ? '已返回的关联指标线索' : '从洞察与证据提取的线索' }}
      </div>
      <ul>
        <li v-for="(clue, index) in clues" :key="`${clue}-${index}`">{{ clue }}</li>
      </ul>
    </section>
    <section v-else class="data-query-drilldown-clues data-query-drilldown-empty" aria-label="关联指标变动线索">
      <div class="data-query-drilldown-section-title">暂无独立关联指标结构</div>
      <p>当前结果未返回 relatedMetrics，仅展示已知数据变动；如需归因，请结合现场记录继续核查。</p>
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

    <section v-if="warnings.length" class="data-query-evidence-card" aria-label="校验提醒">
      <div class="data-query-evidence-title">校验提醒</div>
      <ul class="data-query-warning-list">
        <li v-for="(warning, index) in warnings" :key="`${warning}-${index}`">{{ warning }}</li>
      </ul>
    </section>

    <section class="data-query-drilldown-directions" aria-label="现场排查方向">
      <div class="data-query-drilldown-section-title">建议现场排查方向</div>
      <ul>
        <li>结合相关时间段的现场检修台账核实是否存在设备或工艺事件。</li>
        <li>核对指标统计口径、数据截止日期与组织范围，确认同期可比。</li>
        <li>对照运行记录复核上述变动线索，不据此直接判定生产因果。</li>
      </ul>
    </section>

    <p class="data-query-drilldown-disclaimer">{{ DISCLAIMER }}</p>
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

.data-query-drilldown-boundary,
.data-query-drilldown-clues,
.data-query-drilldown-directions {
  margin-top: 10px;
  padding: 10px;
  border: 1px solid #e2edf7;
  border-radius: 10px;
  background: #f8fbff;
}

.data-query-drilldown-boundary {
  border-color: #bfdcf7;
  background: #f2f8ff;
}

.data-query-drilldown-boundary strong,
.data-query-drilldown-section-title {
  color: #294b6d;
  font-size: 11px;
  font-weight: 800;
}

.data-query-drilldown-boundary p,
.data-query-drilldown-empty p {
  margin: 5px 0 0;
  color: #536b82;
  font-size: 12px;
  line-height: 1.55;
}

.data-query-drilldown-clues ul,
.data-query-drilldown-directions ul {
  display: grid;
  gap: 5px;
  margin: 7px 0 0;
  padding-left: 17px;
  color: #536b82;
  font-size: 11px;
  line-height: 1.55;
}

.data-query-drilldown-empty {
  background: #fbfdff;
}

.data-query-drilldown-directions {
  background: #fff;
}

.data-query-drilldown-disclaimer {
  margin: 11px 0 0;
  padding: 9px 10px;
  border: 1px solid #f2d28c;
  border-radius: 9px;
  color: #674f24;
  background: #fffaf0;
  font-size: 11px;
  line-height: 1.55;
}
</style>
