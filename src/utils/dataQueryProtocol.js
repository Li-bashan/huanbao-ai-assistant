export const DATA_QUERY_PROTOCOL_VERSION = '2.0'

export const DATA_QUERY_MESSAGE_TYPES = new Set([
  'analysis',
  'clarification',
  'error',
  'empty',
  'information',
])

export const DATA_QUERY_ANALYSIS_TYPES = new Set([
  'FACT',
  'DETAIL',
  'TREND',
  'RANKING',
  'COMPARISON',
  'RANKING_COMPARISON',
  'DISTRIBUTION',
  'ANOMALY',
  'DRILLDOWN',
  'OVERVIEW',
])

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

const cleanText = (value, maxLength = 4000) =>
  String(value ?? '')
    .replace(/<think>[\s\S]*?<\/think>/gi, '')
    .replace(/<think>[\s\S]*$/gi, '')
    .replace(/<\/think>/gi, '')
    .trim()
    .slice(0, maxLength)

const firstText = (...values) => {
  for (const value of values) {
    const text = cleanText(value)
    if (text) return text
  }
  return ''
}

const readNestedProtocol = (value, depth = 0) => {
  if (depth > 5 || value === null || value === undefined) return null
  const parsed = parseJson(value)
  if (!parsed) return null

  if (isPlainObject(parsed)) {
    if (
      parsed.protocolVersion ||
      parsed.protocol_version ||
      parsed.messageType ||
      parsed.message_type ||
      parsed.content ||
      parsed.clarification
    ) {
      return parsed
    }

    for (const key of ['response', 'data', 'outputs', 'answer', 'text', 'result', 'output']) {
      const nested = readNestedProtocol(parsed[key], depth + 1)
      if (nested) return nested
    }
  }

  return null
}

const normalizeMetrics = (value, errors) => {
  if (value === undefined || value === null) return []
  if (!Array.isArray(value)) {
    errors.push('content.metrics must be an array')
    return []
  }

  return value.slice(0, 12).map((metric, index) => {
    if (!isPlainObject(metric) || !cleanText(metric.label)) {
      errors.push(`content.metrics[${index}] is invalid`)
      return null
    }
    return {
      label: cleanText(metric.label, 120),
      value: metric.value ?? '',
      unit: cleanText(metric.unit, 40),
    }
  }).filter(Boolean)
}

const normalizeTable = (value, errors) => {
  if (value === undefined || value === null) return null
  if (!isPlainObject(value) || !Array.isArray(value.columns) || !Array.isArray(value.rows)) {
    errors.push('content.table must contain columns and rows arrays')
    return null
  }

  const columns = value.columns.slice(0, 40).map((column, index) => {
    const key = cleanText(column?.key, 120)
    const label = cleanText(column?.label, 120)
    if (!key || !label) {
      errors.push(`content.table.columns[${index}] is invalid`)
      return null
    }
    return {
      key,
      label,
      type: cleanText(column.type || 'text', 30) || 'text',
      ...(cleanText(column.unit, 40) ? { unit: cleanText(column.unit, 40) } : {}),
    }
  }).filter(Boolean)

  const rows = value.rows.slice(0, 1000).filter(isPlainObject)
  if (!columns.length) errors.push('content.table.columns is empty')

  return {
    columns,
    rows,
    total: Number.isFinite(Number(value.total)) ? Number(value.total) : rows.length,
    defaultVisibleRows: Math.max(
      1,
      Math.min(100, Number(value.defaultVisibleRows) || 10),
    ),
  }
}

const normalizeChart = (value, errors) => {
  if (value === undefined || value === null) return null
  if (!isPlainObject(value)) {
    errors.push('content.chart must be an object')
    return null
  }

  const series = Array.isArray(value.series)
    ? value.series.slice(0, 12).filter(isPlainObject).map((item) => ({
        name: cleanText(item.name, 120),
        type: cleanText(item.type || value.type || 'bar', 30) || 'bar',
        data: Array.isArray(item.data) ? item.data.slice(0, 1000) : [],
      }))
    : []

  if (!series.length) {
    errors.push('content.chart.series must be a non-empty array')
    return null
  }

  return {
    type: cleanText(value.type || 'bar', 30) || 'bar',
    title: cleanText(value.title, 200),
    xField: cleanText(value.xField, 80),
    yField: cleanText(value.yField, 80),
    categories: Array.isArray(value.categories) ? value.categories.slice(0, 1000) : [],
    series,
  }
}

