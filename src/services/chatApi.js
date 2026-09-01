import { streamDataQueryMessage } from './dataQueryApi.js'
import { sendGatewayPolicyMessage, streamGatewayOfficeMessage } from './gatewayChatApi.js'

function createChatAbortError() {
  const error = new Error('当前执行已停止。')
  error.name = 'AbortError'
  error.code = 'CHAT_CANCELLED'
  return error
}

function waitWithSignal(milliseconds, signal) {
  if (signal?.aborted) return Promise.reject(createChatAbortError())
  return new Promise((resolve, reject) => {
    const timer = setTimeout(resolve, milliseconds)
    const abort = () => {
      clearTimeout(timer)
      reject(createChatAbortError())
    }
    signal?.addEventListener('abort', abort, { once: true })
  })
}

function isOfficeMode(modeKey) {
  return ['office', 'office-ai', 'general'].includes(modeKey)
}

async function sendMockMessage(question, options = {}) {
  await waitWithSignal(600, options.signal)
  const answer = options.modeKey === 'workflow'
    ? `我已收到您的流程办理需求：${question}。该能力后续将接入业务系统菜单、表单和流程发起工具。`
    : `我已收到您的问题：${question}。`
  return {
    answer,
    conversationId: options.clientConversationId || options.conversationId || 'mock-conversation-001',
    messageId: '',
    sources: [],
  }
}

export async function stopChatMessage() {
  // AbortController closes the Gateway SSE request. No Dify task id or key is
  // exposed to the browser, so there is no direct Dify stop call here.
  return false
}

export async function sendChatMessage(question, options = {}) {
  if (options.modeKey === 'data-query') return streamDataQueryMessage(question, options)
  if (options.modeKey === 'policy') return sendGatewayPolicyMessage(question, options)
  if (isOfficeMode(options.modeKey)) return streamGatewayOfficeMessage(question, options)
  return sendMockMessage(question, options)
}

export async function streamChatMessage(question, options = {}) {
  const result = await sendChatMessage(question, options)
  if (result?.answer && options.modeKey !== 'data-query' && !isOfficeMode(options.modeKey)) {
    options.onMessage?.(result.answer)
    options.onComplete?.(result)
  }
  return result
}

export async function sendMasterChatMessage(question, options = {}) {
  return streamChatMessage(question, options)
}

export async function runDataQueryWorkflow(question, options = {}) {
  return streamDataQueryMessage(question, options)
}
