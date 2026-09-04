<script setup>
import { ChevronDown, Download, FileText, Maximize2, Minimize2 } from '@lucide/vue'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import AccessBlockedView from './data-query/views/AccessBlockedView.vue'
import LoadingSkeleton from './data-query/views/LoadingSkeleton.vue'
import ProtocolFallbackView from './data-query/views/ProtocolFallbackView.vue'
import GenericAnalysisView from './data-query/views/GenericAnalysisView.vue'
import FactView from './data-query/views/FactView.vue'
import TrendView from './data-query/views/TrendView.vue'
import RankingView from './data-query/views/RankingView.vue'
import ComparisonView from './data-query/views/ComparisonView.vue'
import AnomalyView from './data-query/views/AnomalyView.vue'
import DrilldownView from './data-query/views/DrilldownView.vue'
import OverviewView from './data-query/views/OverviewView.vue'

const EMPTY_STATE_TEXT = '当前统计期间暂无可用数据。'
const FRIENDLY_FALLBACK_TEXT = '暂未获取到该维度的结构化分析数据，建议尝试按时间趋势或组织排名提问。'
const NO_DATA_STATUSES = new Set([
  'NO_DATA',
  'NO_DATA_IN_PERIOD',
  'SUCCESS_EMPTY',
  'EMPTY',
])
const ACCESS_DENIED_STATUSES = new Set([
  'ACCESS_DENIED',
  'DATA_QUERY_NOT_COVERED',
  'NO_ACCESS',
  'IDENTITY_UNVERIFIED',
])

const isPlainObject = (value) =>
  value !== null && typeof value === 'object' && !Array.isArray(value)

const readText = (value) => String(value ?? '').trim()

const props = defineProps({
  // `data` is the canonical v2 input. `protocol` remains for the current App.vue
  // message shape until that caller is migrated to the canonical prop name.
  data: { type: Object, default: null },
  answer: { type: String, default: '' },
  protocol: { type: Object, default: null },
  chartOption: { type: Object, default: null },
  loading: { type: Boolean, default: false },
  accessDenied: { type: Boolean, default: false },
  accessStatus: { type: String, default: '' },
  accessDeniedReason: { type: String, default: '' },
  windowView: { type: String, default: 'compact' },
})

const emit = defineEmits(['follow-up', 'clarification'])

const sourceData = computed(() => {
  if (isPlainObject(props.data)) return props.data
  if (isPlainObject(props.protocol)) return props.protocol
  return {}
})

const normalizedPayload = computed(() => {
  const source = sourceData.value
  const rawContent = isPlainObject(source.content) ? source.content : {}

  return {
    protocolVersion: readText(source.protocolVersion || source.protocol_version),
    protocolValid: source.protocolValid === true,
    status: readText(source.status).toUpperCase(),
    messageType: readText(source.messageType || source.message_type).toLowerCase(),
    analysisType: readText(source.analysisType || source.analysis_type).toUpperCase(),
    clarification: isPlainObject(source.clarification) ? source.clarification : null,
    meta: isPlainObject(source.meta) ? source.meta : {},
    // Keep all business fields nested under content. No meta period label is inferred.
    content: {
      title: readText(rawContent.title || rawContent.heading),
      summary: readText(rawContent.summary || rawContent.answer || rawContent.text || rawContent.description),
      metrics: Array.isArray(rawContent.metrics) ? rawContent.metrics : [],
      table: isPlainObject(rawContent.table) ? rawContent.table : null,
      chart: isPlainObject(rawContent.chart) ? rawContent.chart : null,
      insights: Array.isArray(rawContent.insights) ? rawContent.insights : [],
      evidence: Array.isArray(rawContent.evidence) ? rawContent.evidence : [],
      dataInfo: isPlainObject(rawContent.dataInfo) ? rawContent.dataInfo : {},
      // These fields are optional contract extensions. Keep them empty when absent;
      // high-order views must remain usable on the current partial protocol.
      sections: Array.isArray(rawContent.sections) ? rawContent.sections : [],
      documentMarkdown: readText(rawContent.documentMarkdown),
      relatedMetrics: Array.isArray(rawContent.relatedMetrics) ? rawContent.relatedMetrics : [],
      followUps: Array.isArray(rawContent.followUps)
        ? rawContent.followUps
        : Array.isArray(rawContent.follow_ups)
          ? rawContent.follow_ups
          : [],
    },
  }
})

