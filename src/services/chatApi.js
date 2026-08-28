import {
  createDataQueryChartOptionFromAnswer,
  extractDataQueryChartOption,
  removeDataQueryChartPayload,
} from '../utils/dataQueryChart.js'

function createChatAbortError() {
  const error = new Error('当前执行已停止。')
  error.name = 'AbortError'
  error.code = 'CHAT_CANCELLED'
  return error
}

function waitWithSignal(milliseconds, signal) {
  if (signal?.aborted) return Promise.reject(createChatAbortError())

  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      signal?.removeEventListener('abort', handleAbort)
      resolve()
    }, milliseconds)

    const handleAbort = () => {
      clearTimeout(timer)
      signal?.removeEventListener('abort', handleAbort)
      reject(createChatAbortError())
    }

    signal?.addEventListener('abort', handleAbort, { once: true })
  })
}

function normalizeSources(resources = []) {
  if (!Array.isArray(resources)) return []

  const sourceMap = new Map()

  resources.forEach((item, index) => {
    const documentId = item.document_id || ''
    const documentName = item.document_name || ''
    const segmentId = item.segment_id || ''
    const segmentPosition = item.segment_position || item.position || index + 1
    const key =
      segmentId ||
      [documentId, segmentPosition].filter(Boolean).join(':') ||
      [documentName, segmentPosition].filter(Boolean).join(':') ||
      String(index)

    if (!key) return

    const source = {
      id: key,
      datasetName: item.dataset_name || '',
      documentName,
      content: item.content || '',
      score: typeof item.score === 'number' ? item.score : 0,
      position: segmentPosition,
    }

    const existing = sourceMap.get(key)

    if (!existing) {
      sourceMap.set(key, source)
      return
    }

    const existingScore = typeof existing.score === 'number' ? existing.score : 0
    const currentScore = typeof source.score === 'number' ? source.score : 0

    if (currentScore > existingScore) {
      sourceMap.set(key, source)
    }
  })

  return Array.from(sourceMap.values()).slice(0, 3)
}

function logPolicyRetrieverDebug(answer = '', resources = []) {
  if (!import.meta.env.DEV) return

  console.groupCollapsed('[Dify Debug] policy blocking retriever resources')
  console.log('answer', answer || '')
  console.log('retriever_resources.length', Array.isArray(resources) ? resources.length : 0)
  ;(Array.isArray(resources) ? resources : []).forEach((item, index) => {
    console.log(`retriever_resource[${index}]`, {
      document_name: item.document_name || '',
      content: item.content || '',
      score: item.score,
      segment_position: item.segment_position,
      dataset_name: item.dataset_name || '',
      metadata: item.metadata || {},
    })
  })
  console.groupEnd()
}

function removeThinkContent(text = '') {
  return String(text || '')
    .replace(/<think>[\s\S]*?<\/think>/gi, '')
    .replace(/<think>[\s\S]*$/gi, '')
    .replace(/<\/think>/gi, '')
    .trim()
}

function hasEffectiveSources(resources = []) {
  if (!Array.isArray(resources)) return false

  return resources.some(
    (item) =>
      item?.document_id ||
      item?.document_name ||
      item?.segment_id ||
      item?.content,
  )
}

function normalizeAnswer(answer = '', resources = []) {
  const text = removeThinkContent(answer)
  const fallbackAnswer = '当前知识库中未查询到相关制度依据。建议您换一种表述继续查询，或联系相关责任部门确认。'
  const hasSources = hasEffectiveSources(resources)

  if (!text) {
    return {
      answer: fallbackAnswer,
      noHit: !hasSources,
    }
  }

  if (hasSources) {
    return {
      answer: text,
      noHit: false,
    }
  }

  const compactText = text.replace(/\s+/g, '')
  const explicitNoHitPatterns = [
    '当前知识库中未查询到相关制度依据',
    '当前知识库中未查询到相关内容',
    '当前知识库资料未明确',
    '当前制度资料未明确',
    '未查询到相关制度依据',
    '未查询到相关内容',
    '未找到相关制度依据',
    '未找到相关内容',
    '没有找到相关制度依据',
    '没有找到相关内容',
  ]

  const isExplicitNoHit = explicitNoHitPatterns.some((pattern) =>
    compactText.includes(pattern),
  )

  if (isExplicitNoHit) {
    return {
      answer: fallbackAnswer,
      noHit: true,
    }
  }

  return {
    answer: text,
    noHit: false,
  }
}

function isOfficeMode(modeKey) {
  return ['office', 'office-ai', 'general'].includes(modeKey)
}

