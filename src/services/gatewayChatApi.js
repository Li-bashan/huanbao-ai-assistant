import { getPortalIdentityHeaders } from '../utils/igixUser.js'

export class GatewayChatApiError extends Error {
  constructor(message, code = '') {
    super(message)
    this.name = 'GatewayChatApiError'
    this.code = code
  }
}

const gatewayBaseUrl = () =>
  String(import.meta.env.VITE_AI_GATEWAY_BASE_URL || '').replace(/\/$/, '')

const createRequestId = () =>
  globalThis.crypto?.randomUUID?.() || `chat-${Date.now()}-${Math.random().toString(16).slice(2)}`

const normalizeClientContext = (user = {}) => ({
  userId: String(user.userId || '').trim(),
  userCode: String(user.code || user.userCode || '').trim(),
  userName: String(user.name || user.userName || '').trim(),
  orgCode: String(user.orgCode || user.orgId || '').trim(),
  orgName: String(user.orgName || user.unitName || '').trim(),
  tenantId: String(user.tenantId || '').trim(),
})

const readError = async (response) => {
  try {
    const body = await response.json()
    const code = body?.code || 'GATEWAY_ERROR'
    const messages = {
      GATEWAY_CONFIG_MISSING: '智能网关未配置，请联系管理员。',
      UNAUTHENTICATED: '当前登录身份已失效，请重新登录门户。',
      IDENTITY_UNTRUSTED: '当前登录身份未通过服务端校验，请在门户环境中重试。',
      DIFY_UNAUTHORIZED: '智能服务认证失败，请联系管理员检查配置。',
      DIFY_TIMEOUT: '智能服务响应超时，请稍后重试。',
      DIFY_ERROR: '智能服务暂时不可用，请稍后重试。',
      RATE_LIMITED: '请求过于频繁，请稍后再试。',
      VALIDATION_ERROR: '请求参数不完整，请重新描述问题。',
    }
    return new GatewayChatApiError(messages[code] || body?.message || '智能服务暂时不可用，请稍后重试。', code)
  } catch {
    return new GatewayChatApiError('智能服务暂时不可用，请稍后重试。', 'GATEWAY_ERROR')
  }
}

const createBody = (question, options, mode) => ({
  query: String(question || '').trim(),
  conversationId: String(options.conversationId || '').trim(),
  requestId: options.requestId || createRequestId(),
  untrustedClientContext: normalizeClientContext(options.currentUser),
  clientContext: { assistantMode: mode },
})

const parseSse = async (response, onEvent, signal) => {
  if (!response.body) throw new GatewayChatApiError('当前浏览器不支持流式响应。', 'STREAM_UNSUPPORTED')
  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  let eventName = ''
  let dataLines = []

  const flush = () => {
    if (!dataLines.length) return
    const raw = dataLines.join('\n')
    dataLines = []
    let data = raw
    try { data = raw === '[DONE]' ? raw : JSON.parse(raw) } catch { /* ignore non-JSON SSE */ }
    onEvent(eventName || 'message', data)
    eventName = ''
  }

  while (true) {
    if (signal?.aborted) throw new GatewayChatApiError('当前执行已停止。', 'CHAT_CANCELLED')
    const { value, done } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const lines = buffer.split(/\r?\n/)
    buffer = lines.pop() || ''
    lines.forEach((line) => {
      if (!line) return flush()
      if (line.startsWith(':')) return
      if (line.startsWith('event:')) eventName = line.slice(6).trim()
      if (line.startsWith('data:')) dataLines.push(line.slice(5).trimStart())
    })
  }
  buffer += decoder.decode()
  if (buffer || dataLines.length) {
    if (buffer.startsWith('data:')) dataLines.push(buffer.slice(5).trimStart())
    flush()
  }
}

const mergeText = (current, incoming) => {
  const previous = String(current || '')
  const next = String(incoming || '')
  if (!next) return previous
  if (!previous || next.startsWith(previous) || previous.startsWith(next)) return next.length > previous.length ? next : previous
  return previous + next
}

const buildSseResult = (answer, responseConversationId, requestId, messageId, options, suggestedActions = []) => ({
  answer: answer || '当前未获取到有效回答，请稍后重试。',
  // This is the local conversation key. Dify conversation IDs never leave the Gateway.
  conversationId: responseConversationId,
  messageId,
  requestId,
  sources: [],
  noHit: false,
  suggestedActions,
})