const resultActionMessage = ref('')
let resultActionTimer = null
const resultShellRef = ref(null)
const isLocallyMaximized = ref(false)
const isNativeFullscreen = ref(false)

const resultContent = computed(() => normalizedPayload.value.content)
const resultMeta = computed(() => normalizedPayload.value.meta)
const isResultMaximized = computed(() => isLocallyMaximized.value || isNativeFullscreen.value)
const resultSource = computed(() => readText(
  resultMeta.value.sourceLabel,
  resultMeta.value.sourceName,
  resultMeta.value.source,
))
const resultDuration = computed(() => {
  const value = resultMeta.value.durationMs ?? resultMeta.value.elapsedMs ?? resultMeta.value.duration
  if (value === null || value === undefined || value === '') return ''
  if (typeof value === 'number' && Number.isFinite(value)) {
    return `${value >= 100 ? (value / 1000).toFixed(1) : value.toFixed(1)}s`
  }
  return readText(value)
})
const resultReportText = computed(() => {
  const content = resultContent.value
  const metricLines = content.metrics
    .map((metric) => `${metric.label || '指标'}：${metric.value ?? '-'}${metric.unit || ''}`)
  const insightLines = content.insights
    .map((insight) => typeof insight === 'string' ? insight : insight?.text || insight?.label || '')
    .filter(Boolean)
  const evidenceLines = content.evidence
    .map((evidence) => typeof evidence === 'string' ? evidence : evidence?.text || evidence?.label || '')
    .filter(Boolean)
  return [
    content.title,
    content.summary,
    metricLines.length ? `核心指标：\n${metricLines.join('\n')}` : '',
    insightLines.length ? `数据要点：\n${insightLines.map((line) => `- ${line}`).join('\n')}` : '',
    evidenceLines.length ? `证据与校验：\n${evidenceLines.map((line) => `- ${line}`).join('\n')}` : '',
  ].filter(Boolean).join('\n\n')
})

const showResultActionMessage = (message) => {
  resultActionMessage.value = message
  window.clearTimeout(resultActionTimer)
  resultActionTimer = window.setTimeout(() => {
    resultActionMessage.value = ''
  }, 1800)
}