function mergeStreamAnswer(current = '', incoming = '') {
  const previous = String(current || '')
  const next = String(incoming || '')
  if (!next) return previous
  if (!previous) return next

  // Dify 不同应用可能发送增量文本，也可能发送截至当前的累计文本。
  if (next.length > previous.length && next.startsWith(previous)) return next
  if (previous.length > next.length && previous.startsWith(next)) return previous

  return `${previous}${next}`
}

function getErrorMessage(status, fallback = '当前服务暂时不可用，请稍后重试。') {
  if (status === 401) return '智能问数服务认证失败，请联系管理员检查配置。'
  if (status === 403) return '智能问数服务暂时无法完成请求，请稍后重试。'
  if (status === 404) return '智能问数服务接口暂不可用，请联系管理员。'
  if (status >= 500) return '智能问数服务暂时不可用，请稍后重试。'
  return fallback
}

const RETRYABLE_DIFY_STATUS = new Set([408, 425, 429, 500, 502, 503, 504])

async function fetchDifyWithRetry(url, init, signal, retries = 2) {
  for (let attempt = 0; attempt <= retries; attempt += 1) {
    try {
      const response = await fetch(url, init)
      if (!RETRYABLE_DIFY_STATUS.has(response.status) || attempt === retries) {
        return response
      }

      await response.body?.cancel().catch(() => {})
    } catch (error) {
      if (signal?.aborted || error?.name === 'AbortError' || attempt === retries) {
        throw error
      }
    }

    await waitWithSignal(250 * 2 ** attempt, signal)
  }

  throw new Error('Dify 请求失败。')
}

function getWorkflowAnswer(data) {
  const outputs = data?.data?.outputs || data?.outputs || {}
  const answer = outputs.answer || outputs.text || outputs.result || outputs.response || outputs.output

  if (typeof answer === 'string' && answer.trim()) return answer.trim()
  if (answer !== undefined && answer !== null) return JSON.stringify(answer, null, 2)

  const fallback = data?.answer || data?.data?.answer
  if (typeof fallback === 'string' && fallback.trim()) return fallback.trim()
  return ''
}

function findWorkflowErrorCode(value, depth = 0) {
  if (depth > 5 || value === null || value === undefined) return ''
  if (typeof value === 'string') {
    return value.includes('DATA_QUERY_NOT_COVERED') ? 'DATA_QUERY_NOT_COVERED' : ''
  }
  if (Array.isArray(value)) {
    return value.map((item) => findWorkflowErrorCode(item, depth + 1)).find(Boolean) || ''
  }
  if (typeof value === 'object') {
    if (value.code === 'DATA_QUERY_NOT_COVERED') return value.code
    return Object.values(value)
      .map((item) => findWorkflowErrorCode(item, depth + 1))
      .find(Boolean) || ''
  }
  return ''
}

function getDifyConfig(options = {}) {
  const modeKey = options.modeKey || 'policy'

  if (modeKey === 'policy') {
    return {
      apiBase: import.meta.env.VITE_POLICY_DIFY_API_BASE || import.meta.env.VITE_DIFY_API_BASE,
      apiKey: import.meta.env.VITE_POLICY_DIFY_API_KEY || import.meta.env.VITE_DIFY_API_KEY,
      modeName: '制度问答',
    }
  }

  if (modeKey === 'data-query') {
    return {
      apiBase: import.meta.env.VITE_DATA_QUERY_DIFY_API_BASE,
      apiKey: import.meta.env.VITE_DATA_QUERY_DIFY_API_KEY,
      modeName: '智能问数',
    }
  }

  if (isOfficeMode(modeKey)) {
    return {
      apiBase: import.meta.env.VITE_OFFICE_DIFY_API_BASE,
      apiKey: import.meta.env.VITE_OFFICE_DIFY_API_KEY,
      modeName: '办公智能',
    }
  }

  return null
}

async function sendMockMessage(question, options = {}) {
  await waitWithSignal(600, options.signal)

  const mockAnswer =
    options.modeKey === 'workflow'
      ? `我已收到您的流程办理需求：“${question}”。该能力后续将接入业务系统菜单、表单和流程发起工具。`
      : isOfficeMode(options.modeKey)
        ? `我已收到您的办公处理需求：“${question}”。该能力后续将接入办公智能工作流，可支持会议纪要、材料润色、工作总结等场景。`
        : `我已收到您的问题：“${question}”。后续这里会接入 Dify 智能体接口。`

  return {
    answer: mockAnswer,
    conversationId: options.conversationId || 'mock-conversation-001',
    messageId: '',
    sources: [],
  }
}

