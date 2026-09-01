<script setup>
import { Check, Copy, Download, Maximize2, Minimize2 } from '@lucide/vue'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import DataQueryChart from './DataQueryChart.vue'
import {
  extractDataQueryRankingTable,
  removeDataQueryChartPayload,
} from '../utils/dataQueryChart.js'
import { createDataQueryChartOption } from '../utils/dataQueryProtocol.js'
import { getDataQueryCompanyShortName } from '../config/dataQueryCatalog.js'
import { renderMarkdown } from '../utils/markdown'
import { copyText } from '../utils/messageExport.js'

const props = defineProps({
  answer: { type: String, default: '' },
  protocol: { type: Object, default: null },
  chartOption: { type: Object, default: null },
  windowView: { type: String, default: 'compact' },
})

const emit = defineEmits(['follow-up', 'clarification'])

const tableCardRef = ref(null)
const isLocallyMaximized = ref(false)
const isNativeFullscreen = ref(false)
const isMaximized = computed(() => isLocallyMaximized.value || isNativeFullscreen.value)
const actionMessage = ref('')
const tableExpanded = ref(false)
let actionMessageTimer = null

const isStructured = computed(() =>
  props.protocol?.protocolVersion === '2.0' && props.protocol?.protocolValid === true,
)
const structuredContent = computed(() => props.protocol?.content || {})
const structuredTable = computed(() => structuredContent.value.table || null)
const structuredRows = computed(() => {
  const rows = structuredTable.value?.rows || []
  if (tableExpanded.value || props.windowView !== 'compact') return rows
  return rows.slice(0, structuredTable.value?.defaultVisibleRows || 10)
})
const structuredChartOption = computed(() => createDataQueryChartOption(props.protocol))
const structuredEvidence = computed(() => (structuredContent.value.evidence || []).slice(0, 8))
const structuredWarnings = computed(() => {
  const warnings = structuredContent.value.dataInfo?.warnings
  return Array.isArray(warnings) ? warnings.filter(Boolean).slice(0, 6) : []
})
const protocolStatusLabel = computed(() => {
  if (props.protocol?.messageType === 'clarification') return '待选择'
  if (props.protocol?.status === 'SUCCESS_WITH_DATA') return '已校验'
  if (props.protocol?.status === 'NO_DATA_IN_PERIOD') return '暂无数据'
  return '需要处理'
})
const analysisTypeLabel = computed(() => ({
  FACT: '事实',
  DETAIL: '明细',
  TREND: '趋势',
  RANKING: '排名',
  COMPARISON: '对比',
  RANKING_COMPARISON: '同比排名',
  DISTRIBUTION: '分布',
  ANOMALY: '异常关注',
  DRILLDOWN: '下钻分析',
  OVERVIEW: '经营概览',
}[props.protocol?.analysisType] || '分析结果'))
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
const dataInfoLabel = (key) => dataInfoLabels[key] || key
const formatDataInfoValue = (value) => typeof value === 'object' ? JSON.stringify(value) : String(value ?? '')
const cleanAnswer = computed(() => removeDataQueryChartPayload(props.answer))
const rankingTable = computed(() => extractDataQueryRankingTable(cleanAnswer.value))
const beforeTable = computed(() => rankingTable.value?.before || cleanAnswer.value)
const afterTable = computed(() => rankingTable.value?.after || '')

const getTableRows = () => rankingTable.value?.rows || []

const tableRows = computed(() => getTableRows())
const displayCompanyName = (name) => getDataQueryCompanyShortName(name) || name

const formatStructuredValue = (value, column) => {
  if (value === null || value === undefined || value === '') return '-'
  if (column?.type !== 'number' || !Number.isFinite(Number(value))) return String(value)
  return Number(value).toLocaleString('en-US', {
    minimumFractionDigits: 0,
    maximumFractionDigits: 4,
  })
}

const structuredTableMatrix = () => {
  const columns = structuredTable.value?.columns || []
  return [
    columns.map((column) => column.label),
    ...(structuredTable.value?.rows || []).map((row) =>
      columns.map((column) => formatStructuredValue(row[column.key], column)),
    ),
  ]
}

