<script setup>
import { Download, FileText, Maximize2, Minimize2 } from '@lucide/vue'
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

const parseNumber = (value) => {
  if (typeof value === 'number') return Number.isFinite(value) ? value : null
  const parsed = Number(String(value ?? '').replace(/[,，%％\s]/g, ''))
  return Number.isFinite(parsed) ? parsed : null
}

const formatTrendNumber = (value) => Number(value.toFixed(4))

const getTrendPeriodLabel = (period) => {
  const text = readText(period)
  const match = text.match(/^(\d{4})-(\d{2})(?:-\d{2})?$/)
  return match ? `${Number(match[2])}月` : text
}

const getPreviousPeriodLabel = (period) => {
  const match = String(period || '').match(/^(\d{4})-(\d{2})(?:-\d{2})?$/)
  if (!match) return '上一期'

  const year = Number(match[1])
  const month = Number(match[2])
  const previous = month === 1 ? { year: year - 1, month: 12 } : { year, month: month - 1 }
  return `${previous.year}年${previous.month}月`
}

const getResultSource = (meta) => readText(
  meta.sourceLabel ||
  meta.sourceName ||
  meta.source,
)

const getResultDuration = (meta) => {
  const value = meta.durationMs ?? meta.elapsedMs ?? meta.duration
  if (value === null || value === undefined || value === '') return ''
  if (typeof value === 'number' && Number.isFinite(value)) {
    return `${value >= 100 ? (value / 1000).toFixed(1) : value.toFixed(1)}s`
  }
  return readText(value)
}

const getResultTokenUsage = (meta) => {
  const usage = meta.tokenUsage || meta.usage || meta.tokenConsumption
  if (!usage) return ''

  if (typeof usage === 'number' || typeof usage === 'string') {
    const value = parseNumber(usage)
    return value === null ? '' : value.toLocaleString('en-US')
  }

  if (!isPlainObject(usage)) return ''
  const totalValue = usage.totalTokens ?? usage.total_tokens ?? usage.tokens ?? usage.total
  const promptValue = usage.promptTokens ?? usage.prompt_tokens ?? usage.inputTokens ?? usage.input_tokens
  const completionValue = usage.completionTokens ?? usage.completion_tokens ?? usage.outputTokens ?? usage.output_tokens
  const total = parseNumber(totalValue)
  if (total !== null) return total.toLocaleString('en-US')

  const prompt = parseNumber(promptValue)
  const completion = parseNumber(completionValue)
  if (prompt !== null || completion !== null) {
    return ((prompt || 0) + (completion || 0)).toLocaleString('en-US')
  }

  return ''
}

const buildRecentTrendText = (rows) => {
  if (rows.length < 2) return ''
  const latestChange = rows[rows.length - 1].value - rows[rows.length - 2].value
  if (rows.length >= 3) {
    const previousChange = rows[rows.length - 2].value - rows[rows.length - 3].value
    if (latestChange > 0 && previousChange > 0) return '连续回升'
    if (latestChange < 0 && previousChange < 0) return '连续下降'
    if (latestChange > 0 && previousChange < 0) return '先降后升'
    if (latestChange < 0 && previousChange > 0) return '先升后降'
  }
  return latestChange > 0 ? '回升' : latestChange < 0 ? '下降' : '持平'
}

