import { getDataQueryCompanyShortName } from '../config/dataQueryCatalog.js'

const MAX_CHART_OPTION_LENGTH = 120000
const MAX_ARRAY_ITEMS = 2000
const MAX_OBJECT_KEYS = 160

const CHART_OPTION_KEYS = [
  'echarts',
  'echartsOption',
  'echarts_option',
  'chartOption',
  'chart_option',
  'chartConfig',
  'chart_config',
  'option',
  'text',
  'answer',
  'result',
  'output',
  'response',
]

const isPlainObject = (value) =>
  value !== null && typeof value === 'object' && !Array.isArray(value)

const parseJsonCandidate = (value) => {
  if (isPlainObject(value) || Array.isArray(value)) return value
  if (typeof value !== 'string') return null

  const text = value.trim()
  if (!text || text.length > MAX_CHART_OPTION_LENGTH) return null

  const candidates = [text]
  const fencedMatches = text.matchAll(/```(?:json)?\s*([\s\S]*?)```/gi)
  for (const match of fencedMatches) candidates.push(match[1].trim())

  const firstBrace = text.indexOf('{')
  const lastBrace = text.lastIndexOf('}')
  if (firstBrace >= 0 && lastBrace > firstBrace) {
    candidates.push(text.slice(firstBrace, lastBrace + 1))
  }

  for (const candidate of candidates) {
    try {
      const parsed = JSON.parse(candidate)
      if (isPlainObject(parsed) || Array.isArray(parsed)) return parsed
    } catch {
      // 普通 Markdown 或自然语言不是图表配置，继续尝试其他候选片段。
    }
  }

  return null
}

const isEchartsOption = (value) =>
  isPlainObject(value) && Array.isArray(value.series) && value.series.length > 0

const sanitizeChartValue = (value, depth = 0) => {
  if (depth > 10) return null
  if (typeof value === 'string') return value.slice(0, 4000)
  if (typeof value === 'number' || typeof value === 'boolean' || value === null) return value

  if (Array.isArray(value)) {
    return value.slice(0, MAX_ARRAY_ITEMS).map((item) => sanitizeChartValue(item, depth + 1))
  }

  if (!isPlainObject(value)) return null

  return Object.entries(value)
    .slice(0, MAX_OBJECT_KEYS)
    .reduce((result, [key, item]) => {
      // ECharts formatter 可能携带 HTML 或脚本，不从后端配置执行它。
      if (key === 'formatter' || /^on[A-Z]/.test(key)) return result
      result[key] = sanitizeChartValue(item, depth + 1)
      return result
    }, {})
}

const findOption = (value, depth = 0) => {
  if (depth > 5 || value === null || value === undefined) return null

  const parsed = parseJsonCandidate(value)
  if (!parsed) return null
  if (isEchartsOption(parsed)) return parsed

  if (Array.isArray(parsed)) {
    for (const item of parsed) {
      const option = findOption(item, depth + 1)
      if (option) return option
    }
    return null
  }

  for (const key of CHART_OPTION_KEYS) {
    const option = findOption(parsed[key], depth + 1)
    if (option) return option
  }

  if (isPlainObject(parsed.chart)) {
    const option = findOption(parsed.chart.option || parsed.chart, depth + 1)
    if (option) return option
  }

  return null
}

export function extractDataQueryChartOption(value) {
  const option = findOption(value)
  if (!option) return null

  const sanitized = sanitizeChartValue(option)
  if (!isEchartsOption(sanitized)) return null

  try {
    if (JSON.stringify(sanitized).length > MAX_CHART_OPTION_LENGTH) return null
  } catch {
    return null
  }

  return sanitized
}

const findBalancedJsonEnd = (text, startIndex) => {
  const opening = text[startIndex]
  if (opening !== '{' && opening !== '[') return -1

  const stack = []
  let inString = false
  let escaped = false

  for (let index = startIndex; index < text.length; index += 1) {
    const char = text[index]

    if (inString) {
      if (escaped) {
        escaped = false
      } else if (char === '\\') {
        escaped = true
      } else if (char === '"') {
        inString = false
      }
      continue
    }

    if (char === '"') {
      inString = true
      continue
    }

    if (char === '{' || char === '[') {
      stack.push(char)
      continue
    }

    if (char !== '}' && char !== ']') continue

    const expectedOpening = char === '}' ? '{' : '['
    if (stack[stack.length - 1] !== expectedOpening) return -1
    stack.pop()

    if (!stack.length) return index + 1
  }

  return -1
}