export async function sendChatMessage(question, options = {}) {
  const useDify = import.meta.env.VITE_USE_DIFY === 'true'
  const apiMode = options.apiMode || 'dify'

  if (!useDify || apiMode !== 'dify') {
    return sendMockMessage(question, options)
  }

  const difyConfig = getDifyConfig(options)

  if (!difyConfig) {
    return sendMockMessage(question, options)
  }

  const { apiBase, apiKey, modeName } = difyConfig
  const user = import.meta.env.VITE_DIFY_USER || 'huanbao-web-user'

  if (!apiBase || !apiKey) {
    throw new Error(`${modeName} Dify API 配置缺失，请检查 .env.local`)
  }

  const response = await fetchDifyWithRetry(`${apiBase.replace(/\/$/, '')}/chat-messages`, {
    method: 'POST',
    signal: options.signal,
    headers: {
      Authorization: `Bearer ${apiKey}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      inputs: {},
      query: question,
      response_mode: 'blocking',
      conversation_id: options.conversationId || '',
      user,
    }),
  }, options.signal)

  if (!response.ok) {
    const errorText = await response.text().catch(() => '')
    throw new Error(getErrorMessage(response.status, `Dify 请求失败：${response.status} ${errorText}`))
  }

  const data = await response.json()
  const rawSources = data.metadata?.retriever_resources || []
  logPolicyRetrieverDebug(data.answer, rawSources)
  const normalizedAnswer = normalizeAnswer(data.answer, rawSources)

  return {
    answer: normalizedAnswer.answer,
    conversationId: data.conversation_id || options.conversationId || '',
    messageId: data.message_id || '',
    sources: normalizedAnswer.noHit ? [] : normalizeSources(rawSources),
    noHit: normalizedAnswer.noHit,
  }
}

export async function streamChatMessage(question, options = {}) {
  const useDify = import.meta.env.VITE_USE_DIFY === 'true'

  if (!useDify) {
    const mockResult = await sendMockMessage(question, options)
    options.onMessage?.(mockResult.answer)
    options.onComplete?.(mockResult)
    return mockResult
  }

  const difyConfig = getDifyConfig(options)

  if (!difyConfig) {
    const mockResult = await sendMockMessage(question, options)
    options.onMessage?.(mockResult.answer)
    options.onComplete?.(mockResult)
    return mockResult
  }

  const { apiBase, apiKey, modeName } = difyConfig
  const user = import.meta.env.VITE_DIFY_USER || 'huanbao-web-user'

  if (!apiBase || !apiKey) {
    throw new Error(`${modeName} Dify API 配置缺失，请检查 .env.local`)
  }

  try {
    const response = await fetch(`${apiBase.replace(/\/$/, '')}/chat-messages`, {
      method: 'POST',
      signal: options.signal,
      headers: {
        Authorization: `Bearer ${apiKey}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        inputs:
          options.modeKey === 'data-query'
            ? { current_user_name: options.currentUserName || '' }
            : {},
        query: question,
        response_mode: 'streaming',
        conversation_id: options.conversationId || '',
        user,
      }),
    })

    if (!response.ok) {
      const errorText = await response.text().catch(() => '')
      throw new Error(`Dify streaming 请求失败：${response.status} ${errorText}`)
    }

    if (!response.body) {
      throw new Error('当前浏览器不支持流式响应。')
    }

    const reader = response.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''
    let fullAnswerRaw = ''
    let conversationId = options.conversationId || ''
    let messageId = ''
    let chartOption = null
    let lastAnswerEvent = null

    const getVisibleAnswer = (value) => {
      const answer = removeThinkContent(value)
      return options.modeKey === 'data-query' ? removeDataQueryChartPayload(answer) : answer
    }

    const updateChartOption = (value) => {
      const nextOption = extractDataQueryChartOption(value)
      if (!nextOption) return
      chartOption = nextOption
      options.onChart?.(nextOption)
    }

    const handleEvent = (eventData) => {
      if (!eventData || eventData === '[DONE]') return

      const data = JSON.parse(eventData)

      const isWorkflowEvent = [
        'workflow_started',
        'node_started',
        'node_finished',
        'workflow_finished',
      ].includes(data.event)
      if (options.modeKey === 'data-query' && isWorkflowEvent) {
        options.onWorkflowEvent?.({ event: data.event, data: data.data || data })
      }

      if (data.event === 'message' || data.event === 'agent_message') {
        const deltaText = data.answer || ''
        if (deltaText) {
          const isRepeatedAcrossEventTypes =
            lastAnswerEvent &&
            lastAnswerEvent.event !== data.event &&
            lastAnswerEvent.text === deltaText

          if (!isRepeatedAcrossEventTypes) {
            fullAnswerRaw = mergeStreamAnswer(fullAnswerRaw, deltaText)
          }
          lastAnswerEvent = { event: data.event, text: deltaText }
          if (options.modeKey === 'data-query') updateChartOption(fullAnswerRaw)
          options.onMessage?.(getVisibleAnswer(fullAnswerRaw), { replace: true })
        }
        return
      }

      if (options.modeKey === 'data-query') {
        if (data.event === 'workflow_started') {
          options.onStatus?.('已连接智能问数服务，正在解析查询条件...')
          return
        }

        if (data.event === 'node_started') {
          options.onStatus?.('正在处理生产指标查询...')
          return
        }

        if (data.event === 'node_finished') {
          options.onStatus?.('查询步骤已完成，正在继续汇总...')
          return
        }

        if (data.event === 'workflow_finished') {
          updateChartOption(data.data?.outputs || data.outputs)
          options.onStatus?.('正在整理查询结果...')
          return
        }
      }

      if (data.event === 'message_end') {
        conversationId = data.conversation_id || conversationId
        messageId = data.message_id || messageId
        if (options.modeKey === 'data-query') {
          options.onWorkflowEvent?.({ event: data.event, data })
        }
        return
      }

      if (data.event === 'error') {
        throw new Error(data.message || 'Dify streaming error')
      }
    }

    while (true) {
      const { value, done } = await reader.read()
      if (done) break

      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split(/\r?\n/)
      buffer = lines.pop() || ''

      lines.forEach((line) => {
        const trimmedLine = line.trim()
        if (!trimmedLine.startsWith('data:')) return
        handleEvent(trimmedLine.replace(/^data:\s*/, ''))
      })
    }

    buffer
      .split(/\r?\n/)
      .map((line) => line.trim())
      .filter((line) => line.startsWith('data:'))
      .forEach((line) => {
        handleEvent(line.replace(/^data:\s*/, ''))
      })

    if (options.modeKey === 'data-query') updateChartOption(fullAnswerRaw)

    const result = {
      answer:
        getVisibleAnswer(fullAnswerRaw) ||
        (chartOption ? '查询结果已整理如下。' : '当前未获取到有效回答，请稍后重试。'),
      conversationId,
      messageId,
      sources: [],
      noHit: false,
      chartOption,
    }

    options.onComplete?.(result)
    return result
  } catch (error) {
    options.onError?.(error)
    throw error
  }
}

