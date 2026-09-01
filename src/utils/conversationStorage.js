import {
  createDataQueryChartOptionFromAnswer,
  extractDataQueryChartOption,
  removeDataQueryChartPayload,
} from './dataQueryChart.js'
import { stripDataQueryAnalysisMetadata } from './dataQueryAnalysis.js'
import { normalizeDataQueryResponse } from './dataQueryProtocol.js'

const MAX_HISTORY_COUNT = 50

let activeUserNamespace = 'guest'

function stableHash(value) {
  let hash = 2166136261
  for (const character of String(value || '')) {
    hash ^= character.charCodeAt(0)
    hash = Math.imul(hash, 16777619)
  }
  return (hash >>> 0).toString(16).padStart(8, '0')
}

function getTabNamespace() {
  try {
    const existing = sessionStorage.getItem('huanbao_tab_namespace')
    if (existing) return existing
    const created = `tab_${stableHash(`${Date.now()}_${Math.random()}`)}`
    sessionStorage.setItem('huanbao_tab_namespace', created)
    return created
  } catch {
    return `tab_${stableHash(`${Date.now()}_${Math.random()}`)}`
  }
}

const tabNamespace = getTabNamespace()

function getUserIdentitySeed(user = {}) {
  user = user || {}
  const userId = String(user.userId || user.id || '').trim()
  const tenantId = String(user.tenantId || user.tenantName || '').trim()
  const userCode = String(user.code || user.userCode || '').trim()
  return [tenantId, userId || userCode].join(':')
}

export function getConversationStorageUserKey(user = null) {
  const seed = getUserIdentitySeed(user)
  return seed.replace(/:/g, '').trim() ? `u_${stableHash(seed)}` : 'guest'
}

export function setConversationStorageUser(user = null) {
  activeUserNamespace = getConversationStorageUserKey(user)
  return activeUserNamespace
}

export function getUserStorageKey(name) {
  return `huanbao:${activeUserNamespace}:${name}`
}

const getCurrentConversationKey = () => getUserStorageKey(`current_conversation:${tabNamespace}`)
const getConversationHistoryKey = () => getUserStorageKey('conversation_history')

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

function removeThinkContent(text = '') {
  return String(text || '')
    .replace(/<think>[\s\S]*?<\/think>/gi, '')
    .replace(/<think>[\s\S]*$/gi, '')
    .replace(/<\/think>/gi, '')
    .trim()
}

function sanitizeMessageContent(message) {
  const rawContent = removeThinkContent(message?.content)
  if (message?.modeKey !== 'data-query') return rawContent
  const content = stripDataQueryAnalysisMetadata(rawContent)
  return removeDataQueryChartPayload(content)
}

function sanitizeMessageChartOption(message) {
  const chartOption = message?.chartOption
    ? extractDataQueryChartOption(message.chartOption)
    : null
  if (chartOption) return chartOption
  if (message?.modeKey !== 'data-query') return null
  return createDataQueryChartOptionFromAnswer(sanitizeMessageContent(message))
}

function sanitizeDataQueryProtocol(message) {
  if (message?.modeKey !== 'data-query' || !message?.protocol) return null

  const protocol = normalizeDataQueryResponse(message.protocol, {
    fallbackText: sanitizeMessageContent(message),
  })
  if (!protocol || protocol.protocolVersion !== '2.0') return null

  const table = protocol.content?.table
  if (table?.rows?.length) {
    protocol.content.table = {
      ...table,
      rows: table.rows.slice(0, 200),
    }
  }

  try {
    if (JSON.stringify(protocol).length > 180000) {
      protocol.content.chart = null
      protocol.content.evidence = []
      protocol.content.table = protocol.content.table
        ? { ...protocol.content.table, rows: protocol.content.table.rows.slice(0, 80) }
        : null
    }
  } catch {
    return null
  }

  return protocol
}

function sanitizeExecutionProcess(process) {
  if (!process) return null

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
    modeKey: process.modeKey || '',
    capabilities: Array.isArray(process.capabilities) ? process.capabilities.slice(0, 6) : [],
    stage: process.stage || '',
    workflowRunId: process.workflowRunId || process.workflow_run_id || '',
    currentNodeId: '',
    startedAt: Number.isFinite(Number(process.startedAt)) ? Number(process.startedAt) : null,
    finishedAt: Number.isFinite(Number(process.finishedAt)) ? Number(process.finishedAt) : null,
    error: '',
    // 历史只保留业务状态，不持久化 Dify 节点标题、节点类型或技术错误。
    nodes: [],
    thoughts: [],
  }
}

function sanitizeFollowUps(followUps) {
  if (!Array.isArray(followUps)) return []

  return followUps
    .map((followUp, index) => {
      if (typeof followUp === 'string') {
        const label = followUp.trim()
        return label ? { id: `follow-up-${index}`, label, prompt: label } : null
      }

      if (!followUp || typeof followUp !== 'object') return null
      const label = String(followUp.label || followUp.title || followUp.prompt || '').trim()
      const prompt = String(followUp.prompt || followUp.query || label).trim()
      if (!label || !prompt) return null

      return {
        id: String(followUp.id || `follow-up-${index}`),
        label,
        prompt,
      }
    })
    .filter(Boolean)
    .slice(0, 4)
}

