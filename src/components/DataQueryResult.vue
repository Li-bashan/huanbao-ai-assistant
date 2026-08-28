<script setup>
import { Check, Copy, Download, Maximize2, Minimize2 } from '@lucide/vue'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import DataQueryChart from './DataQueryChart.vue'
import {
  extractDataQueryRankingTable,
  removeDataQueryChartPayload,
} from '../utils/dataQueryChart.js'
import { getDataQueryCompanyShortName } from '../config/dataQueryCatalog.js'
import { renderMarkdown } from '../utils/markdown'
import { copyText } from '../utils/messageExport.js'

const props = defineProps({
  answer: { type: String, default: '' },
  chartOption: { type: Object, default: null },
})

const tableCardRef = ref(null)
const isLocallyMaximized = ref(false)
const isNativeFullscreen = ref(false)
const isMaximized = computed(() => isLocallyMaximized.value || isNativeFullscreen.value)
const actionMessage = ref('')
let actionMessageTimer = null

const cleanAnswer = computed(() => removeDataQueryChartPayload(props.answer))
const rankingTable = computed(() => extractDataQueryRankingTable(cleanAnswer.value))
const beforeTable = computed(() => rankingTable.value?.before || cleanAnswer.value)
const afterTable = computed(() => rankingTable.value?.after || '')

const getTableRows = () => rankingTable.value?.rows || []

const tableRows = computed(() => getTableRows())
const displayCompanyName = (name) => getDataQueryCompanyShortName(name) || name

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
    await copyText(tableMatrix().map((row) => row.join('\t')).join('\n'))
    showActionMessage('排名明细已复制')
  } catch {
    exportTableCsv()
    showActionMessage('门户限制剪贴板，已改为下载排名明细 CSV')
  }
}

const exportTableCsv = () => {
  const csv = tableMatrix().map((row) => row.map(escapeCsvCell).join(',')).join('\r\n')
  const blob = new Blob([`\uFEFF${csv}`], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = '智能问数-排名明细.csv'
  document.body.appendChild(link)
  link.click()
  link.remove()
  window.setTimeout(() => URL.revokeObjectURL(url), 0)
  showActionMessage('排名明细 CSV 已导出')
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
  <div class="data-query-result">
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
            <Check v-if="actionMessage === '排名明细已复制'" :size="15" />
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
          <thead>
            <tr>
              <th v-for="header in rankingTable.headers" :key="header">{{ header }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in rankingTable.rows" :key="`${row.rank}-${row.name}`">
              <td>{{ row.rank }}</td>
              <td :title="row.name">{{ displayCompanyName(row.name) }}</td>
              <td>{{ formatValue(row.value) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <div v-if="rankingTable" class="markdown-body markdown-content data-query-answer" v-html="renderMarkdown(afterTable)"></div>
    <DataQueryChart v-if="chartOption" :option="chartOption" />
  </div>
</template>
