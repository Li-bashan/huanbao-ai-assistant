const CURRENT_CONVERSATION_KEY = 'huanbao_current_conversation'
const CONVERSATION_HISTORY_KEY = 'huanbao_conversation_history'
const MAX_HISTORY_COUNT = 20

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

export function sanitizeMessages(messages = []) {
  return messages
    .filter((message) => !message.loading)
    .map((message) => ({
      id: message.id,
      role: message.role,
      content: message.content,
      loading: false,
      messageId: message.messageId || '',
      expandedSourceId: '',
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
  if (!conversation?.messages?.length) return
  writeJson(CURRENT_CONVERSATION_KEY, conversation)
}

export function clearCurrentConversation() {
  localStorage.removeItem(CURRENT_CONVERSATION_KEY)
}

export function getConversationHistory() {
  const history = readJson(CONVERSATION_HISTORY_KEY, [])
  return Array.isArray(history) ? history : []
}

export function saveConversationToHistory(conversation) {
  if (!conversation?.messages?.length) return getConversationHistory()

  const history = getConversationHistory()
  const nextHistory = [
    conversation,
    ...history.filter((item) => item.id !== conversation.id),
  ]
    .sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0))
    .slice(0, MAX_HISTORY_COUNT)

  writeJson(CONVERSATION_HISTORY_KEY, nextHistory)
  return nextHistory
}

export function clearConversationHistory() {
  localStorage.removeItem(CONVERSATION_HISTORY_KEY)
}
