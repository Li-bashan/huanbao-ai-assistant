<script setup>
import {
  Check,
  Copy,
  Expand,
  FileDown,
  FileText,
  History,
  Maximize2,
  MessageSquarePlus,
  Minimize2,
  PanelRightClose,
  PenLine,
  RefreshCw,
  Square,
  X,
} from '@lucide/vue'
import { computed, defineAsyncComponent, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { assistantModes, defaultAssistantModeKey } from './config/assistantModes'
import {
  buildDataQueryFollowUps,
  DATA_QUERY_DEFAULT_PERIOD,
  getDataQueryExploration,
  isUnsupportedDataQuery,
} from './config/dataQueryCatalog'
import { ENABLE_WORKFLOW_PREFILL, detectWorkflowAction } from './config/workflowActions'
import CapabilitySelector from './components/CapabilitySelector.vue'
import AssistantExecution from './components/AssistantExecution.vue'
import DataQueryHome from './components/DataQueryHome.vue'
import DataQueryUserAdmin from './components/DataQueryUserAdmin.vue'
import PromptStarters from './components/PromptStarters.vue'
import WorkflowActionCard from './components/WorkflowActionCard.vue'
import { sendMasterChatMessage, stopChatMessage } from './services/chatApi'
import { streamDataQueryMessage } from './services/dataQueryApi.js'
import { checkDataQueryAccess } from './services/dataQueryAccessApi'
import {
  clearCurrentConversation,
  createConversationId,
  createConversationTitle,
  clearConversationHistory,
  deleteConversationFromHistory,
  getConversationHistory,
  getUserStorageKey,
  getCurrentConversation,
  sanitizeMessages,
  saveConversationToHistory,
  saveCurrentConversation,
  setConversationStorageUser,
} from './utils/conversationStorage'
import { detectIntent } from './utils/intentRouter'
import { sendIgixAction } from './utils/actionBridge'
import { getIgixCurrentUser } from './utils/igixUser'
import { createIgixAssistantWindow } from './utils/igixAssistantWindow'
import {
  createDataQueryChartOptionFromAnswer,
  removeDataQueryChartPayload,
} from './utils/dataQueryChart'
import { renderMarkdown } from './utils/markdown'
import {
  applyDifyExecutionEvent,
  completeDifyExecution,
  createDifyExecutionProcess,
  failDifyExecution,
  stopDifyExecution,
} from './utils/difyMessageState'
import {
  copyText,
  exportMarkdown,
  exportWordHtml,
  getPlainMessageText,
} from './utils/messageExport'

const DataQueryResult = defineAsyncComponent(() => import('./components/DataQueryResult.vue'))

const isDataQueryAdminPage =
  typeof window !== 'undefined' &&
  new URLSearchParams(window.location.search).get('page') === 'data-query-users'
const normalizeModeKey = (modeKey) =>
  modeKey === 'office' || modeKey === 'general' ? 'office-ai' : modeKey

const getModeByKey = (modeKey) =>
  assistantModes.find((mode) => mode.key === normalizeModeKey(modeKey)) || assistantModes[0]

const getStoredModeKey = () => {
  try {
    const storedModeKey = localStorage.getItem(getUserStorageKey('current_mode'))
    return getModeByKey(storedModeKey).key
  } catch {
    return defaultAssistantModeKey
  }
}

const getStoredModeLock = () => {
  try {
    return localStorage.getItem(getUserStorageKey('mode_locked')) === 'true'
  } catch {
    return false
  }
}

const saveModeKey = (modeKey) => {
  try {
    localStorage.setItem(getUserStorageKey('current_mode'), modeKey)
  } catch {
    // Ignore storage failures so the assistant remains usable.
  }
}

const saveModeLock = (isLocked) => {
  try {
    localStorage.setItem(getUserStorageKey('mode_locked'), String(isLocked))
  } catch {
    // Ignore storage failures so the assistant remains usable.
  }
}

const messages = ref([])
const inputValue = ref('')
const inputRef = ref(null)
const inputAssistPlaceholder = ref('')
const chatBodyRef = ref(null)
const conversationId = ref('')
const conversationIds = ref({})
const currentConversationId = ref(createConversationId())
const conversationHistory = ref([])
const showHistory = ref(false)
const historySearch = ref('')
const historyFilter = ref('all')
const copiedMessageId = ref('')
const currentUser = ref(null)
const currentUserReady = ref(false)
const dataQueryAnalysisState = ref('')
const dataQueryAccessStatus = ref('idle')
const dataQueryAccessErrorKind = ref('')
let dataQueryAccessRequestId = 0
const windowState = ref({
  revision: '',
  isOpen: true,
  view: 'compact',
  iframeLoaded: false,
})
const currentModeKey = ref(getStoredModeKey())
const isModeLocked = ref(getStoredModeLock())
const currentMode = computed(() => getModeByKey(currentModeKey.value))
const welcomeTitle = computed(() =>
  currentUser.value?.name
    ? `您好，${currentUser.value.name}，我是环宝${currentMode.value.label}助手。`
    : currentMode.value.welcomeTitle,
)
const hasUserMessages = computed(() =>
  messages.value.some((message) => message.role === 'user' && message.content?.trim()),
)
const hasMessages = computed(() => hasUserMessages.value)
const isStreamingMode = computed(() => currentMode.value.key === 'office-ai')
const isChatBusy = computed(() => messages.value.some((message) => message.loading || message.streaming))
const dataQueryAccessMessage = computed(() => {
  if (dataQueryAccessStatus.value === 'checking' || dataQueryAccessStatus.value === 'idle') {
    return '正在确认当前开放范围...'
  }
  if (dataQueryAccessStatus.value === 'not-covered') {
    return '您所在部门暂不支持生产指标智能问数，如有业务需要，请联系管理员申请。'
  }
  if (dataQueryAccessErrorKind.value === 'current-user-missing') {
    return '暂未获取到当前登录信息，请在门户环境中重试。'
  }
  return '暂时无法确认当前智能问数开放范围，请稍后重试。'
})
const dataQueryInputPlaceholder = computed(() => {
  if (currentMode.value.key !== 'data-query') return currentMode.value.placeholder
  if (dataQueryAccessStatus.value === 'covered') return currentMode.value.placeholder
  if (dataQueryAccessStatus.value === 'not-covered') return '当前暂未开放生产指标查询'
  return dataQueryAccessMessage.value
})
const inputPlaceholder = computed(() => {
  if (inputAssistPlaceholder.value) return inputAssistPlaceholder.value
  if (currentMode.value.key === 'data-query' && dataQueryAccessStatus.value !== 'covered') {
    return dataQueryInputPlaceholder.value
  }

  if (!isModeLocked.value) return '需要环宝帮您做什么？输入 / 快速调用技能及知识库...'
  return `当前已锁定【${currentMode.value.label}】模式，请输入相关问题...`
})
const conversationTitle = computed(() => createConversationTitle(sanitizeMessages(messages.value)))
const showCapabilityMenu = ref(false)
const historyFilters = [
  { key: 'all', label: '全部' },
  { key: 'policy', label: '制度' },
  { key: 'office-ai', label: '办公' },
  { key: 'workflow', label: '流程' },
  { key: 'data-query', label: '问数' },
]
const capabilityLabelsByMode = {
  policy: '制度问答',
  'office-ai': '办公智能',
  workflow: '流程助手',
  'data-query': '智能问数',
}
const executionCapabilityLabelsByMode = {
  policy: '制度问答',
  'office-ai': '智能办公',
  workflow: '流程助手',
  'data-query': '智能问数',
}
const assistantWindow = createIgixAssistantWindow()
let unbindWindowEscape = null
let unsubscribeWindowState = null
let currentUserRefreshPromise = null
let currentUserRefreshToken = 0
let resumeRefreshTimer = null
let activeChatRequest = null
let recentSubmittedQuestion = { value: '', at: 0 }

const createMessageId = () => Date.now() + Math.random()

const normalizeCapabilityKeys = (keys = [], fallbackModeKey = '') =>
  [...(Array.isArray(keys) ? keys : []), fallbackModeKey]
    .map((key) => normalizeModeKey(key))
    .filter((key) => capabilityLabelsByMode[key])
    .filter((key, index, values) => values.indexOf(key) === index)
    .slice(0, 4)

const getCapabilityFields = (modeKey = '', capabilityKeys = []) => {
  const resolvedCapabilities = normalizeCapabilityKeys(capabilityKeys, modeKey)
  return {
    modeKey: modeKey || '',
    resolvedCapability: resolvedCapabilities[0] || modeKey || '',
    resolvedCapabilities,
    capabilityLabels: resolvedCapabilities.map((key) => capabilityLabelsByMode[key]),
  }
}

const getCapabilityKeysFromMessage = (message = {}) =>
  normalizeCapabilityKeys(
    message.resolvedCapabilities || message.resolvedCapability,
    message.modeKey || '',
  )

const getCapabilityLabelsFromConversation = (conversation) => {
  const keys = (conversation?.messages || []).reduce((allKeys, message) => {
    return [...allKeys, ...getCapabilityKeysFromMessage(message)]
  }, [])
  const fallbackKeys = keys.length ? keys : normalizeCapabilityKeys([], conversation?.modeKey || '')
  return fallbackKeys
    .filter((key, index, values) => values.indexOf(key) === index)
    .map((key) => capabilityLabelsByMode[key])
}

const getCapabilityText = (message) => {
  const labels = message?.capabilityLabels?.length
    ? message.capabilityLabels
    : getCapabilityKeysFromMessage(message).map((key) => capabilityLabelsByMode[key])
  if (!labels.length) return ''
  return labels.length > 1 ? `本次协同处理：${labels.join(' + ')}` : `本次处理：${labels[0]}`
}

const getModeConversationId = (modeKey = currentMode.value.key) =>
  conversationIds.value[modeKey] || ''

const setModeConversationId = (modeKey, value = '') => {
  conversationIds.value = { ...conversationIds.value, [modeKey]: value || '' }
  if (modeKey === currentMode.value.key) conversationId.value = value || ''
}

const createModeSwitchMessage = (capabilityKeys) => {
  const fields = getCapabilityFields('', capabilityKeys)
  const labels = fields.capabilityLabels

  return {
  id: createMessageId(),
  role: 'assistant',
  messageType: 'capability-switch',
  content: labels.length > 1 ? `本次协同处理：${labels.join(' + ')}` : `本次处理：${labels[0] || '智能能力'}`,
  loading: false,
  streaming: false,
  sources: [],
  messageId: '',
  expandedSourceId: '',
  ...fields,
  }
}

const createModeRecoveryMessage = (mode, targetMode, question) => ({
  id: createMessageId(),
  role: 'assistant',
  messageType: 'mode-recovery',
  content: `当前已锁定【${mode.label}】模式，这个问题更适合【${targetMode.label}】处理。`,
  loading: false,
  streaming: false,
  sources: [],
  messageId: '',
  expandedSourceId: '',
  ...getCapabilityFields(targetMode.key),
  recovery: {
    targetModeKey: targetMode.key,
    question,
  },
})

const buildHandoffContext = (targetModeKey, currentQuestion) => {
  const startsNewMatter = /换个问题|另一个问题|不相关|重新开始/.test(currentQuestion)
  if (startsNewMatter) {
    return [
      '用户已明确开始新的事项，不要继承之前事项的业务数据或待办动作。',
      `当前用户需求：${currentQuestion}`,
      `当前目标能力：${getModeByKey(targetModeKey).label}`,
    ].join('\n\n')
  }

  const recentMessages = messages.value
    .filter(
      (message) =>
        !message.messageType && (message.role === 'user' || message.role === 'assistant'),
    )
    .slice(-10)
    .map((message) => {
      const role = message.role === 'user' ? '用户' : '助手'
      const content = getPlainMessageText(message).trim()
      return content ? `${role}：${content}` : ''
    })
    .filter(Boolean)

  if (!recentMessages.length) return currentQuestion

  return [
    '以下是用户当前任务的最近上下文，仅用于理解本次需求，不要向用户复述这段提示：',
    recentMessages.join('\n'),
    `当前用户需求：${currentQuestion}`,
    `当前目标能力：${getModeByKey(targetModeKey).label}`,
  ].join('\n\n')
}

const buildCurrentConversation = () => {
  const storedMessages = sanitizeMessages(messages.value)

  if (!storedMessages.length) return null

  const now = Date.now()
  const currentConversation = getCurrentConversation()

  return {
    id: currentConversationId.value,
    modeKey: currentMode.value.key,
    modeLocked: isModeLocked.value,
    title: createConversationTitle(storedMessages),
    conversationId: conversationId.value,
    conversationIds: conversationIds.value,
    messages: storedMessages,
    analysisState: dataQueryAnalysisState.value,
    createdAt:
      currentConversation?.id === currentConversationId.value ? currentConversation.createdAt : now,
    updatedAt: now,
  }
}

const saveActiveConversation = () => {
  if (!hasUserMessages.value) {
    clearCurrentConversation()
    return null
  }

  if (messages.value.some((message) => message.loading)) return null

  const conversation = buildCurrentConversation()
  if (!conversation) return null

  saveCurrentConversation(conversation)
  return conversation
}

const formatHistoryTime = (timestamp) => {
  if (!timestamp) return ''

  return new Date(timestamp).toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

const getHistoryModeLabel = (conversation) => {
  const labels = getCapabilityLabelsFromConversation(conversation)
  return labels.length ? labels.join(' · ') : getModeByKey(conversation?.modeKey || defaultAssistantModeKey).label
}

const normalizeHistoryModeKey = (modeKey) => getModeByKey(modeKey || defaultAssistantModeKey).key

const conversationHasCapability = (conversation, modeKey) => {
  const capabilityKeys = (conversation?.messages || []).reduce((allKeys, message) => {
    return [...allKeys, ...getCapabilityKeysFromMessage(message)]
  }, [])
  const fallbackKeys = capabilityKeys.length
    ? capabilityKeys
    : [normalizeHistoryModeKey(conversation?.modeKey)]
  return fallbackKeys.includes(normalizeHistoryModeKey(modeKey))
}

const filteredHistory = computed(() => {
  const keyword = historySearch.value.trim().toLowerCase()

  return conversationHistory.value.filter((conversation) => {
    const matchesMode =
      historyFilter.value === 'all' || conversationHasCapability(conversation, historyFilter.value)
    const searchableText = [
      conversation.title,
      ...(conversation.messages || [])
        .filter((message) => message.role === 'user')
        .map((message) => message.content),
    ]
      .join(' ')
      .toLowerCase()
    const matchesKeyword = !keyword || searchableText.includes(keyword)

    return matchesMode && matchesKeyword
  })
})

const historyGroups = computed(() => {
  const now = new Date()
  const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  const startOfYesterday = startOfToday - 24 * 60 * 60 * 1000
  const groups = [
    { key: 'today', label: '今天', items: [] },
    { key: 'yesterday', label: '昨天', items: [] },
    { key: 'earlier', label: '更早', items: [] },
  ]

  filteredHistory.value.forEach((conversation) => {
    const timestamp = conversation.updatedAt || conversation.createdAt || 0
    if (timestamp >= startOfToday) {
      groups[0].items.push(conversation)
    } else if (timestamp >= startOfYesterday) {
      groups[1].items.push(conversation)
    } else {
      groups[2].items.push(conversation)
    }
  })

  return groups.filter((group) => group.items.length)
})

const scrollToBottom = async () => {
  await nextTick()
  if (chatBodyRef.value) {
    chatBodyRef.value.scrollTop = chatBodyRef.value.scrollHeight
  }
}

const refreshDataQueryAccess = async () => {
  if (currentMode.value.key !== 'data-query' || !currentUserReady.value) return

  const requestId = ++dataQueryAccessRequestId
  const userId = currentUser.value?.userId?.trim()
  dataQueryAccessErrorKind.value = ''

  if (!userId) {
    dataQueryAccessStatus.value = 'error'
    dataQueryAccessErrorKind.value = 'current-user-missing'
    return
  }

  dataQueryAccessStatus.value = 'checking'
  try {
    const result = await checkDataQueryAccess(currentUser.value)
    if (requestId !== dataQueryAccessRequestId) return
    dataQueryAccessStatus.value = result?.covered ? 'covered' : 'not-covered'
    dataQueryAccessErrorKind.value = ''
  } catch (error) {
    if (requestId !== dataQueryAccessRequestId) return
    if (error?.code === 'DATA_QUERY_NOT_COVERED' || error?.code === 'ACCESS_DENIED') {
      dataQueryAccessStatus.value = 'not-covered'
      dataQueryAccessErrorKind.value = ''
    } else {
      dataQueryAccessStatus.value = 'error'
      dataQueryAccessErrorKind.value = error?.code === 'CURRENT_USER_MISSING' ? 'current-user-missing' : 'api'
    }
  }
}

const wait = (milliseconds) => new Promise((resolve) => window.setTimeout(resolve, milliseconds))

const refreshCurrentUser = async ({ retry = false } = {}) => {
  if (currentUserRefreshPromise) return currentUserRefreshPromise

  const refreshToken = ++currentUserRefreshToken
  const wasReady = currentUserReady.value
  const retryDelays = retry ? [0, 250, 800] : [0]

  if (currentMode.value.key === 'data-query') {
    dataQueryAccessRequestId += 1
    dataQueryAccessStatus.value = 'checking'
    dataQueryAccessErrorKind.value = ''
  }

  const refreshPromise = (async () => {
    let user = null

    for (const [attemptIndex, delay] of retryDelays.entries()) {
      if (delay) await wait(delay)

      try {
        user = await getIgixCurrentUser()
      } catch (error) {
        if (import.meta.env?.DEV) {
          console.warn('IGIX CURRENT USER REFRESH FAILED', error)
        }
      }

      if (user?.name?.trim() || user?.userId?.trim() || attemptIndex === retryDelays.length - 1) {
        break
      }
    }

    if (refreshToken !== currentUserRefreshToken) return user

    const previousUserStorageKey = setConversationStorageUser(currentUser.value)
    const nextUserStorageKey = setConversationStorageUser(user)
    currentUser.value = user
    if (!wasReady) {
      currentModeKey.value = getStoredModeKey()
      isModeLocked.value = getStoredModeLock()
      currentUserReady.value = true
    }

    if (wasReady && previousUserStorageKey !== nextUserStorageKey) {
      activeChatRequest?.controller.abort()
      activeChatRequest = null
      messages.value = []
      conversationId.value = ''
      conversationIds.value = {}
      currentConversationId.value = createConversationId()
      currentModeKey.value = getStoredModeKey()
      isModeLocked.value = getStoredModeLock()
      conversationHistory.value = getConversationHistory()
      const nextConversation = getCurrentConversation()
      if (nextConversation?.messages?.length) {
        currentModeKey.value = getModeByKey(nextConversation.modeKey || currentModeKey.value).key
        isModeLocked.value = nextConversation.modeLocked === true
        currentConversationId.value = nextConversation.id || createConversationId()
        messages.value = sanitizeMessages(nextConversation.messages)
      }
    }

    if (wasReady && currentMode.value.key === 'data-query') {
      await refreshDataQueryAccess()
    }

    return user
  })()

  currentUserRefreshPromise = refreshPromise

  try {
    return await refreshPromise
  } finally {
    if (currentUserRefreshPromise === refreshPromise) {
      currentUserRefreshPromise = null
    }
  }
}

const retryDataQueryAccess = async () => {
  if (dataQueryAccessErrorKind.value === 'current-user-missing' || !currentUser.value?.userId?.trim()) {
    await refreshCurrentUser({ retry: true })
    return
  }

  await refreshDataQueryAccess()
}

const scheduleCurrentUserRefresh = () => {
  if (isDataQueryAdminPage || currentMode.value.key !== 'data-query') return
  if (typeof document !== 'undefined' && document.visibilityState === 'hidden') return

  window.clearTimeout(resumeRefreshTimer)
  resumeRefreshTimer = window.setTimeout(() => {
    resumeRefreshTimer = null
    refreshCurrentUser({ retry: true })
  }, 150)
}

const handleVisibilityChange = () => {
  if (document.visibilityState === 'visible') scheduleCurrentUserRefresh()
}

const handleWindowResume = () => {
  scheduleCurrentUserRefresh()
}

const createWorkflowCardMessage = (workflowResult, capabilityKeys = ['workflow']) => ({
  id: createMessageId(),
  role: 'assistant',
  content: `已识别事项：${workflowResult.workflowName}`,
  loading: false,
  streaming: false,
  sources: [],
  messageId: '',
  expandedSourceId: '',
  ...getCapabilityFields('workflow', capabilityKeys),
  workflowCard: {
    workflowName: workflowResult.workflowName,
    description: workflowResult.description,
    status: workflowResult.status,
    statusReason: workflowResult.statusReason,
    actions: workflowResult.actions,
    actionPayloads: workflowResult.actionPayloads,
    prefillFields: workflowResult.prefillFields,
    parsedFields: workflowResult.parsedFields,
    requiredFields: workflowResult.requiredFields,
    unavailableReason: workflowResult.unavailableReason,
  },
})

const inputRequestPattern = /^(请|需要|请您|请将|请把).{0,24}(提供|补充|粘贴|上传|输入)/
const businessActionNames = new Set([
  'open_policy',
  'query_production_data',
  'generate_production_daily_report',
  'open_purchase_request',
  'send_notice',
])

const isInputRequestAction = (action) => {
  const text = `${action?.label || ''} ${action?.prompt || ''}`.trim()
  return (
    inputRequestPattern.test(text) ||
    /请提供会议内容|提交会议材料|^(提供|补充|粘贴|上传).*(会议内容|会议记录|会议材料)/.test(text)
  )
}

const isBusinessAction = (action) =>
  businessActionNames.has(String(action?.action || '').trim()) ||
  action?.payload?.form === '采购请示单'

const buildMissingInputRequest = (answer = '', modeKey = '', inputAction = null) => {
  const text = String(answer || '').trim()
  const answerRequestsInput =
    inputRequestPattern.test(text) ||
    /请提供会议内容|提交会议材料|^(提供|补充|粘贴|上传).*(会议内容|会议记录|会议材料)/.test(text)
  if (!inputAction && !answerRequestsInput) return null

  const isMeetingRequest = modeKey === 'office-ai' && /会议|纪要/.test(`${text} ${inputAction?.label || ''}`)
  return isMeetingRequest
    ? {
        title: '需要补充会议内容',
        description: '请把会议记录粘贴到下方输入框，我会整理成正式会议纪要。',
        buttonLabel: '去输入会议内容',
        placeholder: '请粘贴会议记录或会议材料',
      }
    : {
        title: '需要补充处理材料',
        description: '请把需要处理的内容粘贴到下方输入框，我会继续完成这项工作。',
        buttonLabel: '去输入补充内容',
        placeholder: '请粘贴需要处理的内容',
      }
}

const createFollowUp = (label, prompt = label, id = '') => ({
  id: id || `follow-up-${label}`,
  label,
  prompt,
})

const buildDefaultFollowUps = (question, answer, modeKey) => {
  const content = `${question} ${answer}`
  if (modeKey === 'data-query') return buildDataQueryFollowUps(question).map((item) => createFollowUp(item))
  if (modeKey === 'policy') {
    return [
      createFollowUp('换一个制度问题'),
      createFollowUp('按我的部门解释'),
      createFollowUp('查看相关制度依据'),
    ]
  }
  if (modeKey === 'office-ai') {
    if (/通知|公告/.test(content)) {
      return [
        createFollowUp('改成邮件版本'),
        createFollowUp('补充发布时间和联系人'),
        createFollowUp('压缩成短信通知'),
      ]
    }
    if (/总结|汇报/.test(content)) {
      return [
        createFollowUp('提炼成汇报提纲'),
        createFollowUp('压缩到 300 字'),
        createFollowUp('改成正式公文'),
      ]
    }
    return [
      createFollowUp('压缩到 300 字'),
      createFollowUp('改成正式公文'),
      createFollowUp('补充责任人和时间'),
    ]
  }
  return []
}

const normalizeMessageInteractions = (result, modeKey, question) => {
  const candidates = Array.isArray(result?.suggestedActions) ? result.suggestedActions : []
  const inputAction = candidates.find(isInputRequestAction)
  const missingInput = buildMissingInputRequest(result?.answer, modeKey, inputAction)
  const actionPills = candidates.filter((action) => isBusinessAction(action) && !isInputRequestAction(action)).slice(0, 4)
  let followUps = candidates
    .filter((action) => !isBusinessAction(action) && !isInputRequestAction(action))
    .map((action, index) => createFollowUp(action.label || action.prompt, action.prompt || action.label, action.id || `follow-up-${index}`))
    .slice(0, 4)

  if (!missingInput && !followUps.length) {
    followUps = buildDefaultFollowUps(question, result?.answer, modeKey)
  }

  return { missingInput, actionPills, followUps }
}

const getMessageActionPills = (message) =>
  (message?.actionPills || []).filter((action) => !isInputRequestAction(action))

const focusInputForMissing = async (missingInput) => {
  if (!missingInput) return
  inputAssistPlaceholder.value = missingInput.placeholder || '请补充需要处理的内容'
  await nextTick()
  inputRef.value?.focus()
}

const clearInputAssistPlaceholder = () => {
  inputAssistPlaceholder.value = ''
}

const getFollowUpLabel = (followUp) =>
  typeof followUp === 'string' ? followUp : followUp?.label || followUp?.prompt || ''

const getFollowUpPrompt = (followUp) =>
  typeof followUp === 'string' ? followUp : followUp?.prompt || followUp?.label || ''

const appendAssistantMessage = async (content, modeKey = currentMode.value.key) => {
  messages.value.push({
    id: createMessageId(),
    role: 'assistant',
    content,
    loading: false,
    streaming: false,
    sources: [],
      messageId: '',
      expandedSourceId: '',
      chartOption: null,
      actionPills: [],
      ...getCapabilityFields(modeKey),
    })
  await scrollToBottom()
}

const sendWorkflowMessage = async (content, capabilityKeys = ['workflow']) => {
  const workflowResult = detectWorkflowAction(content, { currentUser: currentUser.value })

  messages.value.push({
    id: createMessageId(),
    role: 'user',
    content,
    loading: false,
    ...getCapabilityFields('workflow', capabilityKeys),
  })

  if (workflowResult.matched) {
    if (workflowResult.type === 'workflow_list') {
      messages.value.push({
        id: createMessageId(),
        role: 'assistant',
        content: workflowResult.content,
        loading: false,
        streaming: false,
        sources: [],
        messageId: '',
        expandedSourceId: '',
        ...getCapabilityFields('workflow', capabilityKeys),
      })
    } else {
      messages.value.push(createWorkflowCardMessage(workflowResult, capabilityKeys))
    }
  } else {
    messages.value.push({
      id: createMessageId(),
      role: 'assistant',
      content: `我已收到您的流程办理需求：“${content}”。请补充您要办理的具体表单或流程名称，例如采购请示单、合同评审流程、我的待办等。`,
      loading: false,
      streaming: false,
      sources: [],
      messageId: '',
      expandedSourceId: '',
      ...getCapabilityFields('workflow', capabilityKeys),
    })
  }

  inputValue.value = ''
  conversationId.value = ''
  await scrollToBottom()
}

const sendDataQueryExplorationMessage = async (content, exploration) => {
  messages.value.push({
    id: createMessageId(),
    role: 'user',
    content,
    loading: false,
    modeKey: 'data-query',
    ...getCapabilityFields('data-query'),
  })

  const isCompanyExploration = exploration.type === 'company'
  const explorationTitle = String(exploration.title || '').trim()
  const titleEndsWithPunctuation = /[：:。！？!?；;]$/.test(explorationTitle)
  messages.value.push({
    id: createMessageId(),
    role: 'assistant',
    content: isCompanyExploration
      ? `${explorationTitle}${titleEndsWithPunctuation ? '' : '，'}您可以点击“项目公司”入口搜索具体公司。`
      : `${explorationTitle}${titleEndsWithPunctuation ? '' : '：'}`,
    loading: false,
    streaming: false,
    sources: [],
    messageId: '',
    expandedSourceId: '',
    modeKey: 'data-query',
    ...getCapabilityFields('data-query'),
    dataExploration: isCompanyExploration ? null : exploration,
  })

  inputValue.value = ''
  await scrollToBottom()
}

const handleDifyExecutionEvent = (message, event) => {
  if (!message?.executionProcess) return
  applyDifyExecutionEvent(message.executionProcess, event)
  scrollToBottom()
}

const handleExecutionStage = (message, stagePayload = {}) => {
  const process = message?.executionProcess
  if (!process) return

  const stageKey = String(stagePayload.stage || '').trim().toLowerCase()
  const capabilityLabel =
    executionCapabilityLabelsByMode[normalizeModeKey(stageKey)] ||
    ({ '智能问数': '智能问数', '智能办公': '智能办公', '办公智能': '智能办公' }[stageKey] || '')
  const stageMessage = String(stagePayload.message || stagePayload.label || '').trim()

  process.visible = true
  process.status = 'running'
  if (capabilityLabel) {
    process.activeCapability = capabilityLabel
    if (!process.capabilities.includes(capabilityLabel)) process.capabilities.push(capabilityLabel)
  }
  if (stageMessage) {
    process.stage = stageMessage
  } else if (capabilityLabel) {
    process.stage = `正在调用${capabilityLabel}...`
  }
  scrollToBottom()
}

const toggleDifyExecution = (message) => {
  const process = message?.executionProcess
  if (!process) return
  process.expanded = !process.expanded
  process.userExpanded = true
}

const markMessageAsCancelled = (messageId) => {
  const message = messages.value.find((item) => item.id === messageId)
  if (!message) return

  const partialContent = message.content?.trim()
  if (!partialContent?.endsWith('已停止本次执行。')) {
    message.content = partialContent
      ? `${message.content}\n\n已停止本次执行。`
      : '已停止本次执行。'
  }
  message.loading = false
  message.streaming = false
  message.status = ''
  message.messageFollowUps = []
  message.followUps = []
  stopDifyExecution(message.executionProcess)
}

const isCancelledChatError = (error, requestController) =>
  requestController?.signal.aborted ||
  error?.name === 'AbortError' ||
  error?.code === 'CHAT_CANCELLED'

const cancelCurrentExecution = async () => {
  if (!activeChatRequest) return

  const request = activeChatRequest
  request.controller.abort()
  inputValue.value = request.question || ''
  markMessageAsCancelled(request.messageId)
  activeChatRequest = null
  await stopChatMessage(request.taskId, {
    modeKey: request.modeKey,
  }).catch(() => false)
  await scrollToBottom()
}

const handleSendButtonClick = () => {
  if (isChatBusy.value) {
    cancelCurrentExecution()
    return
  }

  sendMessage()
}

const sendMessage = async (question = inputValue.value, options = {}) => {
  if (isChatBusy.value) return

  const content = question.trim()
  if (!content) return
  inputAssistPlaceholder.value = ''

  const now = Date.now()
  const hasSamePendingQuestion = messages.value.some((message, index) =>
    message.role === 'user' &&
    message.content?.trim() === content &&
    messages.value[index + 1]?.loading,
  )
  if (hasSamePendingQuestion) return
  if (
    !options.bypassRecentGuard &&
    recentSubmittedQuestion.value === content &&
    now - recentSubmittedQuestion.at < 1000
  ) return
  recentSubmittedQuestion = { value: content, at: now }

  const previousModeKey = currentMode.value.key
  const forcedModeKey = options.modeKeyOverride
    ? normalizeModeKey(options.modeKeyOverride)
    : ''
  const detectedIntent = forcedModeKey
    ? {
        modeKey: forcedModeKey,
        modeKeys: options.modeKeysOverride || [forcedModeKey],
        confidence: 1,
        reason: '推荐题目指定执行链路',
      }
    : detectIntent(content, { currentModeKey: previousModeKey })
  const routedMode = detectedIntent.modeKey ? getModeByKey(detectedIntent.modeKey) : currentMode.value
  const capabilityKeys = normalizeCapabilityKeys(
    detectedIntent.modeKeys?.length ? detectedIntent.modeKeys : [routedMode.key],
    routedMode.key,
  )
  const hasAutoSwitch = Boolean(detectedIntent.modeKey && routedMode.key !== previousModeKey)
  const handoffQuestion = hasAutoSwitch
    ? [
        buildHandoffContext(routedMode.key, content),
        capabilityKeys.length > 1
          ? `本次协同能力：${capabilityKeys.map((key) => capabilityLabelsByMode[key]).join(' + ')}`
          : '',
      ]
        .filter(Boolean)
        .join('\n\n')
    : content

  if (isModeLocked.value && hasAutoSwitch) {
    messages.value.push({
      id: createMessageId(),
      role: 'user',
      content,
      loading: false,
      ...getCapabilityFields(previousModeKey),
    })
    messages.value.push(createModeRecoveryMessage(currentMode.value, routedMode, content))
    inputValue.value = ''
    await scrollToBottom()
    return
  }

  if (hasAutoSwitch) {
    currentModeKey.value = routedMode.key
    saveModeKey(routedMode.key)
    conversationId.value = getModeConversationId(routedMode.key)
    if (hasAutoSwitch || capabilityKeys.length > 1) {
      messages.value.push(createModeSwitchMessage(capabilityKeys))
    }
  }

  if (currentMode.value.key === 'workflow') {
    await sendWorkflowMessage(content, capabilityKeys)
    return
  }

  if (
    currentMode.value.key === 'data-query' &&
    dataQueryAccessStatus.value !== 'covered'
  ) {
    if (currentUserReady.value && currentUser.value?.userId?.trim()) {
      await refreshDataQueryAccess()
    }
  }

  if (
    currentMode.value.key === 'data-query' &&
    dataQueryAccessStatus.value !== 'covered'
  ) {
    messages.value.push({
      id: createMessageId(),
      role: 'user',
      content,
      loading: false,
      ...getCapabilityFields('data-query', capabilityKeys),
    })
    messages.value.push({
      id: createMessageId(),
      role: 'assistant',
      content: dataQueryAccessMessage.value,
      loading: false,
      streaming: false,
      sources: [],
      messageId: '',
      expandedSourceId: '',
      ...getCapabilityFields('data-query', capabilityKeys),
      errorState: { question: content },
    })
    inputValue.value = ''
    await scrollToBottom()
    return
  }

  if (currentMode.value.key === 'data-query') {
    const exploration = getDataQueryExploration(content)
    if (exploration) {
      await sendDataQueryExplorationMessage(content, exploration)
      return
    }

    if (isUnsupportedDataQuery(content)) {
      await sendDataQueryExplorationMessage(content, {
        type: 'boundary',
        title: '当前智能问数主要支持生产指标数据查询，其他业务数据暂未开放。',
        items: [],
      })
      return
    }
  }

  const requestMode = currentMode.value
  const requestModeKey = requestMode.key
  const loadingMessageId = createMessageId()
  const shouldStream = requestModeKey === 'data-query' || requestModeKey === 'office-ai'

  if (!options.reuseLatestUser) {
    messages.value.push({
      id: createMessageId(),
      role: 'user',
      content,
      loading: false,
      ...getCapabilityFields(requestModeKey, capabilityKeys),
    })
  }
  messages.value.push({
    id: loadingMessageId,
    role: 'assistant',
    content: shouldStream ? '' : '环宝正在思考中...',
    loading: true,
    streaming: shouldStream,
    status:
      requestModeKey === 'data-query'
        ? '正在处理生产指标查询...'
        : shouldStream
          ? '环宝正在生成中...'
          : '',
    sources: [],
    messageId: '',
    expandedSourceId: '',
    chartOption: null,
    actionPills: [],
    executionProcess:
      (requestMode.apiMode === 'dify' || requestModeKey === 'data-query')
        ? createDifyExecutionProcess({
            modeKey: requestModeKey,
            visible: requestModeKey !== 'policy',
            capabilities: capabilityKeys.map((key) => executionCapabilityLabelsByMode[key]),
            stage:
              requestModeKey === 'policy'
                ? '正在检索制度依据...'
                : requestModeKey === 'data-query'
                  ? '正在分析生产指标...'
                  : '正在拟制办公材料...',
          })
        : null,
    ...getCapabilityFields(requestModeKey, capabilityKeys),
  })

  const requestController = new AbortController()
  activeChatRequest = {
    controller: requestController,
    messageId: loadingMessageId,
    question: content,
    modeKey: requestModeKey,
    taskId: '',
  }

  inputValue.value = ''
  await scrollToBottom()

  try {
    const requestOptions = {
      conversationId: getModeConversationId(requestModeKey),
      apiMode: requestMode.apiMode,
      modeKey: requestModeKey,
      currentUserName: currentUser.value?.name || '',
      currentUser: currentUser.value,
      analysisState: dataQueryAnalysisState.value,
      signal: requestController.signal,
      onTask: (taskId) => {
        if (activeChatRequest?.controller === requestController && taskId) {
          activeChatRequest.taskId = taskId
        }
      },
    }
    let hasStreamedAnswer = false

    const chatRequest = requestModeKey === 'data-query'
      ? streamDataQueryMessage
      : sendMasterChatMessage
    const result = await chatRequest(
      requestModeKey === 'data-query' ? content : handoffQuestion,
      {
        ...requestOptions,
        ...(requestModeKey === 'data-query' && options.dataQueryClarification
          ? { clarification: options.dataQueryClarification }
          : {}),
        onMessage: (messageContent, meta = {}) => {
          if (requestController.signal.aborted) return
          const streamingMessage = messages.value.find(
            (message) => message.id === loadingMessageId,
          )
          if (!streamingMessage) return

          if (meta.replace) {
            if (messageContent?.trim()) {
              hasStreamedAnswer = true
              streamingMessage.status = ''
            }
            streamingMessage.content = messageContent || ''
            scrollToBottom()
            return
          }

          if (!messageContent) return
          streamingMessage.content += messageContent
          hasStreamedAnswer = true
          streamingMessage.status = ''
          scrollToBottom()
        },
        onStatus: (status) => {
          if (requestController.signal.aborted) return
          const streamingMessage = messages.value.find(
            (message) => message.id === loadingMessageId,
          )
          if (!streamingMessage || hasStreamedAnswer) return
          streamingMessage.status = status
          scrollToBottom()
        },
        onChart: (chartOption) => {
          if (requestController.signal.aborted) return
          const streamingMessage = messages.value.find(
            (message) => message.id === loadingMessageId,
          )
          if (!streamingMessage) return
          streamingMessage.chartOption = chartOption
          scrollToBottom()
        },
        onDifyEvent: (event) => {
          if (requestController.signal.aborted) return
          const streamingMessage = messages.value.find(
            (message) => message.id === loadingMessageId,
          )
          if (!streamingMessage) return
          handleDifyExecutionEvent(streamingMessage, event)
        },
        onStage: (stagePayload) => {
          if (requestController.signal.aborted) return
          const streamingMessage = messages.value.find(
            (message) => message.id === loadingMessageId,
          )
          if (!streamingMessage) return
          handleExecutionStage(streamingMessage, stagePayload)
        },
      },
    )

    if (requestController.signal.aborted) return

    if (requestModeKey === 'data-query') {
      dataQueryAnalysisState.value =
        result.analysisState || result.protocol?.meta?.analysisState || ''
    }
    setModeConversationId(requestModeKey, result.conversationId)

    const loadingMessage = messages.value.find((message) => message.id === loadingMessageId)
    if (loadingMessage) {
      loadingMessage.content = result.answer || loadingMessage.content
      loadingMessage.loading = false
      loadingMessage.streaming = false
      loadingMessage.status = ''
      completeDifyExecution(loadingMessage.executionProcess)
      loadingMessage.sources = result.sources || []
      loadingMessage.messageId = result.messageId || ''
      loadingMessage.expandedSourceId = ''
      const isStructuredDataQuery =
        requestModeKey === 'data-query' &&
        result.protocol?.protocolVersion === '2.0' &&
        result.protocol?.protocolValid
      const interactions = isStructuredDataQuery
        ? { missingInput: null, actionPills: [], followUps: [] }
        : normalizeMessageInteractions(result, requestModeKey, content)
      loadingMessage.actionPills = interactions.actionPills
      loadingMessage.messageFollowUps = interactions.followUps
      loadingMessage.followUps = interactions.followUps
      loadingMessage.missingInput = interactions.missingInput
      loadingMessage.errorState = null
      Object.assign(loadingMessage, getCapabilityFields(requestModeKey, capabilityKeys))
      loadingMessage.protocol = result.protocol || null
      loadingMessage.messageType = result.protocol?.messageType || ''
      loadingMessage.analysisType = result.protocol?.analysisType || ''
      loadingMessage.chartOption =
        result.chartOption ||
        (requestModeKey === 'data-query'
          ? createDataQueryChartOptionFromAnswer(result.answer)
          : null) ||
        loadingMessage.chartOption ||
        null
      loadingMessage.followUps = isStructuredDataQuery
        ? []
        : requestModeKey === 'data-query'
          ? buildDataQueryFollowUps(content)
          : []
    }
  } catch (error) {
    if (isCancelledChatError(error, requestController)) {
      markMessageAsCancelled(loadingMessageId)
      return
    }

    console.error(error)
    if (requestModeKey === 'data-query' && error?.code === 'DATA_QUERY_NOT_COVERED') {
      dataQueryAccessStatus.value = 'not-covered'
      dataQueryAccessErrorKind.value = ''
    }
    const loadingMessage = messages.value.find((message) => message.id === loadingMessageId)
    if (loadingMessage) {
      loadingMessage.content =
        requestModeKey === 'data-query'
          ? error?.code === 'DATA_QUERY_NOT_COVERED'
            ? '您所在部门暂不支持生产指标智能问数，如有业务需要，请联系管理员申请。'
            : error?.message || '当前服务暂时不可用，请稍后重试。'
          : '当前服务暂时不可用，请稍后重试。'
      loadingMessage.loading = false
      loadingMessage.streaming = false
      loadingMessage.sources = []
      loadingMessage.messageId = ''
      loadingMessage.expandedSourceId = ''
      loadingMessage.status = ''
      loadingMessage.actionPills = []
      loadingMessage.messageFollowUps = []
      loadingMessage.followUps = []
      loadingMessage.missingInput = null
      loadingMessage.errorState = { question: content }
      failDifyExecution(loadingMessage.executionProcess, loadingMessage.content)
    }
  } finally {
    if (activeChatRequest?.controller === requestController) {
      activeChatRequest = null
    }
  }

  await scrollToBottom()
}

const handleStarterSelect = (question, starter) => {
  const promptText = String(question || '').trim()
  if (!promptText) return
  inputValue.value = promptText
  const shouldUseDataQueryPipeline =
    starter?.isCompound === true && starter.supportedModes?.includes('data-query')
  sendMessage(promptText, {
    bypassRecentGuard: true,
    ...(shouldUseDataQueryPipeline
      ? { modeKeyOverride: 'data-query', modeKeysOverride: ['data-query', 'office-ai'] }
      : {}),
  })
}

const retryFailedMessage = async (message) => {
  const question = message?.errorState?.question?.trim()
  if (!question || isChatBusy.value) return

  const messageIndex = messages.value.findIndex((item) => item.id === message.id)
  const previousMessage = messages.value[messageIndex - 1]
  if (messageIndex > 0 && previousMessage?.role === 'user' && previousMessage.content?.trim() === question) {
    messages.value.splice(messageIndex - 1, 2)
  } else if (messageIndex >= 0) {
    messages.value.splice(messageIndex, 1)
  }

  await sendMessage(question, { bypassRecentGuard: true })
}

const handleActionPillClick = (action) => {
  const actionName = String(action?.action || '').trim()

  if (actionName === 'open_purchase_request' || action?.payload?.form === '采购请示单') {
    const workflowCard = detectWorkflowAction('打开采购请示单', { currentUser: currentUser.value })
    const openAction = workflowCard.actions?.find((item) => item.includes('打开'))
    if (workflowCard.matched && openAction) {
      handleWorkflowActionClick(workflowCard, openAction)
      return
    }
  }

  const actionPrompts = {
    open_policy: action?.payload?.keyword ? `查询${action.payload.keyword}` : '查询相关制度依据',
    query_production_data: '查询昨日生活垃圾入厂量和吨垃圾发电量',
    generate_production_daily_report: '根据昨日生产指标生成生产日报',
    send_notice: '生成维护通知',
  }
  const actionPrompt = actionPrompts[actionName]
  const question = String(action?.prompt || action?.query || action?.label || '').trim()
  const nextQuestion = actionPrompt || question
  if (!nextQuestion) return
  inputValue.value = nextQuestion
  sendMessage(nextQuestion, { bypassRecentGuard: true })
}

const restoreAdaptiveMode = () => {
  isModeLocked.value = false
  saveModeLock(false)
}

const handleModeRecovery = async (message) => {
  const recovery = message?.recovery
  if (!recovery?.question) return

  const messageIndex = messages.value.findIndex((item) => item.id === message.id)
  if (messageIndex >= 0) messages.value.splice(messageIndex, 1)

  const targetMode = getModeByKey(recovery.targetModeKey)
  isModeLocked.value = false
  saveModeLock(false)
  currentModeKey.value = targetMode.key
  saveModeKey(targetMode.key)
  conversationId.value = getModeConversationId(targetMode.key)
  await sendMessage(recovery.question, { bypassRecentGuard: true, reuseLatestUser: true })
}

const handleDataExplorationItem = (item, type) => {
  const label = item.name || item
  const question =
    type === 'region'
      ? `查询${DATA_QUERY_DEFAULT_PERIOD}${label}生产指标`
      : `查询${DATA_QUERY_DEFAULT_PERIOD}${label}`
  sendMessage(question)
}

const handleDataQueryFollowUp = (followUp) => {
  const query = String(followUp?.query || followUp?.prompt || followUp?.label || '').trim()
  if (query) sendMessage(query, { bypassRecentGuard: true })
}

const handleDataQueryClarification = (message, candidate) => {
  const clarification = message?.protocol?.clarification
  const selectedValue = String(candidate?.id || '').trim()
  const selectedLabel = String(candidate?.label || '').trim()
  if (!clarification?.slot || !selectedValue || !selectedLabel) return

  sendMessage(selectedLabel, {
    bypassRecentGuard: true,
    dataQueryClarification: {
      slot: clarification.slot,
      selectedValue,
      selectedLabel,
    },
  })
}

const canUseMessageTools = (message) =>
  message.role === 'assistant' &&
  !message.messageType &&
  !message.loading &&
  (message.content || message.workflowCard)

const copyMessageContent = async (message) => {
  await copyText(getPlainMessageText(message))
  copiedMessageId.value = message.id
  setTimeout(() => {
    if (copiedMessageId.value === message.id) {
      copiedMessageId.value = ''
    }
  }, 1500)
}

const exportMessageAsMarkdown = (message) => {
  exportMarkdown(message, currentMode.value.label, conversationTitle.value)
}

const exportMessageAsWordHtml = (message) => {
  exportWordHtml(message, currentMode.value.label, conversationTitle.value)
}

const toggleSource = (message, sourceId) => {
  message.expandedSourceId = message.expandedSourceId === sourceId ? '' : sourceId
}

const handleWorkflowActionClick = (workflowCard, action) => {
  if (!workflowCard) return

  const getWorkflowActionPayload = () => {
    const basePayload =
      workflowCard.actionPayloads?.[action] ||
      (action.includes('预填') ? Object.values(workflowCard.actionPayloads || {})[0] : null)

    if (!basePayload) return null

    const parsedFields = ENABLE_WORKFLOW_PREFILL ? workflowCard.parsedFields || {} : {}
    const hasFields = Object.keys(parsedFields).length > 0

    return hasFields
      ? {
          ...basePayload,
          fields: parsedFields,
        }
      : basePayload
  }

  if (action.includes('打开')) {
    const actionPayload = getWorkflowActionPayload()

    if (!actionPayload) {
      appendAssistantMessage(
        workflowCard.unavailableReason ||
          workflowCard.statusReason ||
          `“${workflowCard.workflowName}”暂未配置可执行动作。`,
      )
      return
    }

    sendIgixAction(actionPayload, { status: workflowCard.status })
    appendAssistantMessage(
      `已向 iGIX 发送打开“${workflowCard.workflowName}”的动作，请在门户页面中查看表单打开结果。`,
    )
    return
  }

  if (action.includes('填写说明') || action.includes('待办说明')) {
    const fields = workflowCard.requiredFields?.length
      ? workflowCard.requiredFields.join('、')
      : '该事项暂无必填字段要求。'
    appendAssistantMessage(`“${workflowCard.workflowName}”需要准备：${fields}`)
    return
  }

  if (action.includes('预填')) {
    if (!ENABLE_WORKFLOW_PREFILL) {
      appendAssistantMessage('企业级试点上线版暂不支持字段预填，请使用打开表单动作后在 iGIX 页面内填写。')
      return
    }

    const actionPayload = getWorkflowActionPayload()

    if (!actionPayload) {
      appendAssistantMessage(
        workflowCard.unavailableReason || `“${workflowCard.workflowName}”暂未配置可执行动作。`,
      )
      return
    }

    if (!actionPayload.fields) {
      appendAssistantMessage('当前未识别到可预填字段，请补充项目名称、联系人、联系电话或预算金额。')
      return
    }

    sendIgixAction(actionPayload, { status: workflowCard.status })
    appendAssistantMessage(
      `已向 iGIX 发送打开“${workflowCard.workflowName}”并预填字段的动作，请在门户页面中查看表单结果。`,
    )
  }
}

const toggleHistory = () => {
  showCapabilityMenu.value = false
  conversationHistory.value = getConversationHistory()
  showHistory.value = !showHistory.value
}

const updateCapabilityMenu = (open) => {
  if (open) showHistory.value = false
  showCapabilityMenu.value = open
}

const switchMode = async (modeKey) => {
  if (modeKey === 'adaptive') {
    showCapabilityMenu.value = false
    if (isChatBusy.value) return
    isModeLocked.value = false
    saveModeLock(false)
    await scrollToBottom()
    return
  }

  const nextMode = getModeByKey(modeKey)

  showCapabilityMenu.value = false
  if (isChatBusy.value) return
  if (nextMode.key === currentMode.value.key) return

  currentModeKey.value = nextMode.key
  isModeLocked.value = true
  saveModeLock(true)
  saveModeKey(nextMode.key)
  conversationId.value = getModeConversationId(nextMode.key)
  await scrollToBottom()
}

const restoreConversation = async (conversation) => {
  if (!conversation) return

  const activeConversation = saveActiveConversation()
  if (activeConversation) {
    conversationHistory.value = saveConversationToHistory(activeConversation)
  }

  currentModeKey.value = getModeByKey(conversation.modeKey || defaultAssistantModeKey).key
  isModeLocked.value = conversation.modeLocked === true
  saveModeLock(isModeLocked.value)
  saveModeKey(currentModeKey.value)
  currentConversationId.value = conversation.id || createConversationId()
  conversationIds.value = conversation.conversationIds || {}
  if (!Object.keys(conversationIds.value).length && conversation.conversationId) {
    conversationIds.value = { [currentModeKey.value]: conversation.conversationId }
  }
  conversationId.value = getModeConversationId(currentModeKey.value)
  messages.value = sanitizeMessages(conversation.messages || [])
  dataQueryAnalysisState.value = conversation.analysisState || ''
  showHistory.value = false
  showCapabilityMenu.value = false

  await scrollToBottom()
}

const deleteHistoryItem = (conversationId) => {
  conversationHistory.value = deleteConversationFromHistory(conversationId)
}

const clearAllHistory = () => {
  if (!window.confirm('确定清空全部历史会话吗？')) return
  clearConversationHistory()
  conversationHistory.value = []
}

const newChat = async () => {
  const conversation = saveActiveConversation()
  if (conversation) {
    conversationHistory.value = saveConversationToHistory(conversation)
  }

  messages.value = []
  inputValue.value = ''
  inputAssistPlaceholder.value = ''
  currentModeKey.value = defaultAssistantModeKey
  isModeLocked.value = false
  saveModeLock(false)
  saveModeKey(defaultAssistantModeKey)
  conversationId.value = ''
  conversationIds.value = {}
  dataQueryAnalysisState.value = ''
  currentConversationId.value = createConversationId()
  showHistory.value = false
  showCapabilityMenu.value = false
  clearCurrentConversation()
  await scrollToBottom()
}

onMounted(async () => {
  if (isDataQueryAdminPage) return

  unbindWindowEscape = assistantWindow.bindEscape()
  unsubscribeWindowState = assistantWindow.onStateChange((state) => {
    windowState.value = state
    showCapabilityMenu.value = false
    scheduleCurrentUserRefresh()
  })
  assistantWindow.getState()

  document.addEventListener('visibilitychange', handleVisibilityChange)
  window.addEventListener('focus', handleWindowResume)
  window.addEventListener('pageshow', handleWindowResume)

  await refreshCurrentUser({ retry: true })
  conversationHistory.value = getConversationHistory()
  const currentConversation = getCurrentConversation()

  if (currentConversation?.messages?.length) {
    currentModeKey.value = getModeByKey(currentConversation.modeKey || currentModeKey.value).key
    isModeLocked.value = currentConversation.modeLocked === true
    saveModeLock(isModeLocked.value)
    saveModeKey(currentModeKey.value)
    currentConversationId.value = currentConversation.id || createConversationId()
    conversationIds.value = currentConversation.conversationIds || {}
    if (!Object.keys(conversationIds.value).length && currentConversation.conversationId) {
      conversationIds.value = { [currentModeKey.value]: currentConversation.conversationId }
    }
    conversationId.value = getModeConversationId(currentModeKey.value)
    messages.value = sanitizeMessages(currentConversation.messages)
    dataQueryAnalysisState.value = currentConversation.analysisState || ''
    await scrollToBottom()
  }
})

onUnmounted(() => {
  activeChatRequest?.controller.abort()
  activeChatRequest = null
  currentUserRefreshToken += 1
  window.clearTimeout(resumeRefreshTimer)
  document.removeEventListener('visibilitychange', handleVisibilityChange)
  window.removeEventListener('focus', handleWindowResume)
  window.removeEventListener('pageshow', handleWindowResume)
  if (unbindWindowEscape) unbindWindowEscape()
  if (unsubscribeWindowState) unsubscribeWindowState()
  assistantWindow.destroy()
})

watch(
  [currentModeKey, currentUserReady],
  ([modeKey, userReady], [previousModeKey, previousUserReady] = []) => {
    if (!userReady) return
    if (modeKey === 'data-query') {
      if (previousUserReady && modeKey !== previousModeKey) {
        refreshCurrentUser({ retry: true })
        return
      }
      refreshDataQueryAccess()
    } else {
      dataQueryAccessStatus.value = 'idle'
      dataQueryAccessErrorKind.value = ''
    }
  },
)

watch(
  [messages, conversationId, currentModeKey, isModeLocked],
  () => {
    saveActiveConversation()
  },
  { deep: true },
)
</script>

<template>
  <DataQueryUserAdmin v-if="isDataQueryAdminPage" />
  <section
    v-else
    class="ai-assistant"
    :class="`assistant-view-${windowState.view || 'compact'}`"
    aria-label="环宝 AI 智能助手"
  >
    <header class="assistant-header">
      <div class="brand">
        <div class="assistant-avatar" aria-hidden="true">
          <img src="/huanbao-avatar.png" alt="" />
        </div>
        <div class="brand-copy">
          <h1>环宝 AI 智能助手</h1>
          <p><span class="status-dot"></span>在线服务中</p>
        </div>
      </div>

      <CapabilitySelector
        :modes="assistantModes"
        :current-mode-key="currentMode.key"
        :adaptive="!isModeLocked"
        :open="showCapabilityMenu"
        :disabled="isChatBusy"
        @update:open="updateCapabilityMenu"
        @select="switchMode"
      />

      <div class="header-actions" aria-label="助手操作">
        <button class="new-chat-button" type="button" title="新对话" aria-label="新对话" @click="newChat">
          <MessageSquarePlus class="titlebar-icon" :size="18" :stroke-width="1.8" aria-hidden="true" />
          <span class="visually-hidden">新对话</span>
        </button>
        <div class="history-wrapper">
          <button
            class="history-button"
            type="button"
            title="历史记录"
            aria-label="历史记录"
            :aria-expanded="showHistory"
            @click="toggleHistory"
          >
            <History class="titlebar-icon" :size="18" :stroke-width="1.8" aria-hidden="true" />
          </button>
          <div v-if="showHistory" class="history-panel">
            <div class="history-panel-head">
              <div class="history-title">最近会话</div>
              <button
                v-if="conversationHistory.length"
                class="history-clear-button"
                type="button"
                @click="clearAllHistory"
              >
                清空历史
              </button>
            </div>
            <input
              v-model="historySearch"
              class="history-search"
              type="text"
              placeholder="搜索历史会话..."
            />
            <div class="history-filter-list">
              <button
                v-for="filter in historyFilters"
                :key="filter.key"
                class="history-filter-button"
                :class="{ active: historyFilter === filter.key }"
                type="button"
                @click="historyFilter = filter.key"
              >
                {{ filter.label }}
              </button>
            </div>
            <div v-if="historyGroups.length" class="history-list">
              <div
                v-for="group in historyGroups"
                :key="group.key"
                class="history-group"
              >
                <div class="history-group-title">{{ group.label }}</div>
                <div
                  v-for="conversation in group.items"
                  :key="conversation.id"
                  class="history-item"
                  :title="conversation.title"
                >
                  <button
                    class="history-item-main"
                    type="button"
                    @click="restoreConversation(conversation)"
                  >
                    <span class="history-item-title">{{ conversation.title }}</span>
                    <span class="history-item-meta">
                        <span class="history-mode">{{ getHistoryModeLabel(conversation) }}</span>
                      <span>{{ formatHistoryTime(conversation.updatedAt) }}</span>
                      <span>{{ conversation.messages.length }} 条消息</span>
                    </span>
                  </button>
                  <button
                    class="history-delete-button"
                    type="button"
                    aria-label="删除历史会话"
                    @click="deleteHistoryItem(conversation.id)"
                  >
                    删除
                  </button>
                </div>
              </div>
            </div>
            <div v-else class="history-empty">暂无历史会话。</div>
          </div>
        </div>
        <span class="titlebar-divider" aria-hidden="true"></span>
        <button
          v-if="windowState.view !== 'fullscreen'"
          class="window-control-button"
          type="button"
          :title="windowState.view === 'wide' ? '切换小窗口' : '切换中窗口'"
          @click="assistantWindow.toggleWide()"
        >
          <Minimize2
            v-if="windowState.view === 'wide'"
            class="titlebar-icon"
            :size="18"
            :stroke-width="1.8"
            aria-hidden="true"
          />
          <Maximize2
            v-else
            class="titlebar-icon"
            :size="18"
            :stroke-width="1.8"
            aria-hidden="true"
          />
        </button>
        <button
          v-if="windowState.view !== 'fullscreen'"
          class="window-control-button"
          type="button"
          title="切换全屏"
          @click="assistantWindow.toggleFullscreen()"
        >
          <Expand class="titlebar-icon" :size="18" :stroke-width="1.8" aria-hidden="true" />
        </button>
        <button
          v-if="windowState.view === 'fullscreen'"
          class="window-control-button"
          type="button"
          title="切换小窗口"
          aria-label="切换小窗口"
          @click="assistantWindow.compact()"
        >
          <PanelRightClose class="titlebar-icon" :size="18" :stroke-width="1.8" aria-hidden="true" />
        </button>
        <button
          class="window-control-button window-control-close"
          type="button"
          title="关闭"
          aria-label="关闭"
          @click="assistantWindow.close()"
        >
          <X class="titlebar-icon" :size="19" :stroke-width="1.8" aria-hidden="true" />
        </button>
      </div>
    </header>

    <main
      ref="chatBodyRef"
      class="chat-body"
      :class="{ 'chat-body-has-messages': hasMessages }"
    >
      <div class="chat-content">
        <DataQueryHome
          v-if="currentMode.key === 'data-query' && dataQueryAccessStatus === 'covered'"
          :session-key="currentConversationId"
          :org-name="currentUser?.orgName || currentUser?.unitName || ''"
          :disabled="isChatBusy"
          :show-home="!hasMessages"
          :input-value="inputValue"
          @update:input-value="inputValue = $event"
          @submit-query="handleStarterSelect"
        />

        <section
          v-else-if="currentMode.key === 'data-query'"
          class="data-query-access-state"
          :class="`data-query-access-state-${dataQueryAccessStatus}`"
          aria-live="polite"
        >
          <div class="data-query-access-state-icon" aria-hidden="true">{{ dataQueryAccessStatus === 'not-covered' ? '·' : '○' }}</div>
          <h2>{{ dataQueryAccessStatus === 'not-covered' ? '当前暂未开放' : '正在确认开放范围' }}</h2>
          <p>{{ dataQueryAccessMessage }}</p>
          <button
            v-if="dataQueryAccessStatus === 'error'"
            type="button"
            class="data-query-access-retry"
            @click="retryDataQueryAccess"
          >
            重新检查
          </button>
        </section>

        <section v-else class="welcome-card" aria-label="助手欢迎信息">
          <div class="welcome-copy">
            <span class="welcome-tag">{{ currentMode.badge }}</span>
            <h2>{{ welcomeTitle }}</h2>
            <p>{{ currentMode.welcomeDesc }}</p>
            <span v-if="currentMode.capabilityLabel" class="welcome-capability">
              {{ currentMode.capabilityLabel }}
            </span>
          </div>
        </section>

        <section class="message-list" aria-label="对话消息">
          <div v-if="!hasMessages && currentMode.key !== 'data-query'" class="message-row message-row-assistant">
            <span class="message-avatar" aria-hidden="true">
              <img src="/huanbao-avatar.png" alt="" />
            </span>
            <div class="message message-assistant">{{ currentMode.guideText }}</div>
          </div>

          <div
            v-for="message in messages"
            :key="message.id"
            class="message-row"
            :class="[
              message.role === 'user' ? 'message-row-user' : 'message-row-assistant',
              message.messageType ? `message-row-${message.messageType}` : '',
            ]"
          >
            <span
              v-if="message.role === 'assistant' && !message.messageType"
              class="message-avatar"
              aria-hidden="true"
            >
              <img src="/huanbao-avatar.png" alt="" />
            </span>
            <div
              class="message"
              :class="[
                message.role === 'user' ? 'message-user' : 'message-assistant',
                message.messageType ? `message-${message.messageType}` : '',
                message.role === 'assistant' &&
                message.loading &&
                !message.content &&
                message.executionProcess?.visible
                  ? 'message-assistant-executing'
                  : '',
              ]"
            >
              <div v-if="message.messageType === 'capability-switch'" class="mode-switch-copy">
                <RefreshCw :size="14" :stroke-width="1.8" aria-hidden="true" />
                <span>{{ message.content }}</span>
              </div>
              <div v-else-if="message.messageType === 'mode-recovery'" class="mode-recovery-copy">
                <div class="mode-recovery-text">{{ message.content }}</div>
                <button type="button" class="mode-recovery-action" @click="handleModeRecovery(message)">
                  <span aria-hidden="true">⚡</span>
                  一键切换为智能模式并生成
                </button>
              </div>
              <AssistantExecution
                v-if="message.role === 'assistant' && message.executionProcess"
                :process="message.executionProcess"
                :mode-key="message.modeKey"
                @toggle="toggleDifyExecution(message)"
              />
              <div
                v-if="message.role === 'assistant' && !message.messageType && !message.loading && !message.executionProcess && getCapabilityText(message)"
                class="message-capability-note"
              >
                <span class="message-capability-note-dot" aria-hidden="true"></span>
                <span>{{ getCapabilityText(message) }}</span>
              </div>
              <div
                v-if="message.role === 'assistant' && message.loading && !message.content && message.status && !message.executionProcess?.visible"
                class="message-status"
                aria-live="polite"
              >
                <span>{{ message.status }}</span>
                <span class="message-status-dots" aria-hidden="true"><i></i><i></i><i></i></span>
              </div>
              <div
                v-else-if="message.role === 'assistant' && !message.messageType && message.modeKey !== 'data-query'"
                class="markdown-body markdown-content"
                :class="{ 'data-query-answer': message.modeKey === 'data-query' }"
                v-html="renderMarkdown(message.modeKey === 'data-query' ? removeDataQueryChartPayload(message.content) : message.content)"
              ></div>
              <template v-else-if="message.role === 'user'">{{ message.content }}</template>

              <div
                v-if="message.dataExploration?.items?.length && (message.modeKey !== 'data-query' || dataQueryAccessStatus === 'covered')"
                class="data-query-exploration-card"
              >
                <div class="data-query-exploration-title">可点击继续选择</div>
                <div class="data-query-chip-list">
                  <button
                    v-for="item in message.dataExploration.items"
                    :key="item.id || item"
                    type="button"
                    class="data-query-chip"
                    @click="handleDataExplorationItem(item, message.dataExploration.type)"
                  >
                    {{ item.name || item }}
                  </button>
                </div>
              </div>

              <DataQueryResult
                v-if="message.role === 'assistant' && message.modeKey === 'data-query'"
                :answer="message.content"
                :protocol="message.protocol"
                :chart-option="message.chartOption"
                :window-view="windowState.view"
                @follow-up="handleDataQueryFollowUp"
                @clarification="handleDataQueryClarification(message, $event)"
              />

              <WorkflowActionCard
                v-if="message.workflowCard"
                :card="message.workflowCard"
                @action="handleWorkflowActionClick(message.workflowCard, $event)"
              />

              <div
                v-if="message.role === 'assistant' && message.sources?.length"
                class="message-source-drawer"
              >
                <button
                  type="button"
                  class="source-drawer-trigger"
                  :aria-expanded="Boolean(message.expandedSourceId)"
                  @click="toggleSource(message, message.sources[0].id)"
                >
                  <span class="source-drawer-trigger-copy">
                    <span class="source-drawer-icon" aria-hidden="true">📎</span>
                    <span>参考依据：《{{ message.sources[0].documentName || '制度知识库' }}》</span>
                  </span>
                  <span class="source-toggle" aria-hidden="true">
                    {{ message.expandedSourceId ? '⌃' : '⌄' }}
                  </span>
                </button>
                <Transition name="source-drawer">
                  <div v-if="message.expandedSourceId" class="source-drawer-panel">
                    <div
                      v-for="source in message.sources.slice(0, 3)"
                      :key="source.id"
                      class="source-drawer-source"
                    >
                      <div class="source-drawer-source-title">
                        {{ source.documentName || '制度知识库' }}
                        <span v-if="source.datasetName" class="source-dataset">
                          {{ source.datasetName }}
                        </span>
                      </div>
                      <div class="source-content-title">知识库原文片段</div>
                      <div class="source-drawer-source-content">
                        {{ source.content || '暂无可展示的原文片段。' }}
                      </div>
                    </div>
                  </div>
                </Transition>
              </div>

              <section
                v-if="message.role === 'assistant' && message.missingInput"
                class="message-missing-input"
                aria-label="需要补充信息"
              >
                <div class="message-missing-input-title">{{ message.missingInput.title }}</div>
                <p>{{ message.missingInput.description }}</p>
                <button type="button" class="message-missing-input-action" @click="focusInputForMissing(message.missingInput)">
                  <PenLine :size="14" :stroke-width="1.8" aria-hidden="true" />
                  {{ message.missingInput.buttonLabel }}
                </button>
              </section>

              <div
                v-if="message.role === 'assistant' && getMessageActionPills(message).length"
                class="message-action-pills"
                aria-label="业务动作"
              >
                <span class="message-action-pills-label">可执行操作</span>
                <button
                    v-for="action in getMessageActionPills(message)"
                  :key="action.id || action.prompt || action.label"
                  type="button"
                  class="message-action-pill"
                  @click="handleActionPillClick(action)"
                >
                  <span aria-hidden="true">›</span>
                  {{ action.label || action.prompt }}
                </button>
              </div>

              <div
                v-if="(message.messageFollowUps || message.followUps)?.length && (message.modeKey !== 'data-query' || dataQueryAccessStatus === 'covered')"
                class="message-followups"
              >
                <div class="message-followups-title">你还可以继续</div>
                <div class="message-followup-list">
                  <button
                    v-for="followUp in message.messageFollowUps || message.followUps"
                    :key="getFollowUpLabel(followUp)"
                    type="button"
                    class="message-followup"
                    @click="sendMessage(getFollowUpPrompt(followUp))"
                  >
                    {{ getFollowUpLabel(followUp) }}
                  </button>
                </div>
              </div>

              <div v-if="message.errorState" class="message-error-actions" aria-label="失败后的操作">
                <span class="message-error-actions-title">本次处理未完成</span>
                <button type="button" @click="retryFailedMessage(message)">重试</button>
                <button type="button" @click="focusInputForMissing({ placeholder: '请换一种方式描述您的需求' })">
                  换一种问法
                </button>
              </div>

              <div v-if="canUseMessageTools(message)" class="message-toolbar">
                <button
                  type="button"
                  :title="copiedMessageId === message.id ? '已复制' : '复制回答'"
                  :aria-label="copiedMessageId === message.id ? '已复制' : '复制回答'"
                  @click="copyMessageContent(message)"
                >
                  <Check v-if="copiedMessageId === message.id" :size="15" :stroke-width="2" aria-hidden="true" />
                  <Copy v-else :size="15" :stroke-width="2" aria-hidden="true" />
                  <span class="visually-hidden">{{ copiedMessageId === message.id ? '已复制' : '复制回答' }}</span>
                </button>
                <button
                  type="button"
                  title="导出 Markdown"
                  aria-label="导出 Markdown"
                  @click="exportMessageAsMarkdown(message)"
                >
                  <FileDown :size="15" :stroke-width="2" aria-hidden="true" />
                  <span class="visually-hidden">导出 Markdown</span>
                </button>
                <button
                  type="button"
                  title="导出 Word"
                  aria-label="导出 Word"
                  @click="exportMessageAsWordHtml(message)"
                >
                  <FileText :size="15" :stroke-width="2" aria-hidden="true" />
                  <span class="visually-hidden">导出 Word</span>
                </button>
              </div>
            </div>
          </div>
        </section>

        <PromptStarters
          v-if="!hasMessages && currentMode.key !== 'data-query'"
          :mode="isModeLocked ? currentMode.key : 'all'"
          :org-name="currentUser?.orgName || currentUser?.unitName || ''"
          :disabled="isChatBusy"
          @select="handleStarterSelect"
        />

      </div>
    </main>

    <footer class="assistant-footer">
      <div v-if="isModeLocked" class="mode-lock-notice" role="status">
        <span>当前已锁定【{{ currentMode.label }}】模式 🔒</span>
        <button type="button" @click="restoreAdaptiveMode">点击恢复自适应</button>
      </div>
      <form class="input-shell" @submit.prevent="sendMessage()">
        <span class="input-attach" aria-hidden="true">↵</span>
        <input
          ref="inputRef"
          v-model="inputValue"
          type="text"
          :placeholder="inputPlaceholder"
          :aria-label="inputPlaceholder"
          :disabled="isChatBusy || (currentMode.key === 'data-query' && dataQueryAccessStatus !== 'covered')"
          @input="clearInputAssistPlaceholder"
        />
        <button
          class="send-button"
          :class="{ 'send-button-running': isChatBusy }"
          type="button"
          :title="isChatBusy ? '停止当前执行' : '发送'"
          :aria-label="isChatBusy ? '停止当前执行' : '发送'"
          :disabled="!isChatBusy && currentMode.key === 'data-query' && dataQueryAccessStatus !== 'covered'"
          @click.stop.prevent="handleSendButtonClick"
        >
          <Square v-if="isChatBusy" :size="14" :stroke-width="2.4" fill="currentColor" aria-hidden="true" />
          <span>{{ isChatBusy ? '停止' : '发送' }}</span>
        </button>
      </form>
    </footer>
  </section>
</template>
