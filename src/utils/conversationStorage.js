import {
  createDataQueryChartOptionFromAnswer,
  extractDataQueryChartOption,
  removeDataQueryChartPayload,
} from './dataQueryChart.js'

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

function sanitizeMessageContent(message) {
  if (message?.modeKey !== 'data-query') return message?.content
  return removeDataQueryChartPayload(message?.content)
}

function sanitizeMessageChartOption(message) {
  const chartOption = message?.chartOption
    ? extractDataQueryChartOption(message.chartOption)
    : null
  if (chartOption) return chartOption
  if (message?.modeKey !== 'data-query') return null
  return createDataQueryChartOptionFromAnswer(sanitizeMessageContent(message))
}

function sanitizeExecutionProcess(process) {
  if (!process) return null

  const rawNodes = Array.isArray(process.nodes)
    ? process.nodes
    : Array.isArray(process.steps)
      ? process.steps
      : Array.isArray(process.tracing)
        ? process.tracing
        : []
  const normalizeStatus = (status) => {
    if (status === 'succeeded' || status === 'completed') return 'success'
    if (status === 'cancelled' || status === 'canceled') return 'stopped'
    return ['pending', 'waiting', 'running', 'retrying', 'success', 'failed', 'stopped', 'paused'].includes(status)
      ? status
      : 'success'
  }

  return {
    status: normalizeStatus(process.status),
    visible: process.visible !== false,
    expanded: process.expanded === true,
    userExpanded: false,
    workflowRunId: process.workflowRunId || process.workflow_run_id || '',
    currentNodeId: '',
    startedAt: Number.isFinite(Number(process.startedAt)) ? Number(process.startedAt) : null,
    finishedAt: Number.isFinite(Number(process.finishedAt)) ? Number(process.finishedAt) : null,
    error: process.error || '',
    nodes: rawNodes.slice(0, 80).map((node, index) => ({
      key: node.key || node.node_id || node.id || `execution-node-${index}`,
      title: node.title || node.node_title || node.nodeName || '执行节点',
      nodeType: node.nodeType || node.node_type || '',
      status: normalizeStatus(node.status),
      startedAt: Number.isFinite(Number(node.startedAt)) ? Number(node.startedAt) : null,
      finishedAt: Number.isFinite(Number(node.finishedAt)) ? Number(node.finishedAt) : null,
      elapsedTime: Number.isFinite(Number(node.elapsedTime)) ? Number(node.elapsedTime) : null,
      error: node.error || '',
      retryCount: Number.isFinite(Number(node.retryCount)) ? Number(node.retryCount) : 0,
    })),
    thoughts: [],
  }
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
    conversationIds:
      conversation.conversationIds && typeof conversation.conversationIds === 'object'
        ? conversation.conversationIds
        : conversation.conversationId
          ? { [conversation.modeKey || 'policy']: conversation.conversationId }
          : {},
    messages,
    createdAt: conversation.createdAt || timestamp,
    updatedAt: timestamp,
  }
}

export function sanitizeMessages(messages = []) {
  const sanitizedMessages = messages
    .filter((message) => {
      if (message.loading || message.streaming) return false
      const content = sanitizeMessageContent(message)
      const chartOption = sanitizeMessageChartOption(message)
      const executionProcess = message.executionProcess || message.workflowProcess
      if (
        message.role === 'assistant' &&
        !content &&
        !message.workflowCard &&
        !chartOption &&
        !executionProcess?.nodes?.length &&
        !executionProcess?.steps?.length
      ) return false
      return true
    })
    .map((message) => ({
      id: message.id,
      role: message.role,
      content: sanitizeMessageContent(message),
      loading: false,
      messageType: message.messageType || '',
      modeKey: message.modeKey || '',
      executionProcess: sanitizeExecutionProcess(message.executionProcess || message.workflowProcess),
      followUps: Array.isArray(message.followUps) ? message.followUps.slice(0, 3) : [],
      dataExploration: message.dataExploration
        ? {
            type: message.dataExploration.type || '',
            title: message.dataExploration.title || '',
            items: Array.isArray(message.dataExploration.items)
              ? message.dataExploration.items.slice(0, 20).map((item) =>
                  typeof item === 'string'
                    ? item
                    : { id: item.id || '', name: item.name || '' },
                )
              : [],
          }
        : null,
      chartOption: sanitizeMessageChartOption(message),
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

  return sanitizedMessages.filter((message, index) => {
    const previousMessage = sanitizedMessages[index - 1]
    return !(
      message.role === 'user' &&
      previousMessage?.role === 'user' &&
      message.modeKey === previousMessage.modeKey &&
      message.content?.trim() === previousMessage.content?.trim()
    )
  })
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