function sanitizeCapabilityMetadata(message) {
  const resolvedCapabilities = [
    ...(Array.isArray(message?.resolvedCapabilities) ? message.resolvedCapabilities : []),
    message?.resolvedCapability,
    message?.modeKey,
  ]
    .map((value) => String(value || '').trim())
    .filter(Boolean)
    .filter((value, index, values) => values.indexOf(value) === index)
    .slice(0, 4)

  const capabilityLabels = [
    ...(Array.isArray(message?.capabilityLabels) ? message.capabilityLabels : []),
  ]
    .map((value) => String(value || '').trim())
    .filter(Boolean)
    .filter((value, index, values) => values.indexOf(value) === index)
    .slice(0, 4)

  return {
    resolvedCapability: String(message?.resolvedCapability || message?.modeKey || '').trim(),
    resolvedCapabilities,
    capabilityLabels,
  }
}

export function createConversationId() {
  if (globalThis.crypto?.randomUUID) return `conv_${globalThis.crypto.randomUUID()}`
  return `conv_${Date.now()}_${Math.random().toString(16).slice(2)}`
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
    // These ids are local product conversation ids only. Dify conversation ids
    // are held by the Gateway mapping table and never persisted in browser
    // history or used as the restore contract.
    conversationId: typeof conversation.conversationId === 'string'
      ? conversation.conversationId.slice(0, 120)
      : '',
    conversationIds: conversation.conversationIds && typeof conversation.conversationIds === 'object'
      ? Object.fromEntries(
          Object.entries(conversation.conversationIds)
            .filter(([key, value]) => key && typeof value === 'string' && value.trim())
            .slice(0, 8)
            .map(([key, value]) => [key, value.slice(0, 120)]),
        )
      : {},
    modeLocked: conversation.modeLocked === true,
    messages,
    analysisState:
      typeof conversation.analysisState === 'string'
        ? conversation.analysisState.slice(0, 12000)
        : '',
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
        !message.missingInput &&
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
      analysisType: message.analysisType || message.protocol?.analysisType || '',
      modeKey: message.modeKey || '',
      ...sanitizeCapabilityMetadata(message),
      executionProcess: sanitizeExecutionProcess(message.executionProcess || message.workflowProcess),
      messageFollowUps: sanitizeFollowUps(message.messageFollowUps || message.followUps),
      followUps: sanitizeFollowUps(message.messageFollowUps || message.followUps),
      actionPills: Array.isArray(message.actionPills)
        ? message.actionPills
            .map((action, index) => {
              const actionName = String(action?.action || '').trim()
              return {
                id: String(action?.id || `suggested-action-${index}`),
                label: String(action?.label || action?.prompt || '').trim(),
                prompt: String(action?.prompt || action?.query || action?.label || '').trim(),
                ...(actionName ? { action: actionName } : {}),
                ...(action?.payload && typeof action.payload === 'object'
                  ? { payload: action.payload }
                  : {}),
              }
            })
            .filter((action) => action.label && action.prompt)
            .slice(0, 6)
        : [],
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
      protocol: sanitizeDataQueryProtocol(message),
      messageId: message.messageId || '',
      expandedSourceId: '',
      missingInput: message.missingInput
        ? {
            title: String(message.missingInput.title || '').trim(),
            description: String(message.missingInput.description || '').trim(),
            buttonLabel: String(message.missingInput.buttonLabel || '去输入补充内容').trim(),
            placeholder: String(message.missingInput.placeholder || '').trim(),
          }
        : null,
      errorState: message.errorState
        ? { question: String(message.errorState.question || '').trim() }
        : null,
      workflowCard: message.workflowCard
        ? {
            workflowName: message.workflowCard.workflowName || '',
            description: message.workflowCard.description || '',
            status: message.workflowCard.status || '',
            statusReason: message.workflowCard.statusReason || '',
            actions: Array.isArray(message.workflowCard.actions)
              ? message.workflowCard.actions
              : [],
            actionPayloads:
              message.workflowCard.actionPayloads && typeof message.workflowCard.actionPayloads === 'object'
                ? message.workflowCard.actionPayloads
                : {},
            prefillFields: Array.isArray(message.workflowCard.prefillFields)
              ? message.workflowCard.prefillFields
              : [],
            parsedFields:
              message.workflowCard.parsedFields && typeof message.workflowCard.parsedFields === 'object'
                ? message.workflowCard.parsedFields
                : {},
            requiredFields: Array.isArray(message.workflowCard.requiredFields)
              ? message.workflowCard.requiredFields
              : [],
            unavailableReason: message.workflowCard.unavailableReason || '',
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
  return readJson(getCurrentConversationKey(), null)
}

export function saveCurrentConversation(conversation) {
  const normalizedConversation = normalizeConversation(conversation)
  if (!normalizedConversation) return
  writeJson(getCurrentConversationKey(), normalizedConversation)
}

export function clearCurrentConversation() {
  localStorage.removeItem(getCurrentConversationKey())
}

export function getConversationHistory() {
  const history = readJson(getConversationHistoryKey(), [])
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

  writeJson(getConversationHistoryKey(), nextHistory)
  return nextHistory
}

export function deleteConversationFromHistory(conversationId) {
  const nextHistory = getConversationHistory().filter((item) => item.id !== conversationId)
  writeJson(getConversationHistoryKey(), nextHistory)
  return nextHistory
}

export function clearConversationHistory() {
  localStorage.removeItem(getConversationHistoryKey())
}
