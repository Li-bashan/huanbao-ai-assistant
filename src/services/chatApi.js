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

export async function sendChatMessage(question, options = {}) {
  const useDify = import.meta.env.VITE_USE_DIFY === 'true'

  if (!useDify) {
    await new Promise((resolve) => setTimeout(resolve, 600))

    return {
      answer: `我已收到您的问题：“${question}”。后续这里会接入 Dify 智能体接口。`,
      conversationId: options.conversationId || 'mock-conversation-001',
      messageId: '',
      sources: [],
    }
  }

  const apiBase = import.meta.env.VITE_DIFY_API_BASE
  const apiKey = import.meta.env.VITE_DIFY_API_KEY
  const user = import.meta.env.VITE_DIFY_USER || 'huanbao-web-user'

  if (!apiBase || !apiKey) {
    throw new Error('Dify API 配置缺失，请检查 .env.local')
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

  return {
    answer: data.answer || '当前未获取到有效回答。',
    conversationId: data.conversation_id || options.conversationId || '',
    messageId: data.message_id || '',
    sources: normalizeSources(rawSources),
  }
}
