<script setup>
import { computed, defineComponent, h } from 'vue'
import DataQueryChart from '../../DataQueryChart.vue'
import MetricGrid from '../shared/MetricGrid.vue'
import AnalysisTable from '../shared/AnalysisTable.vue'
import InsightList from '../shared/InsightList.vue'
import FollowUpActions from '../shared/FollowUpActions.vue'
import { createDataQueryChartOption } from '../../../utils/dataQueryProtocol.js'
import { renderMarkdown } from '../../../utils/markdown.js'

const NO_DATA_STATUSES = new Set(['NO_DATA', 'NO_DATA_IN_PERIOD', 'SUCCESS_EMPTY', 'EMPTY'])

const isPlainObject = (value) =>
  value !== null && typeof value === 'object' && !Array.isArray(value)

const readText = (...values) => values
  .find((value) => typeof value === 'string' && value.trim())
  ?.trim() || ''

const OverviewSection = defineComponent({
  name: 'OverviewSection',
  props: {
    section: { type: Object, default: () => ({}) },
    windowView: { type: String, default: 'compact' },
    level: { type: Number, default: 0 },
  },
  setup(sectionProps) {
    const section = computed(() => (isPlainObject(sectionProps.section) ? sectionProps.section : {}))
    const children = computed(() => {
      const value = section.value
      const nested = Array.isArray(value.sections)
        ? value.sections
        : Array.isArray(value.children)
          ? value.children
          : []
      return nested.filter(isPlainObject)
    })

    return () => {
      const value = section.value
      const title = readText(value.title, value.heading)
      const summary = readText(
        value.summary,
        typeof value.content === 'string' ? value.content : '',
        value.text,
        value.description,
      )
      const markdown = readText(value.documentMarkdown, value.markdown)
      const nodes = []

      if (title) nodes.push(h('h4', { class: 'data-query-overview-section-title' }, title))
      if (summary) nodes.push(h('p', { class: 'data-query-overview-section-summary' }, summary))
      if (markdown) {
        nodes.push(h('div', {
          class: 'data-query-overview-section-markdown data-query-markdown-content',
          innerHTML: renderMarkdown(markdown),
        }))
      }
      if (Array.isArray(value.metrics)) nodes.push(h(MetricGrid, { metrics: value.metrics }))
      if (isPlainObject(value.table)) {
        nodes.push(h(AnalysisTable, { table: value.table, windowView: sectionProps.windowView }))
      }
      children.value.forEach((child, index) => {
        nodes.push(h(OverviewSection, {
          key: child.id || `${title || 'section'}-${index}`,
          section: child,
          windowView: sectionProps.windowView,
          level: sectionProps.level + 1,
        }))
      })

      return h('section', {
        class: 'data-query-overview-section',
        style: { '--overview-section-level': sectionProps.level },
      }, nodes)
    }
  },
})

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
const sections = computed(() => (Array.isArray(content.value.sections)
  ? content.value.sections.filter(isPlainObject)
  : []))
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
  const value = readText(content.value.summary, content.value.documentMarkdown)
  return value || (isNoData.value ? '当前统计期间暂无可用数据。' : '查询已完成。')
})
const documentMarkdown = computed(() => readText(content.value.documentMarkdown))
const hasSeparateMarkdown = computed(() => documentMarkdown.value && documentMarkdown.value !== summary.value)
const statusLabel = computed(() => (isNoData.value ? '暂无数据' : '已校验'))
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
  <article class="data-query-result data-query-analysis-card data-query-overview-view">
    <header class="data-query-analysis-header">
      <div>
        <span class="data-query-analysis-kicker">总览分析</span>
        <h3>{{ content.title || `${indicatorName}总览分析` }}</h3>
      </div>
      <span class="data-query-analysis-status">{{ statusLabel }}</span>
    </header>

    <p class="data-query-analysis-summary">{{ summary }}</p>
    <div v-if="indicatorName || timeRange || dataInfo.dataCutoffDate" class="data-query-view-context">
      <span v-if="indicatorName">指标：{{ indicatorName }}</span>
      <span v-if="timeRange">统计期间：{{ timeRange }}</span>
      <span v-if="dataInfo.dataCutoffDate">数据截至：{{ dataInfo.dataCutoffDate }}</span>
    </div>

    <template v-if="sections.length">
      <section class="data-query-overview-sections" aria-label="总览分析分段">
        <OverviewSection
          v-for="(section, index) in sections"
          :key="section.id || section.title || index"
          :section="section"
          :window-view="windowView"
        />
      </section>
    </template>
    <template v-else>
      <section class="data-query-overview-fallback" aria-label="总览分析降级内容">
        <div class="data-query-overview-fallback-title">当前可用分析内容</div>
        <div
          v-if="hasSeparateMarkdown"
          class="data-query-overview-markdown data-query-markdown-content"
          v-html="renderMarkdown(documentMarkdown)"
        ></div>
        <p v-else class="data-query-overview-fallback-note">
          当前协议未返回 content.sections[]，已按现有 summary、指标和明细结果展示。
        </p>
      </section>
    </template>

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

    <MetricGrid :metrics="metrics" />
    <DataQueryChart v-if="chart" :option="chart" :window-view="windowView" />
    <AnalysisTable v-if="table" :table="table" :window-view="windowView" />
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

.data-query-overview-fallback,
.data-query-overview-sections {
  margin-top: 10px;
}

.data-query-overview-fallback {
  padding: 10px;
  border: 1px solid #e2edf7;
  border-radius: 10px;
  background: #f8fbff;
}

.data-query-overview-fallback-title {
  color: #294b6d;
  font-size: 11px;
  font-weight: 800;
}

.data-query-overview-fallback-note {
  margin: 5px 0 0;
  color: #6d8195;
  font-size: 11px;
  line-height: 1.55;
}

.data-query-overview-markdown {
  margin-top: 7px;
  color: #536b82;
  font-size: 12px;
  line-height: 1.65;
}

.data-query-overview-section {
  margin-top: 8px;
  padding: 10px;
  border: 1px solid #e2edf7;
  border-left: 3px solid #bfdbfe;
  border-radius: 10px;
  background: #fff;
  box-shadow: inset calc(var(--overview-section-level) * 8px) 0 0 rgba(239, 246, 255, 0.8);
}

.data-query-overview-section-title {
  margin: 0;
  color: #294b6d;
  font-size: 12px;
  font-weight: 800;
}

.data-query-overview-section-summary {
  margin: 5px 0 0;
  color: #536b82;
  font-size: 11px;
  line-height: 1.55;
}

.data-query-overview-section-markdown {
  margin-top: 6px;
  color: #536b82;
  font-size: 11px;
  line-height: 1.55;
}

.data-query-overview-section :deep(.data-query-metrics),
.data-query-overview-section :deep(.data-query-ranking-card) {
  margin-top: 8px;
}
</style>