const CHART_CONFIG_HINT = /["']?(?:title|tooltip|legend|xAxis|yAxis|series|dataset)["']?\s*:/i

const findCompleteChartPayloadRange = (text) => {
  const fencedMatches = text.matchAll(/```(?:json)?\s*([\s\S]*?)```/gi)
  for (const match of fencedMatches) {
    if (extractDataQueryChartOption(match[1])) {
      return { start: match.index, end: match.index + match[0].length }
    }
  }

  for (let index = 0; index < text.length; index += 1) {
    if (text[index] !== '{' && text[index] !== '[') continue

    const end = findBalancedJsonEnd(text, index)
    if (end < 0) continue

    const candidate = text.slice(index, end)
    if (CHART_CONFIG_HINT.test(candidate) && extractDataQueryChartOption(candidate)) {
      return { start: index, end }
    }
  }

  return null
}

const findIncompleteChartPayloadStart = (text) => {
  const inlineStart = text.search(
    /(?:```(?:json)?\s*)?(?:\[\s*\{\s*|\{\s*["']?(?:title|tooltip|legend|xAxis|yAxis|series|dataset)["']?\s*:)/i,
  )
  if (inlineStart < 0) return -1

  const prefix = text.slice(0, inlineStart)
  const lineStart = prefix.lastIndexOf('\n') + 1
  const linePrefix = prefix.slice(lineStart).trim()
  if (!linePrefix || /(?:图表|chart|echarts|配置|结果)\s*[：:]?\s*$/i.test(linePrefix)) {
    const firstNonWhitespace = prefix.slice(lineStart).search(/\S/)
    return firstNonWhitespace < 0 ? inlineStart : lineStart + firstNonWhitespace
  }

  return inlineStart
}

export function removeDataQueryChartPayload(value) {
  let text = String(value || '')

  while (text) {
    const range = findCompleteChartPayloadRange(text)
    if (!range) break
    text = `${text.slice(0, range.start)}${text.slice(range.end)}`
  }

  const incompleteStart = findIncompleteChartPayloadStart(text)
  if (incompleteStart >= 0) text = text.slice(0, incompleteStart)

  return text
    .replace(/^[ \t]*(?:图表配置|chart(?:\s+option)?|echarts配置)\s*[：:]?[ \t]*$/gim, '')
    .replace(/\n{3,}/g, '\n\n')
    .trim()
}

const getChartTitle = (option) => {
  if (typeof option?.title === 'string') return option.title
  if (isPlainObject(option?.title)) return String(option.title.text || '')
  return ''
}

const formatChartAxisValue = (value) => {
  const number = Number(value)
  if (!Number.isFinite(number)) return value

  const absolute = Math.abs(number)
  const format = (divisor, suffix) => {
    const scaled = number / divisor
    const digits = Math.abs(scaled) >= 100 ? 0 : Math.abs(scaled) >= 10 ? 1 : 2
    return `${scaled.toFixed(digits).replace(/\.0+$|(?<=\.[0-9])0+$/, '')}${suffix}`
  }

  if (absolute >= 100000000) return format(100000000, '亿')
  if (absolute >= 10000) return format(10000, '万')
  if (absolute >= 1000) return format(1000, '千')
  return String(number)
}

const isRankingChart = (option) => /排名|排行|top\s*\d+|前\s*\d+/i.test(getChartTitle(option))

const getAxisObject = (axis) => (Array.isArray(axis) ? axis[0] : axis)

const cloneChartOption = (option) => {
  try {
    return JSON.parse(JSON.stringify(option))
  } catch {
    return null
  }
}

export function adaptDataQueryChartOption(option) {
  const adapted = cloneChartOption(option)
  if (!isEchartsOption(adapted)) return option

  const title = isPlainObject(adapted.title) ? adapted.title : { text: getChartTitle(adapted) }
  title.left = 'center'
  title.top = 8
  title.width = '92%'
  title.overflow = 'truncate'
  title.textStyle = {
    ...(isPlainObject(title.textStyle) ? title.textStyle : {}),
    fontSize: 14,
    lineHeight: 20,
  }
  adapted.title = title

  const originalGrid = isPlainObject(adapted.grid) ? adapted.grid : {}
  const categoryAxis = getAxisObject(adapted.xAxis)
  const categories = Array.isArray(categoryAxis?.data) ? categoryAxis.data : []

  if (isRankingChart(adapted) && categories.length) {
    const names = categories.slice(0, 10).map((category) => getDataQueryCompanyShortName(category))
    const series = adapted.series.map((item) => ({
      ...item,
      type: 'bar',
      data: Array.isArray(item.data) ? item.data.slice(0, names.length) : item.data,
      barMaxWidth: item.barMaxWidth || 24,
    }))
    const valueAxis = getAxisObject(adapted.yAxis)
    const nextValueAxis = isPlainObject(valueAxis) ? { ...valueAxis } : {}
    const nextCategoryAxis = isPlainObject(categoryAxis) ? { ...categoryAxis } : {}
    delete nextValueAxis.data
    delete nextCategoryAxis.data

    adapted.xAxis = {
      ...nextValueAxis,
      type: 'value',
      axisLabel: {
        ...(isPlainObject(nextValueAxis.axisLabel) ? nextValueAxis.axisLabel : {}),
        hideOverlap: true,
        formatter: formatChartAxisValue,
      },
    }
    adapted.yAxis = {
      ...nextCategoryAxis,
      type: 'category',
      data: names,
      axisLabel: {
        ...(isPlainObject(nextCategoryAxis.axisLabel) ? nextCategoryAxis.axisLabel : {}),
        rotate: 0,
        width: 155,
        overflow: 'truncate',
        hideOverlap: false,
      },
    }
    adapted.series = series
    adapted.dataZoom = []
    adapted.grid = {
      ...originalGrid,
      left: 8,
      right: 18,
      top: 52,
      bottom: 12,
      containLabel: true,
    }
    return adapted
  }

  const nextXAxis = isPlainObject(categoryAxis) ? { ...categoryAxis } : {}
  if (categories.length > 6 && nextXAxis.type === 'category') {
    nextXAxis.axisLabel = {
      ...(isPlainObject(nextXAxis.axisLabel) ? nextXAxis.axisLabel : {}),
      rotate: 42,
      interval: 0,
      hideOverlap: true,
      width: 82,
      overflow: 'truncate',
    }
    adapted.xAxis = nextXAxis
    adapted.grid = {
      ...originalGrid,
      top: 52,
      bottom: 78,
      containLabel: true,
    }
  } else {
    adapted.grid = { ...originalGrid, containLabel: true }
  }

  return adapted
}

const parseChartNumber = (value) => {
  const normalized = String(value || '').replace(/[,，\s]/g, '').replace(/[%％吨小时天]+$/g, '')
  const number = Number(normalized)
  return Number.isFinite(number) ? number : null
}

const parseRankingRowsFromSection = (section) => {
  const rows = []

  section.split(/\r?\n/).forEach((line) => {
    const trimmed = line.trim()
    if (!trimmed || /^\|?\s*:?-{2,}/.test(trimmed)) return

    const cells = trimmed.split('|').map((cell) => cell.trim()).filter(Boolean)
    if (cells.length >= 3 && /^\d{1,3}$/.test(cells[0])) {
      const value = parseChartNumber(cells[cells.length - 1])
      if (value !== null) rows.push({ rank: cells[0], name: cells[1], value })
      return
    }

    const match = trimmed.match(/^\s*(\d{1,3})[.)、]?\s+(.+?)\s+([\d,，]+(?:\.\d+)?)\s*(?:吨|%|％|小时|天)?\s*$/)
    if (match) {
      const value = parseChartNumber(match[3])
      if (value !== null) rows.push({ rank: match[1], name: match[2].trim(), value })
    }
  })

  return rows
}

const parseRankingRows = (text) => {
  const rankingIndex = text.search(/排名明细|排名详情|项目公司排名|top\s*\d+|前\s*\d+/i)
  if (rankingIndex < 0) return []
  return parseRankingRowsFromSection(text.slice(rankingIndex)).slice(0, 10)
}

const findRankingTableRange = (text) => {
  const lines = text.split(/\r?\n/)
  const headerIndex = lines.findIndex((line) => {
    const normalized = line.replace(/\s/g, '')
    return normalized.includes('公司名称') && normalized.includes('|')
  })
  if (headerIndex < 0) return null

  let endIndex = headerIndex + 1
  while (endIndex < lines.length) {
    const line = lines[endIndex].trim()
    if (!line || /^\|?\s*:?-{2,}/.test(line) || /^\|?\s*\d{1,3}\s*\|/.test(line)) {
      endIndex += 1
      continue
    }
    break
  }

  return { lines, headerIndex, endIndex }
}

export function extractDataQueryRankingTable(value) {
  const text = removeDataQueryChartPayload(value)
  const range = findRankingTableRange(text)
  if (!range) return null

  const headers = range.lines[range.headerIndex]
    .split('|')
    .map((cell) => cell.trim())
    .filter(Boolean)
  const rows = parseRankingRowsFromSection(range.lines.slice(range.headerIndex + 1, range.endIndex).join('\n')).slice(0, 10)
  if (!rows.length) return null

  const beforeLines = range.lines.slice(0, range.headerIndex)
  while (beforeLines.length && !beforeLines[beforeLines.length - 1].trim()) beforeLines.pop()
  if (beforeLines.length && /排名明细|排名详情/i.test(beforeLines[beforeLines.length - 1])) beforeLines.pop()

  return {
    headers: headers.length >= 3 ? headers.slice(0, 3) : ['排名', '公司名称', '数值'],
    rows,
    before: beforeLines.join('\n').trim(),
    after: range.lines.slice(range.endIndex).join('\n').trim(),
  }
}

export function createDataQueryChartOptionFromAnswer(value) {
  const text = String(value || '').trim()
  if (!text || !/排名|排行|项目公司/i.test(text)) return null

  const rows = parseRankingRows(text)
  if (!rows.length) return null

  const titleMatch = text.match(/(?:\d{4}年[^\n。|]*?(?:排名|排行)[^\n。|]*)/i)
  const title = titleMatch?.[0]?.trim() || '项目公司排名（Top 10）'
  const unit = /(?:吨|%|％|小时|天)/.exec(text)?.[0] || ''

  return {
    title: { text: title },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    xAxis: { type: 'category', data: rows.map((row) => row.name) },
    yAxis: { type: 'value', name: unit },
    series: [{ type: 'bar', data: rows.map((row) => row.value) }],
  }
}