const normalizeList = (value, field, maxLength = 8) => {
  if (!Array.isArray(value)) return []
  return value.slice(0, maxLength).map((item, index) => {
    if (typeof item === 'string') return { type: field === 'insights' ? 'fact' : 'evidence', text: cleanText(item) }
    if (!isPlainObject(item)) return null
    const text = cleanText(item.text || item.label || item.description)
    if (!text) return null
    return {
      ...(field === 'insights' ? { type: cleanText(item.type || 'fact', 30) || 'fact' } : {}),
      text,
    }
  }).filter(Boolean)
}

const normalizeFollowUps = (value) => {
  if (!Array.isArray(value)) return []
  return value.slice(0, 6).map((item, index) => {
    if (typeof item === 'string') {
      const text = cleanText(item, 200)
      return text ? { id: `follow-up-${index}`, label: text, query: text } : null
    }
    if (!isPlainObject(item)) return null
    const label = cleanText(item.label || item.title || item.query, 200)
    const query = cleanText(item.query || item.prompt || label, 500)
    return label && query
      ? { id: cleanText(item.id || `follow-up-${index}`, 100), label, query }
      : null
  }).filter(Boolean)
}

const normalizeSafeValue = (value, depth = 0) => {
  if (value === null || value === undefined) return value
  if (typeof value === 'string') return cleanText(value, 800)
  if (typeof value === 'number' || typeof value === 'boolean') return value
  if (depth > 3) return '[已折叠]'
  if (Array.isArray(value)) return value.slice(0, 30).map((item) => normalizeSafeValue(item, depth + 1))
  if (isPlainObject(value)) {
    return Object.entries(value).slice(0, 40).reduce((result, [key, item]) => {
      result[cleanText(key, 80)] = normalizeSafeValue(item, depth + 1)
      return result
    }, {})
  }
  return cleanText(value, 800)
}

const normalizeDataInfo = (value) => {
  if (!isPlainObject(value)) return {}
  const allowedKeys = [
    'analysisType',
    'indicatorName',
    'indicatorCode',
    'unit',
    'timeRange',
    'dataCutoffDate',
    'aggregation',
    'valueSemantics',
    'organizationScope',
    'rowCount',
    'statistics',
    'comparison',
    'sourceTables',
    'warnings',
    'validation',
  ]
  return allowedKeys.reduce((result, key) => {
    if (value[key] === undefined || value[key] === null) return result
    result[key] = normalizeSafeValue(value[key])
    return result
  }, {})
}

const normalizeClarification = (value, errors) => {
  if (!value) return null
  if (!isPlainObject(value) || !Array.isArray(value.candidates)) {
    errors.push('clarification must contain candidates')
    return null
  }

  return {
    slot: cleanText(value.slot, 60),
    title: cleanText(value.title || '请选择一个候选项', 200),
    candidates: value.candidates.slice(0, 20).map((item, index) => {
      if (!isPlainObject(item)) return null
      const id = cleanText(item.id || item.code || `candidate-${index}`, 120)
      const label = cleanText(item.label || item.name, 200)
      if (!id || !label) return null
      return { id, label, description: cleanText(item.description, 300) }
    }).filter(Boolean),
  }
}

const normalizeContent = (value, errors) => {
  if (!isPlainObject(value)) {
    errors.push('content must be an object')
    return {
      title: '', summary: '', metrics: [], table: null, chart: null,
      insights: [], evidence: [], dataInfo: {}, followUps: [],
    }
  }

  return {
    title: cleanText(value.title || value.heading, 200),
    summary: firstText(value.summary, value.answer, value.text, value.description),
    metrics: normalizeMetrics(value.metrics, errors),
    table: normalizeTable(value.table, errors),
    chart: normalizeChart(value.chart, errors),
    insights: normalizeList(value.insights, 'insights'),
    evidence: normalizeList(value.evidence, 'evidence'),
    dataInfo: normalizeDataInfo(value.dataInfo),
    followUps: normalizeFollowUps(value.followUps || value.follow_ups),
  }
}

