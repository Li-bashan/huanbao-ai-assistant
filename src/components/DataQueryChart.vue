<script setup>
import { Check, Copy, Download, Maximize2, Minimize2 } from '@lucide/vue'
import { init, use } from 'echarts/core'
import {
  BarChart,
  FunnelChart,
  GaugeChart,
  HeatmapChart,
  LineChart,
  PieChart,
  RadarChart,
  ScatterChart,
} from 'echarts/charts'
import {
  AriaComponent,
  DataZoomComponent,
  DatasetComponent,
  GridComponent,
  LegendComponent,
  MarkAreaComponent,
  MarkLineComponent,
  MarkPointComponent,
  TitleComponent,
  TooltipComponent,
  VisualMapComponent,
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { LegacyGridContainLabel } from 'echarts/features'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { adaptDataQueryChartOption } from '../utils/dataQueryChart.js'
import { createIgixAssistantWindow } from '../utils/igixAssistantWindow.js'

use([
  BarChart,
  FunnelChart,
  GaugeChart,
  HeatmapChart,
  LineChart,
  PieChart,
  RadarChart,
  ScatterChart,
  AriaComponent,
  DataZoomComponent,
  DatasetComponent,
  GridComponent,
  LegendComponent,
  MarkAreaComponent,
  MarkLineComponent,
  MarkPointComponent,
  TitleComponent,
  TooltipComponent,
  VisualMapComponent,
  CanvasRenderer,
  LegacyGridContainLabel,
])

const props = defineProps({
  option: { type: Object, required: true },
  windowView: { type: String, default: 'compact' },
})

const chartRef = ref(null)
const chartCardRef = ref(null)
const isLocallyMaximized = ref(false)
const isNativeFullscreen = ref(false)
const isMaximized = computed(() => isLocallyMaximized.value || isNativeFullscreen.value)
const isCompactWindow = computed(() => props.windowView === 'compact')
const isExpandingWindow = ref(false)
const actionMessage = ref('')
let chartInstance = null
let resizeObserver = null
let actionMessageTimer = null
let assistantWindow = null
let expandWindowTimer = null

const getChartOption = () => adaptDataQueryChartOption(props.option) || props.option

const getChartTitle = (option) => {
  if (typeof option?.title === 'string') return option.title
  return option?.title?.text || ''
}

const getAxisObject = (axis) => (Array.isArray(axis) ? axis[0] : axis)

const getChartCategories = (option) => {
  const categoryAxis = getAxisObject(option?.yAxis)?.type === 'category'
    ? getAxisObject(option.yAxis)
    : getAxisObject(option?.xAxis)
  return Array.isArray(categoryAxis?.data) ? categoryAxis.data : []
}

const getSeriesValue = (value) => {
  if (value && typeof value === 'object') return value.value ?? ''
  return value ?? ''
}

const escapeCsvCell = (value) => {
  const text = String(value ?? '')
  return /[",\r\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text
}

const buildChartCsv = (option) => {
  const series = Array.isArray(option?.series) ? option.series : []
  const categories = getChartCategories(option)
  const rowCount = Math.max(
    categories.length,
    ...series.map((item) => (Array.isArray(item.data) ? item.data.length : 0)),
  )
  const headers = [
    '类别',
    ...series.map((item, index) => item.name || `数值${index + 1}`),
  ]
  const rows = Array.from({ length: rowCount }, (_, index) => [
    categories[index] ?? index + 1,
    ...series.map((item) => getSeriesValue(item.data?.[index])),
  ])

  return [headers, ...rows]
    .map((row) => row.map(escapeCsvCell).join(','))
    .join('\r\n')
}

const showActionMessage = (message) => {
  actionMessage.value = message
  window.clearTimeout(actionMessageTimer)
  actionMessageTimer = window.setTimeout(() => {
    actionMessage.value = ''
  }, 2200)
}

const copyChart = async () => {
  if (!chartInstance) return

  let dataUrl = ''
  try {
    dataUrl = chartInstance.getDataURL({
      type: 'png',
      pixelRatio: 2,
      backgroundColor: '#ffffff',
    })

    if (!navigator.clipboard?.write || typeof ClipboardItem === 'undefined') {
      throw new Error('clipboard-image-unavailable')
    }

    const blob = await (await fetch(dataUrl)).blob()
    await navigator.clipboard.write([new ClipboardItem({ 'image/png': blob })])
    showActionMessage('图表已复制')
  } catch {
    if (dataUrl) {
      const link = document.createElement('a')
      link.href = dataUrl
      link.download = '智能问数图表.png'
      document.body.appendChild(link)
      link.click()
      link.remove()
      showActionMessage('门户限制图片剪贴板，已改为下载 PNG')
      return
    }

    showActionMessage('复制图表失败，请稍后重试')
  }
}

const exportChartCsv = () => {
  const csv = buildChartCsv(props.option)
  const blob = new Blob([`\uFEFF${csv}`], { type: 'text/csv;charset=utf-8' })
  const link = document.createElement('a')
  const title = String(getChartTitle(getChartOption()) || '智能问数图表')
    .replace(/[\\/:*?"<>|]/g, '')
    .slice(0, 60)
  const url = URL.createObjectURL(blob)
  link.href = url
  link.download = `${title || '智能问数图表'}.csv`
  document.body.appendChild(link)
  link.click()
  link.remove()
  window.setTimeout(() => URL.revokeObjectURL(url), 0)
  showActionMessage('CSV 已导出')
}

const syncFullscreenState = () => {
  isNativeFullscreen.value = document.fullscreenElement === chartCardRef.value
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
    if (chartCardRef.value?.requestFullscreen) {
      await chartCardRef.value.requestFullscreen()
      return
    }
  } catch {
    // Cross-origin portal frames can reject native fullscreen; use the in-panel fallback below.
  }

  isLocallyMaximized.value = true
  showActionMessage('已切换为面板最大化')
}

const renderChart = async () => {
  await nextTick()
  if (!chartRef.value) return

  if (!chartInstance) chartInstance = init(chartRef.value)
  chartInstance.setOption(adaptDataQueryChartOption(props.option), { notMerge: true, lazyUpdate: true })
  chartInstance.resize()
}

const resizeChart = () => chartInstance?.resize()

const expandWindowForChart = () => {
  if (!isCompactWindow.value || isExpandingWindow.value) return

  isExpandingWindow.value = true
  assistantWindow?.wide()
  showActionMessage('正在展开中窗查看图表')
  window.clearTimeout(expandWindowTimer)
  expandWindowTimer = window.setTimeout(() => {
    isExpandingWindow.value = false
  }, 1200)
}

onMounted(() => {
  assistantWindow = createIgixAssistantWindow()
  renderChart()
  document.addEventListener('fullscreenchange', syncFullscreenState)
  if (typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(resizeChart)
    if (chartRef.value) resizeObserver.observe(chartRef.value)
  } else {
    window.addEventListener('resize', resizeChart)
  }
})

watch(() => props.option, renderChart, { deep: true })
watch(
  () => props.windowView,
  () => {
    nextTick(resizeChart)
  },
)

onBeforeUnmount(() => {
  document.removeEventListener('fullscreenchange', syncFullscreenState)
  window.clearTimeout(actionMessageTimer)
  window.clearTimeout(expandWindowTimer)
  resizeObserver?.disconnect()
  window.removeEventListener('resize', resizeChart)
  assistantWindow?.destroy()
  assistantWindow = null
  chartInstance?.dispose()
  chartInstance = null
})
</script>

<template>
  <section
    ref="chartCardRef"
    class="data-query-chart-card"
    :class="{ 'data-query-chart-card-local-maximized': isLocallyMaximized }"
    aria-label="智能问数图表"
  >
    <div class="data-query-chart-header">
      <div class="data-query-chart-label">ECHARTS</div>
      <div class="data-query-chart-toolbar" aria-label="图表操作">
        <button
          v-if="isCompactWindow && !isMaximized"
          type="button"
          class="data-query-chart-expand-button"
          :disabled="isExpandingWindow"
          title="展开中窗查看图表"
          aria-label="展开中窗查看图表"
          @click.stop="expandWindowForChart"
        >
          <Maximize2 :size="14" />
          <span>{{ isExpandingWindow ? '展开中' : '展开大图' }}</span>
        </button>
        <button type="button" title="复制图表" aria-label="复制图表" @click.stop="copyChart">
          <Check v-if="actionMessage === '图表已复制'" :size="15" />
          <Copy v-else :size="15" />
        </button>
        <button type="button" title="导出 CSV" aria-label="导出 CSV" @click.stop="exportChartCsv">
          <Download :size="15" />
        </button>
        <button
          type="button"
          :title="isMaximized ? '退出最大化' : '最大化查看'"
          :aria-label="isMaximized ? '退出最大化' : '最大化查看'"
          @click.stop="toggleMaximize"
        >
          <Minimize2 v-if="isMaximized" :size="15" />
          <Maximize2 v-else :size="15" />
        </button>
      </div>
    </div>
    <div v-if="actionMessage" class="data-query-chart-feedback" aria-live="polite">{{ actionMessage }}</div>
    <div ref="chartRef" class="data-query-chart" role="img" aria-label="智能问数结果图表"></div>
  </section>
</template>
