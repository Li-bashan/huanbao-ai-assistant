const CURRENT_CONVERSATION_KEY = 'huanbao_current_conversation'
const CONVERSATION_HISTORY_KEY = 'huanbao_conversation_history'
const MAX_HISTORY_COUNT = 50

function readJson(key, fallback) {
  try {
    const rawValue = localStorage.getItem(key)
    return rawValue ? JSON.parse(rawValue) : fallback
  } catch {
    return fallback
  }
}

function writeJson(key, value) {
  localStorage.setItem(key, JSON.stringify(value))
}

export function createConversationId() {
  return `conv_${Date.now()}`
}

export function createConversationTitle(messages = []) {
  const firstUserMessage = messages.find((message) => message.role === 'user')
  const title = firstUserMessage?.content?.trim() || '新对话'
  return title.length > 18 ? `${title.slice(0, 18)}...` : title
}

function normalizeConversation(conversation) {
  if (!conversation?.messages?.length) return null

  const messages = sanitizeMessages(conversation.messages)
  if (!messages.length) return null

  const timestamp = conversation.updatedAt || conversation.createdAt || Date.now()

  return {
    id: conversation.id || `conv_${timestamp}`,
    title: conversation.title || createConversationTitle(messages),
    modeKey: conversation.modeKey || 'policy',
    conversationId: conversation.conversationId || '',
    messages,
    createdAt: conversation.createdAt || timestamp,
    updatedAt: timestamp,
  }
}

export function sanitizeMessages(messages = []) {
  return messages
    .filter((message) => {
      if (message.loading || message.streaming) return false
      if (message.role === 'assistant' && !message.content && !message.workflowCard) return false
      return true
    })
    .map((message) => ({
      id: message.id,
      role: message.role,
      content: message.content,
      loading: false,
      messageId: message.messageId || '',
      expandedSourceId: '',
      workflowCard: message.workflowCard
        ? {
            workflowName: message.workflowCard.workflowName || '',
            description: message.workflowCard.description || '',
            actions: Array.isArray(message.workflowCard.actions)
              ? message.workflowCard.actions
              : [],
            requiredFields: Array.isArray(message.workflowCard.requiredFields)
              ? message.workflowCard.requiredFields
              : [],
          }
        : null,
      sources: Array.isArray(message.sources)
        ? message.sources.map((source) => ({
            id: source.id,
            datasetName: source.datasetName || '',
            documentName: source.documentName || '',
            content: source.content || '',
            score: source.score,
            position: source.position,
          }))
        : [],
    }))
}

export function getCurrentConversation() {
  return readJson(CURRENT_CONVERSATION_KEY, null)
}

export function saveCurrentConversation(conversation) {
  const normalizedConversation = normalizeConversation(conversation)
  if (!normalizedConversation) return
  writeJson(CURRENT_CONVERSATION_KEY, normalizedConversation)
}

export function clearCurrentConversation() {
  localStorage.removeItem(CURRENT_CONVERSATION_KEY)
}

export function getConversationHistory() {
  const history = readJson(CONVERSATION_HISTORY_KEY, [])
  if (!Array.isArray(history)) return []

  return history
    .map((item) => normalizeConversation(item))
    .filter(Boolean)
    .sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0))
    .slice(0, MAX_HISTORY_COUNT)
}

export function saveConversationToHistory(conversation) {
  const normalizedConversation = normalizeConversation(conversation)
  if (!normalizedConversation) return getConversationHistory()

  const history = getConversationHistory()
  const nextHistory = [
    normalizedConversation,
    ...history.filter((item) => item.id !== normalizedConversation.id),
  ]
    .sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0))
    .slice(0, MAX_HISTORY_COUNT)

  writeJson(CONVERSATION_HISTORY_KEY, nextHistory)
  return nextHistory
}

export function deleteConversationFromHistory(conversationId) {
  const nextHistory = getConversationHistory().filter((item) => item.id !== conversationId)
  writeJson(CONVERSATION_HISTORY_KEY, nextHistory)
  return nextHistory
}

export function clearConversationHistory() {
  localStorage.removeItem(CONVERSATION_HISTORY_KEY)
}