const createLegacyResponse = (text, options = {}) => ({
  protocolVersion: DATA_QUERY_PROTOCOL_VERSION,
  requestId: options.requestId || '',
  conversationId: options.conversationId || '',
  status: text ? 'LEGACY_RESPONSE' : 'SUCCESS_EMPTY',
  messageType: text ? 'information' : 'empty',
  analysisType: '',
  content: {
    title: '',
    summary: text || '本次未获取到有效结果。',
    metrics: [],
    table: null,
    chart: null,
    insights: [],
    evidence: [],
    dataInfo: {},
    followUps: [],
  },
  clarification: null,
  meta: { legacy: true },
  protocolValid: false,
  protocolErrors: ['legacy response'],
})

export function normalizeDataQueryResponse(value, options = {}) {
  const candidate = readNestedProtocol(value)
  const fallbackText = cleanText(options.fallbackText || (typeof value === 'string' ? value : ''))
  if (!candidate) return createLegacyResponse(fallbackText, options)

  const errors = []
  const messageType = cleanText(candidate.messageType || candidate.message_type || 'analysis', 40)
  const analysisType = cleanText(candidate.analysisType || candidate.analysis_type, 50).toUpperCase()
  if (!DATA_QUERY_MESSAGE_TYPES.has(messageType)) errors.push('messageType is invalid')
  if (messageType === 'analysis' && !DATA_QUERY_ANALYSIS_TYPES.has(analysisType)) {
    errors.push('analysisType is invalid')
  }

  const content = normalizeContent(candidate.content, errors)
  const clarification = normalizeClarification(candidate.clarification, errors)
  const normalized = {
    protocolVersion: cleanText(candidate.protocolVersion || candidate.protocol_version || DATA_QUERY_PROTOCOL_VERSION, 20),
    requestId: cleanText(candidate.requestId || candidate.request_id || options.requestId, 100),
    conversationId: cleanText(candidate.conversationId || candidate.conversation_id || options.conversationId, 100),
    status: cleanText(candidate.status || (messageType === 'clarification' ? 'CLARIFICATION_REQUIRED' : 'SUCCESS_WITH_DATA'), 60),
    messageType: DATA_QUERY_MESSAGE_TYPES.has(messageType) ? messageType : 'information',
    analysisType: DATA_QUERY_ANALYSIS_TYPES.has(analysisType) ? analysisType : '',
    content,
    clarification,
    meta: isPlainObject(candidate.meta) ? candidate.meta : {},
    protocolValid:
      errors.length === 0 &&
      normalizedProtocolVersion(candidate) === DATA_QUERY_PROTOCOL_VERSION &&
      candidate.meta?.legacy !== true &&
      candidate.status !== 'LEGACY_RESPONSE',
    protocolErrors: errors,
  }

  if (!normalized.protocolValid) {
    normalized.meta = { ...normalized.meta, protocolValidation: 'PROTOCOL_VALIDATION_FAILED' }
    if (!normalized.content.summary) normalized.content.summary = fallbackText || '结果协议校验失败，请稍后重试。'
    if (!normalized.content.summary && !fallbackText) normalized.content.summary = '结果协议校验失败，请稍后重试。'
  }

  return normalized
}

const normalizedProtocolVersion = (candidate) =>
  cleanText(candidate.protocolVersion || candidate.protocol_version || '', 20)

export function createDataQueryChartOption(protocol) {
  const chart = protocol?.content?.chart
  if (!chart?.series?.length) return null

  const categories = Array.isArray(chart.categories) ? chart.categories : []
  const series = chart.series.map((item) => ({
    name: item.name,
    type: item.type || chart.type || 'bar',
    data: Array.isArray(item.data) ? item.data : [],
  }))

  return {
    title: { text: chart.title || protocol.content.title || '' },
    tooltip: { trigger: 'axis' },
    legend: series.length > 1 ? { data: series.map((item) => item.name).filter(Boolean) } : undefined,
    xAxis: { type: 'category', data: categories },
    yAxis: { type: 'value' },
    series,
  }
}

export function getDataQueryDisplayText(protocol) {
  return cleanText(protocol?.content?.summary || '')
}