const buildFactTrendContent = (rawContent) => {
  const table = isPlainObject(rawContent.table) ? rawContent.table : null
  const columns = Array.isArray(table?.columns) ? table.columns : []
  const rows = Array.isArray(table?.rows) ? table.rows.filter(isPlainObject) : []
  if (rows.length < 3) return null

  const columnKeys = new Set(columns.map((column) => readText(column?.key)))
  if (!columnKeys.has('period') || !columnKeys.has('value')) return null

  const organizations = new Set(rows.map((row) => readText(row.organization)).filter(Boolean))
  const indicators = new Set(rows.map((row) => readText(row.indicator)).filter(Boolean))
  if (organizations.size > 1 || indicators.size > 1) return null

  const trendRows = rows.map((row) => {
    const period = readText(row.period)
    const value = parseNumber(row.value)
    return value === null || !period
      ? null
      : {
          period,
          organization: readText(row.organization),
          indicator: readText(row.indicator),
          value,
          yearOverYearPercent: parseNumber(row.yearOverYearPercent),
          monthOverMonthPercent: parseNumber(row.monthOverMonthPercent),
        }
  })
  if (trendRows.some((row) => row === null)) return null

  trendRows.sort((left, right) => left.period.localeCompare(right.period))
  const periods = new Set(trendRows.map((row) => row.period))
  if (periods.size !== trendRows.length) return null

  const dataInfo = isPlainObject(rawContent.dataInfo) ? rawContent.dataInfo : {}
  const timeRange = isPlainObject(dataInfo.timeRange) ? dataInfo.timeRange : {}
  const periodLabel = readText(timeRange.expression) || `近${trendRows.length}个月`
  const organizationName = trendRows[0].organization || readText(dataInfo.organizationScope?.names?.[0])
  const indicatorName = trendRows[0].indicator || readText(dataInfo.indicatorName) || '指标'
  const unit = readText(dataInfo.unit)
  const latest = trendRows[trendRows.length - 1]
  const latestMonth = getTrendPeriodLabel(latest.period)
  const total = trendRows.reduce((sum, row) => sum + row.value, 0)
  const max = trendRows.reduce((left, right) => (right.value > left.value ? right : left))
  const min = trendRows.reduce((left, right) => (right.value < left.value ? right : left))
  const recentTrendText = buildRecentTrendText(trendRows)
  const latestMom = latest.monthOverMonthPercent
  const latestMomText = latestMom === null
    ? '暂无环比数据'
    : `${latestMom > 0 ? '+' : ''}${latestMom.toFixed(2)}%`
  const latestMomDirection = latestMom === null
    ? '变化'
    : latestMom > 0 ? '增长' : latestMom < 0 ? '下降' : '持平'

  return {
    title: organizationName ? `${organizationName} · ${indicatorName}` : indicatorName,
    summary: `${periodLabel}${organizationName || ''}${indicatorName}整体呈波动走势，${latestMonth}较上月${latestMomDirection}${latestMom === null ? '' : ` ${Math.abs(latestMom).toFixed(2)}%`}，最近两个月${recentTrendText}。`,
    metrics: [
      { label: `${periodLabel}累计`, value: formatTrendNumber(total), unit },
      { label: `最新月（${latest.period}）`, value: formatTrendNumber(latest.value), unit },
      {
        label: `${latestMonth}同比变动`,
        value: latest.yearOverYearPercent,
        unit: '%',
        description: `与${latest.period.slice(0, 4) - 1}年${latestMonth}相比`,
      },
      {
        label: `${latestMonth}环比变动`,
        value: latestMom,
        unit: '%',
        description: `与${getPreviousPeriodLabel(latest.period)}相比`,
      },
    ].filter((metric) => metric.value !== null && metric.value !== undefined),
    table: rawContent.table,
    chart: {
      type: 'line',
      title: `${indicatorName}${periodLabel}走势`,
      xField: 'period',
      yField: 'value',
      categories: trendRows.map((row) => getTrendPeriodLabel(row.period)),
      series: [{
        name: indicatorName,
        type: 'line',
        data: trendRows.map((row) => formatTrendNumber(row.value)),
      }],
    },
    insights: [
      { type: 'fact', text: `最高月份：${getTrendPeriodLabel(max.period)}（${formatTrendNumber(max.value)}${unit ? ` ${unit}` : ''}）` },
      { type: 'fact', text: `最低月份：${getTrendPeriodLabel(min.period)}（${formatTrendNumber(min.value)}${unit ? ` ${unit}` : ''}）` },
      { type: 'fact', text: `最新月环比：${latestMomText}` },
      { type: 'fact', text: `最近 2 个月：${recentTrendText}` },
    ],
    evidence: Array.isArray(rawContent.evidence) ? rawContent.evidence : [],
    dataInfo,
    sections: Array.isArray(rawContent.sections) ? rawContent.sections : [],
    documentMarkdown: readText(rawContent.documentMarkdown),
    relatedMetrics: Array.isArray(rawContent.relatedMetrics) ? rawContent.relatedMetrics : [],
    followUps: [
      { label: `查看项目公司${periodLabel}排名`, query: `查看项目公司${periodLabel}${indicatorName}排名` },
      { label: '和去年同期比较', query: `${organizationName || ''}${periodLabel}${indicatorName}和去年同期比较` },
      {
        label: `查看${latestMonth}环比${latestMom > 0 ? '回升' : latestMom < 0 ? '下降' : '变化'}原因`,
        query: `查看${latest.period}${organizationName || ''}${indicatorName}环比${latestMom > 0 ? '回升' : latestMom < 0 ? '下降' : '变化'}原因`,
      },
    ],
  }
}

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
  const rawAnalysisType = readText(source.analysisType || source.analysis_type).toUpperCase()
  const trendContent = ['FACT', 'DETAIL'].includes(rawAnalysisType)
    ? buildFactTrendContent(rawContent)
    : null

  return {
    protocolVersion: readText(source.protocolVersion || source.protocol_version),
    protocolValid: source.protocolValid === true,
    status: readText(source.status).toUpperCase(),
    messageType: readText(source.messageType || source.message_type).toLowerCase(),
    analysisType: trendContent ? 'TREND' : rawAnalysisType,
    clarification: isPlainObject(source.clarification) ? source.clarification : null,
    meta: isPlainObject(source.meta) ? source.meta : {},
    // Keep all business fields nested under content. No meta period label is inferred.
    content: trendContent || {
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
const resultSource = computed(() => getResultSource(resultMeta.value))
const resultDuration = computed(() => getResultDuration(resultMeta.value))
const resultTokenUsage = computed(() => getResultTokenUsage(resultMeta.value))

const showResultActionMessage = (message) => {
  resultActionMessage.value = message
  window.clearTimeout(resultActionTimer)
  resultActionTimer = window.setTimeout(() => {
    resultActionMessage.value = ''
  }, 1800)
}

const escapeReportHtml = (value) => String(value ?? '')
  .replace(/&/g, '&amp;')
  .replace(/</g, '&lt;')
  .replace(/>/g, '&gt;')
  .replace(/"/g, '&quot;')
  .replace(/'/g, '&#39;')

const exportResultReport = () => {
  const title = readText(resultContent.value.title) || '智能问数报告'
  const content = resultContent.value
  const metrics = Array.isArray(content.metrics) ? content.metrics : []
  const insights = Array.isArray(content.insights) ? content.insights : []
  const metricHtml = metrics.length
    ? `<h2>核心指标</h2><table><thead><tr><th>指标</th><th>数值</th></tr></thead><tbody>${metrics
      .map((metric) => `<tr><td>${escapeReportHtml(metric.label)}</td><td>${escapeReportHtml(`${metric.value ?? '-'}${metric.unit || ''}`)}</td></tr>`)
      .join('')}</tbody></table>`
    : ''
  const insightHtml = insights.length
    ? `<h2>数据要点</h2><ul>${insights
      .map((insight) => `<li>${escapeReportHtml(typeof insight === 'string' ? insight : insight?.text || insight?.label || '')}</li>`)
      .join('')}</ul>`
    : ''
  const html = `<!doctype html><html><head><meta charset="utf-8"><title>${escapeReportHtml(title)}</title><style>
body{font-family:"Microsoft YaHei",SimSun,sans-serif;line-height:1.7;color:#1f2937}h1{font-size:22px}h2{font-size:16px;margin-top:20px}.meta{color:#64748b;font-size:13px}table{width:100%;border-collapse:collapse}th,td{border:1px solid #cbd5e1;padding:6px 8px;font-size:13px}th{background:#f1f5f9;text-align:left}
</style></head><body><h1>${escapeReportHtml(title)}</h1><p class="meta">${escapeReportHtml([
    resultDuration.value ? `耗时：${resultDuration.value}` : '',
    resultSource.value ? `来源：${resultSource.value}` : '',
    content.dataInfo?.dataCutoffDate ? `数据截至：${content.dataInfo.dataCutoffDate}` : '',
  ].filter(Boolean).join('；'))}</p><h2>摘要</h2><p>${escapeReportHtml(content.summary).replace(/\n/g, '<br>')}</p>${metricHtml}${insightHtml}</body></html>`
  const blob = new Blob([`\uFEFF${html}`], { type: 'application/msword;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `${title.replace(/[\\/:*?"<>|]/g, '_').slice(0, 60) || '智能问数报告'}.doc`
  document.body.appendChild(link)
  link.click()
  link.remove()
  window.setTimeout(() => URL.revokeObjectURL(url), 1000)
  showResultActionMessage('Word 已导出')
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

const exitResultFullscreen = async () => {
  if (isNativeFullscreen.value) {
    await document.exitFullscreen()
    return
  }
  isLocallyMaximized.value = false
}

const handleResultKeydown = (event) => {
  if (event.key !== 'Escape' || !isResultMaximized.value) return
  event.preventDefault()
  exitResultFullscreen()
}

const handleNewAssistantRequest = () => {
  if (isResultMaximized.value) exitResultFullscreen()
}

const handleFollowUp = (followUp) => {
  exitResultFullscreen()
  emit('follow-up', followUp)
}

const handleClarification = (candidate) => {
  exitResultFullscreen()
  emit('clarification', candidate)
}

onMounted(() => {
  document.addEventListener('fullscreenchange', syncResultFullscreenState)
  document.addEventListener('keydown', handleResultKeydown)
  window.addEventListener('huanbao:assistant-request', handleNewAssistantRequest)
})

onBeforeUnmount(() => {
  document.removeEventListener('fullscreenchange', syncResultFullscreenState)
  document.removeEventListener('keydown', handleResultKeydown)
  window.removeEventListener('huanbao:assistant-request', handleNewAssistantRequest)
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
          <span v-if="resultTokenUsage">· Token：{{ resultTokenUsage }}</span>
        </div>
        <div class="data-query-result-meta-actions">
          <span v-if="resultActionMessage" class="data-query-result-action-feedback" aria-live="polite">{{ resultActionMessage }}</span>
          <button type="button" title="导出 CSV" aria-label="导出 CSV" @click="exportResultCsv">
            <Download :size="13" aria-hidden="true" />
            <span>导出 CSV</span>
          </button>
          <button type="button" title="导出 Word 简报" aria-label="导出 Word 简报" @click="exportResultReport">
            <FileText :size="13" aria-hidden="true" />
            <span>导出 Word</span>
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
        @follow-up="handleFollowUp"
        @clarification="handleClarification"
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
