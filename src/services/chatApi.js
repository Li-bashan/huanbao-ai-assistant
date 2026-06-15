function normalizeSources(resources = []) {
  if (!Array.isArray(resources)) return []

  const sourceMap = new Map()

  resources.forEach((item, index) => {
    const documentId = item.document_id || ''
    const documentName = item.document_name || ''
    const key = documentId || documentName || item.segment_id || String(index)

    if (!key) return

    const source = {
      id: documentId || key,
      datasetName: item.dataset_name || '',
      documentName,
      content: item.content || '',
      score: typeof item.score === 'number' ? item.score : 0,
      position: item.position || index + 1,
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

function removeThinkContent(text = '') {
  return String(text || '')
    .replace(/<think>[\s\S]*?<\/think>/gi, '')
    .replace(/<think>[\s\S]*$/gi, '')
    .replace(/<\/think>/gi, '')
    .trim()
}

function normalizeAnswer(answer = '') {
  const text = removeThinkContent(answer)
  const fallbackAnswer = '当前知识库中未查询到相关制度依据。建议您换一种表述继续查询，或联系相关责任部门确认。'

  if (!text) {
    return {
      answer: fallbackAnswer,
      noHit: true,
    }
  }

  const noHitKeywords = [
    '暂时没有把握',
    '换个方式描述',
    '当前知识库中未查询到',
    '未查询到',
    '未查询到相关',
    '未找到相关',
    '没有找到相关',
    '无法回答',
  ]

  const isNoHit = noHitKeywords.some((keyword) => text.includes(keyword))

  if (isNoHit) {
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

function getDifyConfig(options = {}) {
  const modeKey = options.modeKey || 'policy'

  if (modeKey === 'policy') {
    return {
      apiBase: import.meta.env.VITE_POLICY_DIFY_API_BASE || import.meta.env.VITE_DIFY_API_BASE,
      apiKey: import.meta.env.VITE_POLICY_DIFY_API_KEY || import.meta.env.VITE_DIFY_API_KEY,
      modeName: '制度问答',
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
  await new Promise((resolve) => setTimeout(resolve, 600))

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

  const response = await fetch(`${apiBase.replace(/\/$/, '')}/chat-messages`, {
    method: 'POST',
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
  })

  if (!response.ok) {
    const errorText = await response.text().catch(() => '')
    throw new Error(`Dify 请求失败：${response.status} ${errorText}`)
  }

  const data = await response.json()
  const rawSources = data.metadata?.retriever_resources || []
  const normalizedAnswer = normalizeAnswer(data.answer)

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
      headers: {
        Authorization: `Bearer ${apiKey}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        inputs: {},
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

    const handleEvent = (eventData) => {
      if (!eventData || eventData === '[DONE]') return

      const data = JSON.parse(eventData)

      if (data.event === 'message' || data.event === 'agent_message') {
        const deltaText = data.answer || ''
        if (deltaText) {
          fullAnswerRaw += deltaText
          options.onMessage?.(removeThinkContent(fullAnswerRaw), { replace: true })
        }
        return
      }

      if (data.event === 'message_end') {
        conversationId = data.conversation_id || conversationId
        messageId = data.message_id || messageId
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

    const result = {
      answer: removeThinkContent(fullAnswerRaw) || '当前未获取到有效回答，请稍后重试。',
      conversationId,
      messageId,
      sources: [],
      noHit: false,
    }

    options.onComplete?.(result)
    return result
  } catch (error) {
    options.onError?.(error)
    throw error
  }
}