export async function sendGatewayPolicyMessage(question, options = {}) {
  const baseUrl = gatewayBaseUrl()
  if (!baseUrl) throw new GatewayChatApiError('智能网关未配置，请联系管理员。', 'GATEWAY_CONFIG_MISSING')
  const body = createBody(question, options, 'policy')
  let response
  try {
    response = await fetch(`${baseUrl}/api/ai/policy/chat`, {
      method: 'POST',
      signal: options.signal,
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        ...getPortalIdentityHeaders(options.currentUser),
      },
      body: JSON.stringify(body),
    })
  } catch (error) {
    if (error?.name === 'AbortError' || options.signal?.aborted) throw new GatewayChatApiError('当前执行已停止。', 'CHAT_CANCELLED')
    throw new GatewayChatApiError('网络连接失败，请稍后重试。', 'NETWORK_ERROR')
  }
  if (!response.ok) throw await readError(response)
  const bodyResponse = await response.json()
  const data = bodyResponse?.data || {}
  return {
    answer: data.answer || '当前未获取到有效回答，请稍后重试。',
    conversationId: data.conversationId || data.clientConversationId || body.conversationId,
    messageId: data.messageId || '',
    requestId: data.requestId || body.requestId,
    sources: data.retrieverResources || data.sources || [],
    noHit: false,
  }
}

export async function streamGatewayOfficeMessage(question, options = {}) {
  const baseUrl = gatewayBaseUrl()
  if (!baseUrl) throw new GatewayChatApiError('智能网关未配置，请联系管理员。', 'GATEWAY_CONFIG_MISSING')
  const body = createBody(question, options, 'office-ai')
  let response
  try {
    response = await fetch(`${baseUrl}/api/ai/office/chat`, {
      method: 'POST',
      signal: options.signal,
      headers: {
        Accept: 'text/event-stream',
        'Content-Type': 'application/json',
        ...getPortalIdentityHeaders(options.currentUser),
      },
      body: JSON.stringify(body),
    })
  } catch (error) {
    if (error?.name === 'AbortError' || options.signal?.aborted) throw new GatewayChatApiError('当前执行已停止。', 'CHAT_CANCELLED')
    throw new GatewayChatApiError('网络连接失败，请稍后重试。', 'NETWORK_ERROR')
  }
  if (!response.ok) throw await readError(response)

  const requestId = response.headers.get('X-Request-Id') || body.requestId
  let answer = ''
  let messageId = ''
  let responseConversationId = body.conversationId
  let suggestedActions = []

  await parseSse(response, (eventName, payload) => {
    if (payload === '[DONE]') return
    const data = payload && typeof payload === 'object' ? payload : { text: String(payload || '') }
    options.onTask?.(data.taskId || data.task_id || '')
    if (data.requestId) options.onRequestId?.(data.requestId)
    responseConversationId = data.conversationId || data.conversation_id || responseConversationId
    messageId = data.messageId || data.message_id || messageId

    if (eventName === 'dify_event') {
      options.onDifyEvent?.(data.data || data)
      return
    }
    if (eventName === 'analysis_started') {
      options.onStatus?.('环宝正在生成中...')
      return
    }
    if (eventName === 'text_delta' || eventName === 'message' || eventName === 'agent_message' || eventName === 'text_chunk') {
      answer = mergeText(answer, data.delta || data.answer || data.text || data.data?.answer || data.data?.text)
      if (answer) options.onMessage?.(answer, { replace: true })
      return
    }
    if (eventName === 'message_replace' || eventName === 'text_replace') {
      answer = String(data.answer || data.text || data.data?.answer || data.data?.text || '')
      if (answer) options.onMessage?.(answer, { replace: true })
      return
    }
    if (eventName === 'analysis_result') {
      answer = String(data.answer || data.text || data.response || data.data?.answer || data.data?.text || answer)
      if (Array.isArray(data.suggestedActions)) suggestedActions = data.suggestedActions
      if (answer) options.onMessage?.(answer, { replace: true })
      return
    }
    if (eventName === 'error') throw new GatewayChatApiError(data.message || '办公智能分析失败，请稍后重试。', data.code || 'DIFY_STREAM_ERROR')
  }, options.signal)

  const result = buildSseResult(answer, responseConversationId, requestId, messageId, options, suggestedActions)
  options.onComplete?.(result)
  return result
}