export async function runDataQueryWorkflow(question, options = {}) {
  if (import.meta.env.VITE_USE_DIFY !== 'true') {
    throw new Error('智能问数服务未启用。')
  }

  const apiBase = import.meta.env.VITE_DATA_QUERY_DIFY_API_BASE
  const apiKey = import.meta.env.VITE_DATA_QUERY_DIFY_API_KEY
  const user = import.meta.env.VITE_DIFY_USER || 'huanbao-web-user'

  if (!apiBase || !apiKey) {
    throw new Error('智能问数 Dify API 配置缺失，请联系管理员检查配置。')
  }

  let response
  try {
    response = await fetch(`${apiBase.replace(/\/$/, '')}/chat-messages`, {
      method: 'POST',
      signal: options.signal,
      headers: {
        Authorization: `Bearer ${apiKey}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        inputs: {
          current_user_name: options.currentUserName || '',
        },
        query: question,
        response_mode: 'blocking',
        conversation_id: options.conversationId || '',
        user,
      }),
    })
  } catch (error) {
    if (error?.name === 'AbortError' || error?.code === 'CHAT_CANCELLED') throw error
    throw new Error('网络连接失败，请稍后重试。')
  }

  if (!response.ok) {
    throw new Error(getErrorMessage(response.status))
  }

  let data
  try {
    data = await response.json()
  } catch {
    throw new Error('智能问数返回格式异常，请稍后重试。')
  }

  const workflowErrorCode = findWorkflowErrorCode(data)
  if (workflowErrorCode === 'DATA_QUERY_NOT_COVERED') {
    const error = new Error('您所在部门暂不支持生产指标智能问数，如有业务需要，请联系管理员申请。')
    error.code = workflowErrorCode
    throw error
  }

  if (data?.status === 'failed' || data?.data?.status === 'failed') {
    throw new Error('智能问数应用执行失败，请稍后重试。')
  }

  const rawAnswer = getWorkflowAnswer(data)
  const answer = removeDataQueryChartPayload(rawAnswer)
  const chartOption = extractDataQueryChartOption(data) || createDataQueryChartOptionFromAnswer(rawAnswer)
  if (!answer && !chartOption) {
    throw new Error('本次未查询到有效结果，请换一种生产指标或时间范围试试。')
  }

  return {
    answer: answer || '查询结果已整理如下。',
    conversationId: data?.conversation_id || options.conversationId || '',
    messageId: data?.message_id || data?.task_id || data?.workflow_run_id || '',
    sources: [],
    noHit: false,
    chartOption,
    data: data?.data?.outputs || data?.outputs || null,
  }
}
