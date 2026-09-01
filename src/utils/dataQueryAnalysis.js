const MARKERS = {
  analysisState: /<!--HUANBAO_ANALYSIS_STATE:([\s\S]*?)-->/i,
  chartOption: /<!--HUANBAO_ANALYSIS_CHART:([\s\S]*?)-->/i,
  followUps: /<!--HUANBAO_ANALYSIS_FOLLOWUPS:([\s\S]*?)-->/i,
}

const isPlainObject = (value) =>
  value !== null && typeof value === 'object' && !Array.isArray(value)

const parseJson = (value) => {
  if (isPlainObject(value) || Array.isArray(value)) return value
  if (typeof value !== 'string') return null

  try {
    return JSON.parse(value.trim())
  } catch {
    return null
  }
}

const cleanText = (value) => String(value ?? '').trim()

const findText = (value, depth = 0) => {
  if (depth > 5 || value === null || value === undefined) return ''
  if (typeof value === 'string') return value
  if (Array.isArray(value)) {
    return value.map((item) => findText(item, depth + 1)).find(Boolean) || ''
  }
  if (!isPlainObject(value)) return ''

  for (const key of ['summary', 'answer', 'text', 'visible_answer', 'result_text', 'output']) {
    const text = findText(value[key], depth + 1)
    if (text) return text
  }

  for (const key of ['response', 'data', 'outputs', 'result', 'content']) {
    const text = findText(value[key], depth + 1)
    if (text) return text
  }

  return ''
}

const readMarker = (text, marker) => {
  const matched = text.match(marker)
  if (!matched) return null
  return parseJson(matched[1])
}

const normalizeFollowUps = (value) => {
  if (!Array.isArray(value)) return []

  return value
    .slice(0, 6)
    .map((item, index) => {
      if (typeof item === 'string') {
        const label = cleanText(item)
        return label ? { id: `follow-up-${index}`, label, query: label } : null
      }
      if (!isPlainObject(item)) return null
      const label = cleanText(item.label || item.title || item.query)
      const query = cleanText(item.query || item.prompt || label)
      return label && query
        ? { id: cleanText(item.id || `follow-up-${index}`), label, query }
        : null
    })
    .filter(Boolean)
}

export function stripDataQueryAnalysisMetadata(value = '') {
  return String(value || '')
    .replace(MARKERS.analysisState, '')
    .replace(MARKERS.chartOption, '')
    .replace(MARKERS.followUps, '')
    .replace(/\n{3,}/g, '\n\n')
    .trim()
}

export function extractDataQueryAnalysisMetadata(value) {
  const direct = isPlainObject(value) ? value : null
  const text = findText(value)
  const state =
    readMarker(text, MARKERS.analysisState) ||
    direct?.analysisState ||
    direct?.analysis_state ||
    direct?.meta?.analysisState ||
    direct?.meta?.analysis_state ||
    null
  const chartOption =
    readMarker(text, MARKERS.chartOption) ||
    direct?.chartOption ||
    direct?.chart_option ||
    direct?.meta?.chartOption ||
    null
  const followUps = normalizeFollowUps(
    readMarker(text, MARKERS.followUps) ||
      direct?.followUps ||
      direct?.follow_ups ||
      direct?.meta?.followUps ||
      direct?.meta?.follow_ups ||
      [],
  )

  return {
    analysisState: state,
    chartOption,
    followUps,
    text: stripDataQueryAnalysisMetadata(text),
  }
}

export function toDataQueryProtocolChart(chartOption) {
  if (!isPlainObject(chartOption) || !Array.isArray(chartOption.series) || !chartOption.series.length) {
    return null
  }

  const xAxis = Array.isArray(chartOption.xAxis) ? chartOption.xAxis[0] : chartOption.xAxis
  const categories = Array.isArray(xAxis?.data) ? xAxis.data.slice(0, 1000) : []
  const series = chartOption.series
    .filter((item) => isPlainObject(item) && Array.isArray(item.data))
    .slice(0, 12)
    .map((item) => ({
      name: cleanText(item.name),
      type: cleanText(item.type || 'bar') || 'bar',
      data: item.data.slice(0, 1000),
    }))

  if (!series.length) return null

  const title = chartOption.title
  return {
    type: cleanText(series[0].type || 'bar') || 'bar',
    title: cleanText(isPlainObject(title) ? title.text : title),
    xField: cleanText(xAxis?.name),
    yField: cleanText((Array.isArray(chartOption.yAxis) ? chartOption.yAxis[0] : chartOption.yAxis)?.name),
    categories,
    series,
  }
}

export function getDataQueryAnalysisState(value) {
  return extractDataQueryAnalysisMetadata(value).analysisState || null
}
