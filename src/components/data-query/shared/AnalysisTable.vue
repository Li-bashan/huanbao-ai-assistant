<script setup>
import { Check, Copy, Download, Maximize2, Minimize2 } from '@lucide/vue'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { getDataQueryCompanyShortName } from '../../../config/dataQueryCatalog.js'
import { copyText } from '../../../utils/messageExport.js'

const props = defineProps({
  table: { type: Object, default: null },
  windowView: { type: String, default: 'compact' },
})

const tableCardRef = ref(null)
const tableExpanded = ref(false)
const isLocallyMaximized = ref(false)
const isNativeFullscreen = ref(false)
const actionMessage = ref('')
let actionMessageTimer = null

const normalizedTable = computed(() => {
  const value = props.table
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null

  const columns = Array.isArray(value.columns)
    ? value.columns.filter((column) => column && typeof column === 'object' && column.key && column.label)
    : []
  if (!columns.length) return null

  const rows = Array.isArray(value.rows)
    ? value.rows.filter((row) => row && typeof row === 'object' && !Array.isArray(row))
    : []

  return {
    columns,
    rows,
    total: Number.isFinite(Number(value.total)) ? Number(value.total) : rows.length,
    defaultVisibleRows: Math.max(1, Math.min(100, Number(value.defaultVisibleRows) || 10)),
  }
})

const structuredRows = computed(() => {
  const value = normalizedTable.value
  if (!value) return []
  if (tableExpanded.value || props.windowView !== 'compact') return value.rows
  return value.rows.slice(0, value.defaultVisibleRows)
})

const isMaximized = computed(() => isLocallyMaximized.value || isNativeFullscreen.value)

const displayCompanyName = (name) => getDataQueryCompanyShortName(name) || name

const formatValue = (value, column) => {
  if (value === null || value === undefined || value === '') return '-'
  if (column?.type !== 'number' || !Number.isFinite(Number(value))) return String(value)
  return Number(value).toLocaleString('en-US', {
    minimumFractionDigits: 0,
    maximumFractionDigits: 4,
  })
}

const formatCell = (value, column) => {
  const formatted = formatValue(value, column)
  return ['organization', 'companyName'].includes(column?.key)
    ? displayCompanyName(formatted)
    : formatted
}

const tableMatrix = computed(() => {
  const value = normalizedTable.value
  if (!value) return []
  return [
    value.columns.map((column) => column.label),
    ...value.rows.map((row) => value.columns.map((column) => formatValue(row[column.key], column))),
  ]
})

const escapeCsvCell = (value) => {
  const text = String(value ?? '')
  return /[",\r\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text
}

const showActionMessage = (message) => {
  actionMessage.value = message
  window.clearTimeout(actionMessageTimer)
  actionMessageTimer = window.setTimeout(() => {
    actionMessage.value = ''
  }, 2200)
}

const exportTableCsv = () => {
  if (!tableMatrix.value.length) return
  const csv = tableMatrix.value.map((row) => row.map(escapeCsvCell).join(',')).join('\r\n')
  const blob = new Blob([`\uFEFF${csv}`], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = '智能问数-数据明细.csv'
  document.body.appendChild(link)
  link.click()
  link.remove()
  window.setTimeout(() => URL.revokeObjectURL(url), 0)
  showActionMessage('数据明细 CSV 已导出')
}

const copyTable = async () => {
  if (!tableMatrix.value.length) return
  try {
    await copyText(tableMatrix.value.map((row) => row.join('\t')).join('\n'))
    showActionMessage('数据明细已复制')
  } catch {
    exportTableCsv()
    showActionMessage('门户限制剪贴板，已改为下载数据明细 CSV')
  }
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
  <section
    v-if="normalizedTable"
    ref="tableCardRef"
    class="data-query-ranking-card data-query-structured-table-card"
    :class="{ 'data-query-ranking-card-local-maximized': isLocallyMaximized }"
    aria-label="分析明细"
  >
    <div class="data-query-ranking-header">
      <span>分析明细（{{ normalizedTable.total }} 条）</span>
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
          <tr>
            <th v-for="column in normalizedTable.columns" :key="column.key" :data-column-key="column.key">
              {{ column.label }}
            </th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="!structuredRows.length">
            <td :colspan="normalizedTable.columns.length">暂无明细数据</td>
          </tr>
          <tr v-for="(row, rowIndex) in structuredRows" :key="row.id || rowIndex">
            <td
              v-for="column in normalizedTable.columns"
              :key="column.key"
              :data-column-key="column.key"
              :title="column.key === 'organization' ? String(row[column.key] || '') : undefined"
              :class="{ 'data-query-number-cell': column.type === 'number' }"
            >
              {{ formatCell(row[column.key], column) }}
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <button
      v-if="normalizedTable.rows.length > structuredRows.length"
      type="button"
      class="data-query-table-more"
      @click="tableExpanded = true"
    >
      查看全部 {{ normalizedTable.total }} 条
    </button>
  </section>
</template>
