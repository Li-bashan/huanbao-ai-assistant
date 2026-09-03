import {
  getDataQueryDisplayText,
  normalizeDataQueryResponse,
} from '../utils/dataQueryProtocol.js'
import {
  extractDataQueryAnalysisMetadata,
  stripDataQueryAnalysisMetadata,
  toDataQueryProtocolChart,
} from '../utils/dataQueryAnalysis.js'
import { getPortalIdentityHeaders } from '../utils/igixUser.js'

export class DataQueryApiError extends Error {
  constructor(message, code = '') {
    super(message)
    this.name = 'DataQueryApiError'
    this.code = code
  }
}

const getGatewayBaseUrl = () =>
  String(import.meta.env.VITE_AI_GATEWAY_BASE_URL || '').replace(/\/$/, '')

const createRequestId = () => {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID()
  return `data-query-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

const normalizeUserContext = (user = {}) => ({
  userId: String(user.userId || '').trim(),
  userCode: String(user.code || user.userCode || '').trim(),
  userName: String(user.name || user.userName || '').trim(),
  orgCode: String(user.orgCode || user.orgId || '').trim(),
  orgName: String(user.orgName || user.unitName || '').trim(),
  tenantId: String(user.tenantId || '').trim(),
})

const mergeStreamText = (current = '', incoming = '') => {
  const previous = String(current || '')
  const next = String(incoming || '')
  if (!next) return previous
  if (!previous) return next
  if (next.startsWith(previous)) return next
  if (previous.startsWith(next)) return previous
  return `${previous}${next}`
}

const readErrorBody = async (response) => {
  try {
    return await response.json()
  } catch {
    return null
  }
}

const toApiError = (body, status, fallback = '智能问数服务暂时不可用，请稍后重试。') => {
  const code = body?.code || (status === 401 ? 'IDENTITY_UNVERIFIED' : 'GATEWAY_ERROR')
  const messages = {
    GATEWAY_CONFIG_MISSING: '智能问数网关未配置，请联系管理员。',
    CURRENT_USER_MISSING: '暂未获取到当前登录信息，请在门户环境中重试。',
    IDENTITY_UNVERIFIED: '当前登录身份未通过服务端校验，请在门户环境中重试。',
    IDENTITY_CONFIG_MISSING: '智能问数身份校验未配置，请联系管理员。',
    DATA_QUERY_NOT_COVERED: '您所在部门暂不支持生产指标智能问数，如有业务需要，请联系管理员申请。',
    ACCESS_DENIED: '当前账号没有智能问数权限。',
    DIFY_UNAUTHORIZED: '智能问数服务认证失败，请联系管理员检查配置。',
    DIFY_TIMEOUT: '智能问数服务响应超时，请稍后重试。',
    DIFY_ERROR: '智能问数分析服务暂时不可用，请稍后重试。',
    VALIDATION_ERROR: '智能问数请求参数不完整，请重新描述问题。',
    RATE_LIMITED: '请求过于频繁，请稍后再试。',
  }
  return new DataQueryApiError(messages[code] || body?.message || fallback, code)
}

const mapStageMessage = (stage) => {
  const messages = {
    understanding: '正在理解问题...',
    planning: '正在确定分析范围...',
    querying: '正在查询经营数据...',
    validating: '正在校验查询结果...',
    presenting: '正在整理分析结果...',
  }
  return messages[String(stage || '').toLowerCase()] || '正在分析经营数据...'
}

const normalizeAnalysisResponse = (candidate, options = {}) => {
  const metadata = extractDataQueryAnalysisMetadata(candidate)
  const normalized = normalizeDataQueryResponse(candidate, {
    ...options,
    fallbackText: metadata.text || stripDataQueryAnalysisMetadata(options.fallbackText || ''),
  })

  if (metadata.text && (!normalized.protocolValid || !normalized.content.summary)) {
    normalized.content.summary = metadata.text
  }
  if (metadata.analysisState) {
    normalized.meta = { ...normalized.meta, analysisState: metadata.analysisState }
  }
  if (metadata.chartOption && !normalized.content.chart) {
    normalized.content.chart = toDataQueryProtocolChart(metadata.chartOption)
  }
  if (metadata.chartOption) {
    normalized.meta = { ...normalized.meta, chartOption: metadata.chartOption }
  }
  if (metadata.followUps.length && !normalized.content.followUps.length) {
    normalized.content.followUps = metadata.followUps
  }

  return normalized
}

const parseSseEvents = async (response, onEvent, signal) => {
  if (!response.body) throw new DataQueryApiError('当前浏览器不支持流式响应。', 'STREAM_UNSUPPORTED')

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  let eventName = ''
  let dataLines = []

  const flush = () => {
    if (!dataLines.length) return
    const raw = dataLines.join('\n')
    dataLines = []
    const parsed = raw === '[DONE]' ? raw : (() => {
      try { return JSON.parse(raw) } catch { return raw }
    })()
    onEvent(eventName || 'message', parsed)
    eventName = ''
  }

  const consumeLines = (chunk) => {
    buffer += chunk
    const lines = buffer.split(/\r?\n/)
    buffer = lines.pop() || ''

    lines.forEach((line) => {
      if (line === '') {
        flush()
        return
      }
      if (line.startsWith(':')) return
      if (line.startsWith('event:')) {
        eventName = line.slice(6).trim()
        return
      }
      if (line.startsWith('data:')) dataLines.push(line.slice(5).trimStart())
    })
  }

  while (true) {
    if (signal?.aborted) throw new DataQueryApiError('当前执行已停止。', 'CHAT_CANCELLED')
    const { value, done } = await reader.read()
    if (done) break
    consumeLines(decoder.decode(value, { stream: true }))
  }

  consumeLines(decoder.decode())
  if (buffer.trim() || dataLines.length) flush()
}

export async function streamDataQueryMessage(question, options = {}) {
  const baseUrl = getGatewayBaseUrl()
  if (!baseUrl) {
    throw new DataQueryApiError('智能问数网关未配置，请联系管理员。', 'GATEWAY_CONFIG_MISSING')
  }

  const clientRequestId = options.requestId || createRequestId()
  let requestId = clientRequestId
  const conversationId = String(options.conversationId || '').trim()
  const body = {
    query: String(question || '').trim(),
    conversationId,
    requestId: clientRequestId,
    // Gateway 在 SIGNED_HEADER 模式只信任签名身份；BODY_TRIAL 仅用于明确标记的试点环境。
    // userContext is accepted for the explicit BODY_TRIAL compatibility mode;
    // SIGNED_HEADER production authorization ignores it.
    untrustedClientContext: normalizeUserContext(options.currentUser),
    clientContext: {
      assistantMode: 'data-query',
      timezone: options.timezone || Intl.DateTimeFormat().resolvedOptions().timeZone || 'Asia/Shanghai',
      ...(options.analysisState ? { analysisState: options.analysisState } : {}),
    },
    ...(options.clarification ? { clarification: options.clarification } : {}),
  }

  if (!body.query) throw new DataQueryApiError('查询内容不能为空。', 'VALIDATION_ERROR')

  let response
  try {
    response = await fetch(`${baseUrl}/api/ai/data-query/chat`, {
      method: 'POST',
      signal: options.signal,
      headers: {
        Accept: 'text/event-stream',
        'Content-Type': 'application/json',
        'X-Request-Id': clientRequestId,
        ...getPortalIdentityHeaders(options.currentUser),
      },
      body: JSON.stringify(body),
    })
  } catch (error) {
    if (error?.name === 'AbortError' || options.signal?.aborted) {
      throw new DataQueryApiError('当前执行已停止。', 'CHAT_CANCELLED')
    }
    throw new DataQueryApiError('网络连接失败，请稍后重试。', 'NETWORK_ERROR')
  }

  if (!response.ok) {
    throw toApiError(await readErrorBody(response), response.status)
  }

  requestId = response.headers.get('X-Request-Id') || requestId
  let latestResponse = null
  let streamedText = ''
  let responseConversationId = conversationId
  let messageId = ''
  let taskId = ''

  await parseSseEvents(response, (eventName, data) => {
    if (data === '[DONE]') return
    const payload = data && typeof data === 'object' ? data : { text: String(data || '') }
    requestId = payload.requestId || payload.request_id || requestId
    taskId = payload.taskId || payload.task_id || taskId
    responseConversationId = payload.conversationId || payload.conversation_id || responseConversationId
    messageId = payload.messageId || payload.message_id || messageId

    if (eventName === 'stage') {
      const stageMessage = String(payload.message || mapStageMessage(payload.stage)).trim()
      options.onStage?.({ ...payload, message: stageMessage })
      options.onStatus?.(stageMessage)
      return
    }

    if (eventName === 'analysis_started') {
      const stageMessage = mapStageMessage(payload.stage)
      options.onStage?.({ ...payload, message: stageMessage })
      options.onStatus?.(stageMessage)
      return
    }

    if (eventName === 'text_delta') {
      const delta = String(payload.delta || payload.text || '')
      if (delta) {
        streamedText += delta
        options.onMessage?.(streamedText, { replace: true })
      }
      return
    }

    if (eventName === 'analysis_result' || eventName === 'clarification') {
      const candidate = payload.response || payload.data || payload
      latestResponse = normalizeAnalysisResponse(candidate, {
        requestId,
        conversationId: responseConversationId,
        fallbackText: streamedText,
      })
      responseConversationId = latestResponse.conversationId || responseConversationId
      options.onAnalysisResult?.(latestResponse)
      options.onMessage?.(getDataQueryDisplayText(latestResponse), { replace: true })
      return
    }

    if (eventName === 'dify_event') {
      options.onDifyEvent?.(payload.data || payload)
      return
    }

    if (eventName === 'message' || eventName === 'agent_message' || eventName === 'text_chunk') {
      const text = payload.delta || payload.answer || payload.text || payload.data?.answer || payload.data?.text || ''
      streamedText = mergeStreamText(streamedText, text)
      if (text) options.onMessage?.(streamedText, { replace: true })
      return
    }

    if (eventName === 'message_replace' || eventName === 'text_replace') {
      streamedText = String(payload.answer || payload.text || payload.data?.answer || payload.data?.text || '')
      if (streamedText) options.onMessage?.(streamedText, { replace: true })
      return
    }

    if (eventName === 'message_end') {
      const candidate = payload.response || payload.answer || payload.data?.outputs || payload.data?.answer || payload.data || payload
      latestResponse = normalizeAnalysisResponse(candidate, {
        requestId,
        conversationId: responseConversationId,
        fallbackText: streamedText,
      })
      responseConversationId = latestResponse.conversationId || responseConversationId
      options.onAnalysisResult?.(latestResponse)
      options.onMessage?.(getDataQueryDisplayText(latestResponse), { replace: true })
      return
    }

    if (eventName === 'completed') {
      responseConversationId = payload.conversationId || responseConversationId
      return
    }

    if (eventName === 'error') {
      throw toApiError(payload, 500, '智能问数分析失败，请稍后重试。')
    }
  }, options.signal)

  if (!latestResponse) {
    latestResponse = normalizeAnalysisResponse(null, {
      requestId,
      conversationId: responseConversationId,
      fallbackText: streamedText,
    })
  }

  const result = {
    answer: getDataQueryDisplayText(latestResponse),
    conversationId: latestResponse.conversationId || responseConversationId,
    messageId,
    requestId,
    protocol: latestResponse,
    analysisState: latestResponse.meta?.analysisState || null,
    chartOption: latestResponse.meta?.chartOption || null,
    suggestedActions: latestResponse.content?.followUps || [],
    sources: [],
    noHit: latestResponse.status === 'SUCCESS_EMPTY',
    taskId,
  }
  options.onComplete?.(result)
  return result
}