const escapeCsvCell = (value) => {
  const text = String(value ?? '')
  return /[",\r\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text
}

const formatValue = (value) => Number(value).toLocaleString('en-US', {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
})

const tableMatrix = () => [
  rankingTable.value?.headers || ['排名', '公司名称', '数值'],
  ...tableRows.value.map((row) => [row.rank, row.name, formatValue(row.value)]),
]

const showActionMessage = (message) => {
  actionMessage.value = message
  window.clearTimeout(actionMessageTimer)
  actionMessageTimer = window.setTimeout(() => {
    actionMessage.value = ''
  }, 2200)
}

const copyTable = async () => {
  try {
    const matrix = isStructured.value ? structuredTableMatrix() : tableMatrix()
    await copyText(matrix.map((row) => row.join('\t')).join('\n'))
    showActionMessage(isStructured.value ? '数据明细已复制' : '排名明细已复制')
  } catch {
    exportTableCsv()
    showActionMessage(isStructured.value ? '门户限制剪贴板，已改为下载数据明细 CSV' : '门户限制剪贴板，已改为下载排名明细 CSV')
  }
}

const exportTableCsv = () => {
  const matrix = isStructured.value ? structuredTableMatrix() : tableMatrix()
  const csv = matrix.map((row) => row.map(escapeCsvCell).join(',')).join('\r\n')
  const blob = new Blob([`\uFEFF${csv}`], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = isStructured.value ? '智能问数-数据明细.csv' : '智能问数-排名明细.csv'
  document.body.appendChild(link)
  link.click()
  link.remove()
  window.setTimeout(() => URL.revokeObjectURL(url), 0)
  showActionMessage(isStructured.value ? '数据明细 CSV 已导出' : '排名明细 CSV 已导出')
}

const syncFullscreenState = () => {
  isNativeFullscreen.value = document.fullscreenElement === tableCardRef.value
}

const toggleMaximize = async () => {
  if (isNativeFullscreen.value) {
    await document.exitFullscreen()
    return
  }

  if (isLocallyMaximized.value) {
    isLocallyMaximized.value = false
    return
  }

  try {
    if (tableCardRef.value?.requestFullscreen) {
      await tableCardRef.value.requestFullscreen()
      return
    }
  } catch {
    // Cross-origin portal frames can reject native fullscreen; use the in-panel fallback below.
  }

  isLocallyMaximized.value = true
  showActionMessage('已切换为面板最大化')
}

onMounted(() => document.addEventListener('fullscreenchange', syncFullscreenState))

onBeforeUnmount(() => {
  document.removeEventListener('fullscreenchange', syncFullscreenState)
  window.clearTimeout(actionMessageTimer)
})
</script>

<template>
  <div v-if="isStructured" class="data-query-result data-query-analysis-card">
    <header class="data-query-analysis-header">
      <div>
        <span class="data-query-analysis-kicker">{{ analysisTypeLabel }}</span>
        <h3>{{ structuredContent.title || '经营数据分析' }}</h3>
      </div>
      <span class="data-query-analysis-status">{{ protocolStatusLabel }}</span>
    </header>

    <p class="data-query-analysis-summary">{{ structuredContent.summary || '查询已完成。' }}</p>

    <div v-if="structuredContent.metrics?.length" class="data-query-metrics" aria-label="核心指标">
      <div v-for="metric in structuredContent.metrics" :key="metric.label" class="data-query-metric">
        <span>{{ metric.label }}</span>
        <strong>{{ formatStructuredValue(metric.value, { type: 'number' }) }}<small>{{ metric.unit }}</small></strong>
      </div>
    </div>

    <section
      v-if="structuredTable"
      ref="tableCardRef"
      class="data-query-ranking-card data-query-structured-table-card"
      :class="{ 'data-query-ranking-card-local-maximized': isLocallyMaximized }"
      aria-label="分析明细"
    >
      <div class="data-query-ranking-header">
        <span>分析明细（{{ structuredTable.total }} 条）</span>
        <div class="data-query-ranking-toolbar" aria-label="分析明细操作">
          <button type="button" title="复制数据明细" aria-label="复制数据明细" @click="copyTable">
            <Check v-if="actionMessage.includes('已复制')" :size="15" />
            <Copy v-else :size="15" />
          </button>
          <button type="button" title="导出数据明细 CSV" aria-label="导出数据明细 CSV" @click="exportTableCsv">
            <Download :size="15" />
          </button>
          <button
            type="button"
            :title="isMaximized ? '退出最大化' : '最大化查看数据明细'"
            :aria-label="isMaximized ? '退出最大化' : '最大化查看数据明细'"
            @click="toggleMaximize"
          >
            <Minimize2 v-if="isMaximized" :size="15" />
            <Maximize2 v-else :size="15" />
          </button>
        </div>
      </div>
      <div v-if="actionMessage" class="data-query-ranking-feedback" aria-live="polite">{{ actionMessage }}</div>
      <div class="data-query-ranking-scroll">
        <table>
          <thead>
            <tr><th v-for="column in structuredTable.columns" :key="column.key">{{ column.label }}</th></tr>
          </thead>
          <tbody>
            <tr v-for="(row, rowIndex) in structuredRows" :key="row.id || rowIndex">
              <td v-for="column in structuredTable.columns" :key="column.key" :class="{ 'data-query-number-cell': column.type === 'number' }">
                {{ formatStructuredValue(row[column.key], column) }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <button
        v-if="structuredTable.rows.length > structuredRows.length"
        type="button"
        class="data-query-table-more"
        @click="tableExpanded = true"
      >
        查看全部 {{ structuredTable.total }} 条
      </button>
    </section>

    <section v-if="protocol.messageType === 'clarification' && protocol.clarification" class="data-query-clarification-card" aria-label="请选择候选项">
      <strong>{{ protocol.clarification.title }}</strong>
      <button
        v-for="candidate in protocol.clarification.candidates"
        :key="candidate.id"
        type="button"
        class="data-query-clarification-option"
        @click="emit('clarification', candidate)"
      >
        <span>{{ candidate.label }}</span>
        <small v-if="candidate.description">{{ candidate.description }}</small>
      </button>
    </section>

    <ul v-if="structuredContent.insights?.length" class="data-query-insight-list" aria-label="关键发现">
      <li v-for="insight in structuredContent.insights" :key="`${insight.type}-${insight.text}`">
        <span class="data-query-insight-mark">{{ insight.type === 'attention' ? '!' : '·' }}</span>
        <span>{{ insight.text }}</span>
      </li>
    </ul>

    <section v-if="structuredEvidence.length || structuredWarnings.length" class="data-query-evidence-card" aria-label="证据与校验">
      <div class="data-query-evidence-title">证据与校验</div>
      <ul v-if="structuredEvidence.length" class="data-query-evidence-list">
        <li v-for="(item, index) in structuredEvidence" :key="`${item.text}-${index}`">{{ item.text }}</li>
      </ul>
      <ul v-if="structuredWarnings.length" class="data-query-warning-list">
        <li v-for="warning in structuredWarnings" :key="warning">{{ warning }}</li>
      </ul>
    </section>

    <DataQueryChart v-if="structuredChartOption" :option="structuredChartOption" :window-view="windowView" />

    <details v-if="Object.keys(structuredContent.dataInfo || {}).length" class="data-query-data-info">
      <summary>数据说明</summary>
      <dl>
        <template v-for="(value, key) in structuredContent.dataInfo" :key="key">
          <dt>{{ dataInfoLabel(key) }}</dt><dd>{{ formatDataInfoValue(value) }}</dd>
        </template>
      </dl>
    </details>

    <div v-if="structuredContent.followUps?.length" class="data-query-followups" aria-label="继续分析">
      <span>继续分析</span>
      <button v-for="followUp in structuredContent.followUps" :key="followUp.id" type="button" @click="emit('follow-up', followUp)">
        {{ followUp.label }}
      </button>
    </div>
  </div>

  <div v-else-if="protocol?.protocolVersion === '2.0'" class="data-query-result data-query-analysis-fallback">
    {{ protocol.content?.summary || answer || '结果协议校验失败，请稍后重试。' }}
  </div>

  <div v-else class="data-query-result">
    <div class="markdown-body markdown-content data-query-answer" v-html="renderMarkdown(beforeTable)"></div>

    <section
      v-if="rankingTable"
      ref="tableCardRef"
      class="data-query-ranking-card"
      :class="{ 'data-query-ranking-card-local-maximized': isLocallyMaximized }"
      aria-label="排名明细"
    >
      <div class="data-query-ranking-header">
        <span>排名明细（前10名）</span>
        <div class="data-query-ranking-toolbar" aria-label="排名明细操作">
          <button type="button" title="复制排名明细" aria-label="复制排名明细" @click="copyTable">
            <Check v-if="actionMessage.includes('已复制')" :size="15" />
            <Copy v-else :size="15" />
          </button>
          <button type="button" title="导出排名明细 CSV" aria-label="导出排名明细 CSV" @click="exportTableCsv">
            <Download :size="15" />
          </button>
          <button
            type="button"
            :title="isMaximized ? '退出最大化' : '最大化查看排名明细'"
            :aria-label="isMaximized ? '退出最大化' : '最大化查看排名明细'"
            @click="toggleMaximize"
          >
            <Minimize2 v-if="isMaximized" :size="15" />
            <Maximize2 v-else :size="15" />
          </button>
        </div>
      </div>
      <div v-if="actionMessage" class="data-query-ranking-feedback" aria-live="polite">{{ actionMessage }}</div>
      <div class="data-query-ranking-scroll">
        <table>
          <thead><tr><th v-for="header in rankingTable.headers" :key="header">{{ header }}</th></tr></thead>
          <tbody>
            <tr v-for="row in rankingTable.rows" :key="`${row.rank}-${row.name}`">
              <td>{{ row.rank }}</td><td :title="row.name">{{ displayCompanyName(row.name) }}</td><td>{{ formatValue(row.value) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <div v-if="rankingTable" class="markdown-body markdown-content data-query-answer" v-html="renderMarkdown(afterTable)"></div>
    <DataQueryChart v-if="chartOption" :option="chartOption" :window-view="windowView" />
  </div>
</template>