const exportResultReport = () => {
  const title = readText(resultContent.value.title) || '智能问数报告'
  const blob = new Blob([`# ${title}\n\n${resultReportText.value}\n`], { type: 'text/markdown;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `${title.replace(/[\\/:*?"<>|]/g, '_').slice(0, 36) || '智能问数报告'}.md`
  document.body.appendChild(link)
  link.click()
  link.remove()
  window.setTimeout(() => URL.revokeObjectURL(url), 0)
  showResultActionMessage('报告已导出')
}

const escapeCsvCell = (value) => {
  const text = String(value ?? '')
  return /[",\r\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text
}

const buildResultCsv = () => {
  const content = resultContent.value
  const table = content.table
  if (table?.columns?.length && Array.isArray(table.rows)) {
    const headers = table.columns.map((column) => column.label)
    const rows = table.rows.map((row) => table.columns.map((column) => row[column.key] ?? ''))
    return [headers, ...rows]
  }

  const chart = content.chart
  if (chart?.series?.length) {
    const categories = Array.isArray(chart.categories) ? chart.categories : []
    const headers = ['类别', ...chart.series.map((item, index) => item.name || `数值${index + 1}`)]
    const rows = Array.from({ length: categories.length }, (_, index) => [
      categories[index] ?? index + 1,
      ...chart.series.map((item) => item.data?.[index] ?? ''),
    ])
    return [headers, ...rows]
  }

  return []
}

const exportResultCsv = () => {
  const rows = buildResultCsv()
  if (!rows.length) {
    showResultActionMessage('暂无可导出的结构化数据')
    return
  }

  const csv = rows.map((row) => row.map(escapeCsvCell).join(',')).join('\r\n')
  const title = readText(resultContent.value.title) || '智能问数结果'
  const blob = new Blob([`\uFEFF${csv}`], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `${title.replace(/[\\/:*?"<>|]/g, '_').slice(0, 60) || '智能问数结果'}.csv`
  document.body.appendChild(link)
  link.click()
  link.remove()
  window.setTimeout(() => URL.revokeObjectURL(url), 0)
  showResultActionMessage('CSV 已导出')
}

const syncResultFullscreenState = () => {
  isNativeFullscreen.value = document.fullscreenElement === resultShellRef.value
}

const toggleResultFullscreen = async () => {
  if (isNativeFullscreen.value) {
    await document.exitFullscreen()
    return
  }

  if (isLocallyMaximized.value) {
    isLocallyMaximized.value = false
    return
  }

  try {
    if (resultShellRef.value?.requestFullscreen) {
      await resultShellRef.value.requestFullscreen()
      return
    }
  } catch {
    // Cross-origin portal frames can reject native fullscreen; use the in-panel fallback below.
  }

  isLocallyMaximized.value = true
  showResultActionMessage('已切换为面板最大化')
}

onMounted(() => {
  document.addEventListener('fullscreenchange', syncResultFullscreenState)
})

onBeforeUnmount(() => {
  document.removeEventListener('fullscreenchange', syncResultFullscreenState)
  window.clearTimeout(resultActionTimer)
})

const status = computed(() => normalizedPayload.value.status)
const isNoData = computed(() => NO_DATA_STATUSES.has(status.value))
const isAccessDenied = computed(() => {
  const source = sourceData.value
  return Boolean(
    props.accessDenied ||
      source.accessDenied === true ||
      source.access?.denied === true ||
      source.meta?.accessDenied === true ||
      ACCESS_DENIED_STATUSES.has(status.value) ||
      ['denied', 'not-covered'].includes(readText(props.accessStatus).toLowerCase()),
  )
})

const accessDeniedReason = computed(() => {
  const source = sourceData.value
  return (
    readText(props.accessDeniedReason) ||
    readText(source.accessDeniedReason || source.access?.reason || source.message) ||
    '您所在部门暂不支持生产指标智能问数，如有业务需要，请联系管理员申请。'
  )
})

const loading = computed(() =>
  props.loading || ['LOADING', 'PENDING', 'PROCESSING', 'IN_PROGRESS'].includes(status.value),
)

const isValidProtocol = computed(() =>
  normalizedPayload.value.protocolVersion === '2.0' &&
  normalizedPayload.value.protocolValid === true,
)

const viewComponentMap = {
  FACT: FactView,
  DETAIL: FactView,
  TREND: TrendView,
  RANKING: RankingView,
  COMPARISON: ComparisonView,
  ANOMALY: AnomalyView,
  DRILLDOWN: DrilldownView,
  DIAGNOSIS: DrilldownView,
  OVERVIEW: OverviewView,
}

const resolvedViewComponent = computed(() =>
  viewComponentMap[normalizedPayload.value.analysisType] || GenericAnalysisView,
)

const fallbackText = computed(() => {
  if (isNoData.value) return normalizedPayload.value.content.summary || EMPTY_STATE_TEXT
  return normalizedPayload.value.content.summary || props.answer || FRIENDLY_FALLBACK_TEXT
})
</script>

<template>
  <div class="data-query-result-root">
    <!-- 1. 未授权阻断 -->
    <AccessBlockedView v-if="isAccessDenied" :reason="accessDeniedReason" />

    <!-- 2. 加载骨架屏 -->
    <LoadingSkeleton v-else-if="loading" />

    <!-- 3. 结构化协议分发：精细视图优先，未知类型安全回退到通用分析视图。 -->
    <section
      v-else-if="isValidProtocol"
      ref="resultShellRef"
      class="data-query-result-card-shell"
      :class="{ 'data-query-result-card-shell-local-maximized': isLocallyMaximized }"
    >
      <div class="data-query-result-meta-bar" aria-label="智能问数结果操作">
        <div class="data-query-result-meta-copy">
          <strong>⚡ 智能问数</strong>
          <span v-if="resultDuration">耗时 {{ resultDuration }}</span>
          <span v-if="resultSource">· 来源：{{ resultSource }}</span>
        </div>
        <div class="data-query-result-meta-actions">
          <span v-if="resultActionMessage" class="data-query-result-action-feedback" aria-live="polite">{{ resultActionMessage }}</span>
          <button type="button" title="导出 CSV" aria-label="导出 CSV" @click="exportResultCsv">
            <Download :size="13" aria-hidden="true" />
            <span>导出 CSV</span>
          </button>
          <button type="button" title="导出问数简报" aria-label="导出问数简报" @click="exportResultReport">
            <FileText :size="13" aria-hidden="true" />
            <span>导出简报</span>
            <ChevronDown :size="13" aria-hidden="true" />
          </button>
          <button
            type="button"
            :title="isResultMaximized ? '退出全屏' : '全屏放大'"
            :aria-label="isResultMaximized ? '退出全屏' : '全屏放大'"
            @click="toggleResultFullscreen"
          >
            <Minimize2 v-if="isResultMaximized" :size="13" aria-hidden="true" />
            <Maximize2 v-else :size="13" aria-hidden="true" />
            <span>{{ isResultMaximized ? '退出全屏' : '全屏放大' }}</span>
          </button>
        </div>
      </div>
      <component
        :is="resolvedViewComponent"
        :content="normalizedPayload.content"
        :meta="normalizedPayload.meta"
        :data-info="normalizedPayload.content?.dataInfo"
        :status="normalizedPayload.status"
        :message-type="normalizedPayload.messageType"
        :analysis-type="normalizedPayload.analysisType"
        :clarification="normalizedPayload.clarification"
        :chart-option="chartOption"
        :window-view="windowView"
        @follow-up="emit('follow-up', $event)"
        @clarification="emit('clarification', $event)"
      />
    </section>

    <!-- 4. 降级或非结构化文本 -->
    <ProtocolFallbackView v-else :raw-text="fallbackText" />
  </div>
</template>

<style scoped>
.data-query-result-root {
  width: 100%;
  min-width: 0;
}

.data-query-result-card-shell {
  width: 100%;
  min-width: 0;
  position: relative;
}

.data-query-result-card-shell-local-maximized {
  position: absolute;
  z-index: 40;
  inset: 12px;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  min-height: 0;
  padding: 12px;
  overflow: auto;
  border-radius: 10px;
  background: #ffffff;
  box-shadow: 0 18px 44px rgba(30, 64, 175, 0.2);
}

.data-query-result-meta-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 0 0 7px;
  border-bottom: 1px solid #edf2f7;
  color: #6d8195;
  font-size: 10px;
}

.data-query-result-meta-copy,
.data-query-result-meta-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 5px;
  min-width: 0;
}

.data-query-result-meta-copy strong {
  color: #176dcc;
  font-size: 11px;
}

.data-query-result-meta-actions {
  justify-content: flex-end;
}

.data-query-result-meta-actions button {
  min-height: 24px;
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 3px 6px;
  border: 1px solid #c9def5;
  border-radius: 6px;
  color: #176dcc;
  background: #f7fbff;
  cursor: pointer;
  font-size: 10px;
}

.data-query-result-meta-actions button:hover,
.data-query-result-meta-actions button:focus-visible {
  border-color: #8dbce5;
  background: #eef7ff;
  outline: none;
}

.data-query-result-action-feedback {
  color: #3b7b66;
  font-weight: 700;
}
</style>
