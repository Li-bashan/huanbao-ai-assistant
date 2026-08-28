<script setup>
import {
  Expand,
  History,
  Maximize2,
  MessageSquarePlus,
  Minimize2,
  PanelRightClose,
  RefreshCw,
  Square,
  ChevronDown,
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
import DataQueryHome from './components/DataQueryHome.vue'
import DataQueryUserAdmin from './components/DataQueryUserAdmin.vue'
import { sendChatMessage, streamChatMessage } from './services/chatApi'
import { checkDataQueryAccess } from './services/dataQueryAccessApi'
import {
  clearCurrentConversation,
  createConversationId,
  createConversationTitle,
  clearConversationHistory,
  deleteConversationFromHistory,
  getConversationHistory,
  getCurrentConversation,
  sanitizeMessages,
  saveConversationToHistory,
  saveCurrentConversation,
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
  copyText,
  exportMarkdown,
  exportWordHtml,
  getPlainMessageText,
} from './utils/messageExport'

const DataQueryResult = defineAsyncComponent(() => import('./components/DataQueryResult.vue'))

const MODE_STORAGE_KEY = 'huanbao_current_mode'
const isDataQueryAdminPage =
  typeof window !== 'undefined' &&
  new URLSearchParams(window.location.search).get('page') === 'data-query-users'
const capabilityClassNames = ['capability-blue', 'capability-green', 'capability-purple', 'capability-orange']
const normalizeModeKey = (modeKey) =>
  modeKey === 'office' || modeKey === 'general' ? 'office-ai' : modeKey

const getModeByKey = (modeKey) =>
  assistantModes.find((mode) => mode.key === normalizeModeKey(modeKey)) || assistantModes[0]

const getStoredModeKey = () => {
  try {
    const storedModeKey = localStorage.getItem(MODE_STORAGE_KEY)
    return getModeByKey(storedModeKey).key
  } catch {
    return defaultAssistantModeKey
  }
}

const saveModeKey = (modeKey) => {
  try {
    localStorage.setItem(MODE_STORAGE_KEY, modeKey)
  } catch {
    // Ignore storage failures so the assistant remains usable.
  }
}

const messages = ref([])
const inputValue = ref('')
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
const currentMode = computed(() => getModeByKey(currentModeKey.value))
const welcomeTitle = computed(() =>
  currentUser.value?.name
    ? `您好，${currentUser.value.name}，我是环宝${currentMode.value.label}助手。`
    : currentMode.value.welcomeTitle,
)
const hasMessages = computed(() => messages.value.length > 0)
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
const conversationTitle = computed(() => createConversationTitle(sanitizeMessages(messages.value)))
const showCapabilityMenu = ref(false)
const historyFilters = [
  { key: 'all', label: '全部' },
  { key: 'policy', label: '制度' },
  { key: 'office-ai', label: '办公' },
  { key: 'workflow', label: '流程' },
  { key: 'data-query', label: '问数' },
]
const assistantWindow = createIgixAssistantWindow()
let unbindWindowEscape = null
let unsubscribeWindowState = null
let currentUserRefreshPromise = null
let currentUserRefreshToken = 0
let resumeRefreshTimer = null
let activeChatRequest = null
let recentSubmittedQuestion = { value: '', at: 0 }

const createMessageId = () => Date.now() + Math.random()

const getModeConversationId = (modeKey = currentMode.value.key) =>
  conversationIds.value[modeKey] || ''

const setModeConversationId = (modeKey, value = '') => {
  conversationIds.value = { ...conversationIds.value, [modeKey]: value || '' }
  if (modeKey === currentMode.value.key) conversationId.value = value || ''
}

const createModeSwitchMessage = (mode) => ({
  id: createMessageId(),
  role: 'assistant',
  messageType: 'mode-switch',
  content: `已切换至 ${mode.label}`,
  loading: false,
  streaming: false,
  sources: [],
  messageId: '',
  expandedSourceId: '',
})

const buildHandoffContext = (targetModeKey, currentQuestion) => {
  const recentMessages = messages.value
    .filter(
      (message) =>
        !message.messageType && (message.role === 'user' || message.role === 'assistant'),
    )
    .slice(-4)
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
    `当前目标模块：${getModeByKey(targetModeKey).label}`,
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
    title: createConversationTitle(storedMessages),
    conversationId: conversationId.value,
    conversationIds: conversationIds.value,
    messages: storedMessages,
    createdAt:
      currentConversation?.id === currentConversationId.value ? currentConversation.createdAt : now,
    updatedAt: now,
  }
}

const saveActiveConversation = () => {
  if (!messages.value.length) {
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

const getHistoryModeLabel = (modeKey) => getModeByKey(modeKey || defaultAssistantModeKey).label

const normalizeHistoryModeKey = (modeKey) => getModeByKey(modeKey || defaultAssistantModeKey).key

const filteredHistory = computed(() => {
  const keyword = historySearch.value.trim().toLowerCase()

  return conversationHistory.value.filter((conversation) => {
    const modeKey = normalizeHistoryModeKey(conversation.modeKey)
    const matchesMode = historyFilter.value === 'all' || modeKey === historyFilter.value
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
  const userName = currentUser.value?.name?.trim()
  dataQueryAccessErrorKind.value = ''

  if (!userName) {
    dataQueryAccessStatus.value = 'error'
    dataQueryAccessErrorKind.value = 'current-user-missing'
    return
  }

  dataQueryAccessStatus.value = 'checking'
  try {
    const result = await checkDataQueryAccess(userName)
    if (requestId !== dataQueryAccessRequestId) return
    dataQueryAccessStatus.value = result?.covered ? 'covered' : 'not-covered'
    dataQueryAccessErrorKind.value = ''
  } catch (error) {
    if (requestId !== dataQueryAccessRequestId) return
    dataQueryAccessStatus.value = 'error'
    dataQueryAccessErrorKind.value = error?.code === 'CURRENT_USER_MISSING' ? 'current-user-missing' : 'api'
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

    currentUser.value = user
    if (!wasReady) currentUserReady.value = true

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
  if (dataQueryAccessErrorKind.value === 'current-user-missing' || !currentUser.value?.name?.trim()) {
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

const createWorkflowCardMessage = (workflowResult) => ({
  id: createMessageId(),
  role: 'assistant',
  content: `已识别事项：${workflowResult.workflowName}`,
  loading: false,
  streaming: false,
  sources: [],
  messageId: '',
  expandedSourceId: '',
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

const appendAssistantMessage = async (content) => {
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
  })
  await scrollToBottom()
}

const sendWorkflowMessage = async (content) => {
  const workflowResult = detectWorkflowAction(content, { currentUser: currentUser.value })

  messages.value.push({
    id: createMessageId(),
    role: 'user',
    content,
    loading: false,
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
      })
    } else {
      messages.value.push(createWorkflowCardMessage(workflowResult))
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
    dataExploration: isCompanyExploration ? null : exploration,
  })

  inputValue.value = ''
  await scrollToBottom()
}

const createDataQueryWorkflowProcess = () => ({
  status: 'running',
  expanded: false,
  steps: [],
})

const getWorkflowEventData = (event) =>
  event?.data && typeof event.data === 'object' ? event.data : {}

const getWorkflowStepKey = (data) =>
  String(data.node_id || data.nodeId || data.title || data.node_title || data.index || 'workflow-node')

const getWorkflowStepTitle = (data) =>
  data.title || data.node_title || data.nodeName || data.node_id || '执行节点'

const getWorkflowElapsedTime = (data, step) => {
  const value = Number(data.elapsed_time ?? data.elapsedTime)
  if (Number.isFinite(value)) return value
  if (step?.startedAt) return (Date.now() - step.startedAt) / 1000
  return null
}

const formatWorkflowElapsed = (value) => {
  const seconds = Number(value)
  if (!Number.isFinite(seconds)) return '处理中'
  const milliseconds = seconds * 1000
  return milliseconds >= 1000
    ? `${seconds.toFixed(3)} s`
    : `${milliseconds.toFixed(3)} ms`
}

const updateDataQueryWorkflowProcess = (message, event) => {
  if (!message || message.modeKey !== 'data-query') return
  if (!message.workflowProcess) message.workflowProcess = createDataQueryWorkflowProcess()

  const data = getWorkflowEventData(event)
  if (event.event === 'workflow_started') {
    message.workflowProcess.status = 'running'
    return
  }

  if (event.event === 'node_started' || event.event === 'node_finished') {
    const key = getWorkflowStepKey(data)
    let step = message.workflowProcess.steps.find((item) => item.key === key)

    if (!step) {
      step = {
        key,
        title: getWorkflowStepTitle(data),
        status: 'running',
        elapsedTime: null,
        startedAt: null,
      }
      message.workflowProcess.steps.push(step)
    }

    step.title = getWorkflowStepTitle(data)
    if (event.event === 'node_started') {
      step.status = 'running'
      step.startedAt = Date.now()
    } else {
      step.status = data.status === 'failed' || data.error ? 'failed' : 'success'
      step.elapsedTime = getWorkflowElapsedTime(data, step)
    }
    return
  }

  if (event.event === 'workflow_finished' || event.event === 'message_end') {
    const failed = data.status === 'failed' || data.error
    message.workflowProcess.status = failed ? 'failed' : 'success'
    message.workflowProcess.steps.forEach((step) => {
      if (step.status === 'running') step.status = failed ? 'failed' : 'success'
      if (step.elapsedTime === null) step.elapsedTime = getWorkflowElapsedTime(data, step)
    })
  }
}

const markMessageAsCancelled = (messageId) => {
  const message = messages.value.find((item) => item.id === messageId)
  if (!message) return

  message.content = '已停止本次执行。'
  message.loading = false
  message.streaming = false
  message.status = ''
  message.chartOption = null
  message.sources = []
  message.messageId = ''
  message.expandedSourceId = ''
  message.followUps = []

  if (message.workflowProcess) {
    message.workflowProcess.status = 'cancelled'
    message.workflowProcess.steps.forEach((step) => {
      if (step.status === 'running') step.status = 'cancelled'
    })
  }
}

const isCancelledChatError = (error, requestController) =>
  requestController?.signal.aborted ||
  error?.name === 'AbortError' ||
  error?.code === 'CHAT_CANCELLED'

const cancelCurrentExecution = () => {
  if (!activeChatRequest) return

  const request = activeChatRequest
  request.controller.abort()
  inputValue.value = request.question || ''
  markMessageAsCancelled(request.messageId)
  activeChatRequest = null
  scrollToBottom()
}

const handleSendButtonClick = () => {
  if (isChatBusy.value) {
    cancelCurrentExecution()
    return
  }

  sendMessage()
}

const sendMessage = async (question = inputValue.value) => {
  if (isChatBusy.value) return

  const content = question.trim()
  if (!content) return

  const now = Date.now()
  const hasSamePendingQuestion = messages.value.some((message, index) =>
    message.role === 'user' &&
    message.content?.trim() === content &&
    messages.value[index + 1]?.loading,
  )
  if (hasSamePendingQuestion) return
  if (recentSubmittedQuestion.value === content && now - recentSubmittedQuestion.at < 1000) return
  recentSubmittedQuestion = { value: content, at: now }

  const previousModeKey = currentMode.value.key
  const detectedIntent = detectIntent(content, { currentModeKey: previousModeKey })
  const routedMode = detectedIntent.modeKey ? getModeByKey(detectedIntent.modeKey) : currentMode.value
  const hasAutoSwitch = Boolean(detectedIntent.modeKey && routedMode.key !== previousModeKey)
  const handoffQuestion = hasAutoSwitch ? buildHandoffContext(routedMode.key, content) : content

  if (hasAutoSwitch) {
    currentModeKey.value = routedMode.key
    saveModeKey(routedMode.key)
    conversationId.value = getModeConversationId(routedMode.key)
    messages.value.push(createModeSwitchMessage(routedMode))
  }

  if (currentMode.value.key === 'workflow') {
    await sendWorkflowMessage(content)
    return
  }

  if (
    currentMode.value.key === 'data-query' &&
    dataQueryAccessStatus.value !== 'covered'
  ) {
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

  const loadingMessageId = createMessageId()
  const shouldStream = currentMode.value.key === 'data-query' || isStreamingMode.value

  messages.value.push({
    id: createMessageId(),
    role: 'user',
    content,
    loading: false,
    modeKey: currentMode.value.key,
  })
  messages.value.push({
    id: loadingMessageId,
    role: 'assistant',
    content: isStreamingMode.value || currentMode.value.key === 'data-query' ? '' : '环宝正在思考中...',
    loading: true,
    streaming: shouldStream,
    status:
      currentMode.value.key === 'data-query'
        ? '正在处理生产指标查询...'
        : isStreamingMode.value
          ? '环宝正在生成中...'
          : '',
    sources: [],
    messageId: '',
    expandedSourceId: '',
    chartOption: null,
    workflowProcess: currentMode.value.key === 'data-query' ? createDataQueryWorkflowProcess() : null,
    modeKey: currentMode.value.key,
  })

  const requestController = new AbortController()
  activeChatRequest = {
    controller: requestController,
    messageId: loadingMessageId,
    question: content,
  }

  inputValue.value = ''
  await scrollToBottom()

  try {
    const requestOptions = {
      conversationId: getModeConversationId(currentMode.value.key),
      apiMode: currentMode.value.apiMode,
      modeKey: currentMode.value.key,
      currentUserName: currentUser.value?.name || '',
      signal: requestController.signal,
    }
    let hasStreamedAnswer = false

    const result = shouldStream
      ? await streamChatMessage(
        currentMode.value.key === 'data-query' ? content : handoffQuestion,
        {
          ...requestOptions,
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
          onWorkflowEvent: (event) => {
            if (requestController.signal.aborted) return
            const streamingMessage = messages.value.find(
              (message) => message.id === loadingMessageId,
            )
            if (!streamingMessage) return
            updateDataQueryWorkflowProcess(streamingMessage, event)
            scrollToBottom()
          },
        },
      )
      : await sendChatMessage(handoffQuestion, requestOptions)

    if (requestController.signal.aborted) return

    setModeConversationId(currentMode.value.key, result.conversationId)

    const loadingMessage = messages.value.find((message) => message.id === loadingMessageId)
    if (loadingMessage) {
      loadingMessage.content = result.answer || loadingMessage.content
      loadingMessage.loading = false
      loadingMessage.streaming = false
      loadingMessage.status = ''
      if (loadingMessage.workflowProcess?.status === 'running') {
        loadingMessage.workflowProcess.status = 'success'
        loadingMessage.workflowProcess.steps.forEach((step) => {
          if (step.status === 'running') step.status = 'success'
        })
      }
      loadingMessage.sources = result.sources || []
      loadingMessage.messageId = result.messageId || ''
      loadingMessage.expandedSourceId = ''
      loadingMessage.modeKey = currentMode.value.key
      loadingMessage.chartOption =
        result.chartOption ||
        (currentMode.value.key === 'data-query'
          ? createDataQueryChartOptionFromAnswer(result.answer)
          : null) ||
        loadingMessage.chartOption ||
        null
      loadingMessage.followUps =
        currentMode.value.key === 'data-query' ? buildDataQueryFollowUps(content) : []
    }
  } catch (error) {
    if (isCancelledChatError(error, requestController)) {
      markMessageAsCancelled(loadingMessageId)
      return
    }

    console.error(error)
    if (currentMode.value.key === 'data-query' && error?.code === 'DATA_QUERY_NOT_COVERED') {
      dataQueryAccessStatus.value = 'not-covered'
      dataQueryAccessErrorKind.value = ''
    }
    const loadingMessage = messages.value.find((message) => message.id === loadingMessageId)
    if (loadingMessage) {
      loadingMessage.content =
        currentMode.value.key === 'data-query'
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
      if (loadingMessage.workflowProcess) {
        loadingMessage.workflowProcess.status = 'failed'
        loadingMessage.workflowProcess.steps.forEach((step) => {
          if (step.status === 'running') step.status = 'failed'
        })
      }
    }
  } finally {
    if (activeChatRequest?.controller === requestController) {
      activeChatRequest = null
    }
  }

  await scrollToBottom()
}

const handleRecommendClick = (question) => {
  sendMessage(question)
}

const handleDataExplorationItem = (item, type) => {
  const label = item.name || item
  const question =
    type === 'region'
      ? `查询${DATA_QUERY_DEFAULT_PERIOD}${label}生产指标`
      : `查询${DATA_QUERY_DEFAULT_PERIOD}${label}`
  sendMessage(question)
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
  const nextMode = getModeByKey(modeKey)

  showCapabilityMenu.value = false
  if (isChatBusy.value) return
  if (nextMode.key === currentMode.value.key) return

  const activeConversation = saveActiveConversation()
  if (activeConversation) {
    conversationHistory.value = saveConversationToHistory(activeConversation)
  }

  currentModeKey.value = nextMode.key
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
  saveModeKey(currentModeKey.value)
  currentConversationId.value = conversation.id || createConversationId()
  conversationIds.value = conversation.conversationIds || {}
  if (!Object.keys(conversationIds.value).length && conversation.conversationId) {
    conversationIds.value = { [currentModeKey.value]: conversation.conversationId }
  }
  conversationId.value = getModeConversationId(currentModeKey.value)
  messages.value = sanitizeMessages(conversation.messages || [])
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
  currentModeKey.value = defaultAssistantModeKey
  saveModeKey(defaultAssistantModeKey)
  conversationId.value = ''
  conversationIds.value = {}
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
    saveModeKey(currentModeKey.value)
    currentConversationId.value = currentConversation.id || createConversationId()
    conversationIds.value = currentConversation.conversationIds || {}
    if (!Object.keys(conversationIds.value).length && currentConversation.conversationId) {
      conversationIds.value = { [currentModeKey.value]: currentConversation.conversationId }
    }
    conversationId.value = getModeConversationId(currentModeKey.value)
    messages.value = sanitizeMessages(currentConversation.messages)
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
  [messages, conversationId],
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
                      <span class="history-mode">{{ getHistoryModeLabel(conversation.modeKey) }}</span>
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

    <main ref="chatBodyRef" class="chat-body">
      <div class="chat-content">
        <DataQueryHome
          v-if="currentMode.key === 'data-query' && dataQueryAccessStatus === 'covered'"
          :session-key="currentConversationId"
          :show-home="!hasMessages"
          :input-value="inputValue"
          @update:input-value="inputValue = $event"
          @submit-query="sendMessage($event)"
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
              ]"
            >
              <div v-if="message.messageType === 'mode-switch'" class="mode-switch-copy">
                <RefreshCw :size="14" :stroke-width="1.8" aria-hidden="true" />
                <span>{{ message.content }}</span>
              </div>
              <section
                v-if="message.modeKey === 'data-query' && message.workflowProcess"
                class="data-query-workflow-process"
                :class="`data-query-workflow-process-${message.workflowProcess.status}`"
                aria-label="正在执行的节点"
              >
                <button
                  type="button"
                  class="data-query-workflow-process-head"
                  :aria-expanded="message.workflowProcess.expanded"
                  @click="message.workflowProcess.expanded = !message.workflowProcess.expanded"
                >
                  <span class="data-query-workflow-process-icon" aria-hidden="true">
                    {{ message.workflowProcess.status === 'failed' ? '!' : message.workflowProcess.status === 'cancelled' ? '–' : message.workflowProcess.status === 'running' ? '·' : '✓' }}
                  </span>
                  <strong>正在执行的节点</strong>
                  <span class="data-query-workflow-process-status">
                    <span>
                      {{ message.workflowProcess.status === 'success' ? '已完成' : message.workflowProcess.status === 'failed' ? '执行失败' : message.workflowProcess.status === 'cancelled' ? '已停止' : message.status || '正在执行...' }}
                    </span>
                    <span
                      v-if="message.workflowProcess.status === 'running' && message.loading"
                      class="message-status-dots"
                      aria-hidden="true"
                    ><i></i><i></i><i></i></span>
                  </span>
                  <ChevronDown
                    class="data-query-workflow-process-caret"
                    :class="{ 'data-query-workflow-process-caret-expanded': message.workflowProcess.expanded }"
                    :size="15"
                    :stroke-width="2"
                    aria-hidden="true"
                  />
                </button>
                <div v-if="message.workflowProcess.expanded" class="data-query-workflow-step-list">
                  <div v-for="step in message.workflowProcess.steps" :key="step.key" class="data-query-workflow-step">
                    <span
                      class="data-query-workflow-step-icon"
                      :class="`data-query-workflow-step-icon-${step.status}`"
                      aria-hidden="true"
                    >
                      {{ step.status === 'failed' ? '!' : step.status === 'running' ? '·' : step.status === 'cancelled' ? '–' : '✓' }}
                    </span>
                    <span class="data-query-workflow-step-title">{{ step.title }}</span>
                    <span class="data-query-workflow-step-time">
                      {{ step.status === 'running' ? '处理中' : step.status === 'cancelled' ? '已停止' : formatWorkflowElapsed(step.elapsedTime) }}
                    </span>
                    <span v-if="step.status === 'success'" class="data-query-workflow-step-check" aria-hidden="true">✓</span>
                  </div>
                </div>
              </section>
              <div
                v-if="message.role === 'assistant' && message.loading && !message.content && message.status && !message.workflowProcess"
                class="message-status"
                aria-live="polite"
              >
                <span>{{ message.status }}</span>
                <span class="message-status-dots" aria-hidden="true"><i></i><i></i><i></i></span>
              </div>
              <div
                v-else-if="message.role === 'assistant' && !message.messageType && message.modeKey !== 'data-query'"
                class="markdown-content"
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
                :chart-option="message.chartOption"
              />

              <div v-if="message.workflowCard" class="workflow-card">
                <div class="workflow-card-header">
                  <span class="workflow-card-kicker">已识别事项</span>
                  <strong>{{ message.workflowCard.workflowName }}</strong>
                  <p>{{ message.workflowCard.description }}</p>
                </div>

                <div class="workflow-section">
                  <div class="workflow-section-title">可执行操作</div>
                  <div v-if="message.workflowCard.actions?.length" class="workflow-action-list">
                    <button
                      v-for="action in message.workflowCard.actions"
                      :key="action"
                      class="workflow-action-button"
                      type="button"
                      @click="handleWorkflowActionClick(message.workflowCard, action)"
                    >
                      {{ action }}
                    </button>
                  </div>
                  <p v-else class="workflow-empty-fields">
                    {{ message.workflowCard.unavailableReason || '该事项暂未配置可执行动作。' }}
                  </p>
                </div>

                <div class="workflow-section">
                  <div class="workflow-section-title">需要准备</div>
                  <ul v-if="message.workflowCard.requiredFields?.length" class="workflow-field-list">
                    <li
                      v-for="field in message.workflowCard.requiredFields"
                      :key="field"
                    >
                      {{ field }}
                    </li>
                  </ul>
                  <p v-else class="workflow-empty-fields">暂无必填字段。</p>
                </div>
              </div>

              <div
                v-if="message.role === 'assistant' && message.sources?.length"
                class="message-sources"
              >
                <div class="sources-title">引用 · 制度来源</div>
                <div class="source-list">
                  <div
                    v-for="source in message.sources.slice(0, 3)"
                    :key="source.id"
                    class="source-card"
                    :title="source.documentName"
                    @click="toggleSource(message, source.id)"
                  >
                    <div class="source-item">
                      <div class="source-main">
                        <span class="source-icon" aria-hidden="true">📄</span>
                        <span class="source-name">{{ source.documentName }}</span>
                        <span v-if="source.datasetName" class="source-dataset">
                          {{ source.datasetName }}
                        </span>
                      </div>
                      <span class="source-toggle" aria-hidden="true">
                        {{ message.expandedSourceId === source.id ? '⌃' : '⌄' }}
                      </span>
                    </div>
                    <div v-if="message.expandedSourceId === source.id" class="source-content">
                      <div class="source-content-title">知识库原文片段</div>
                      <div>{{ source.content || '暂无可展示的原文片段。' }}</div>
                    </div>
                  </div>
                </div>
              </div>

              <div
                v-if="message.followUps?.length && (message.modeKey !== 'data-query' || dataQueryAccessStatus === 'covered')"
                class="data-query-followups"
              >
                <div class="data-query-exploration-title">你还可以继续追问</div>
                <div class="data-query-followup-list">
                  <button
                    v-for="followUp in message.followUps"
                    :key="followUp"
                    type="button"
                    class="data-query-followup"
                    @click="sendMessage(followUp)"
                  >
                    {{ followUp }}
                  </button>
                </div>
              </div>

              <div v-if="canUseMessageTools(message)" class="message-toolbar">
                <button type="button" @click="copyMessageContent(message)">
                  {{ copiedMessageId === message.id ? '已复制' : '复制' }}
                </button>
                <button type="button" @click="exportMessageAsMarkdown(message)">
                  导出 Markdown
                </button>
                <button type="button" @click="exportMessageAsWordHtml(message)">
                  导出 Word
                </button>
              </div>
            </div>
          </div>
        </section>

        <div v-if="!hasMessages && currentMode.key !== 'data-query'" class="recommend-heading">为你推荐</div>
        <div v-if="!hasMessages && currentMode.key !== 'data-query'" class="recommend-list" aria-label="推荐问法">
          <button
            v-for="question in currentMode.suggestions"
            :key="question"
            class="question-chip"
            type="button"
            @click="handleRecommendClick(question)"
          >
            <span>{{ question }}</span>
            <span aria-hidden="true">›</span>
          </button>
        </div>

        <div v-if="!hasMessages && currentMode.key !== 'data-query'" class="capability-list" aria-label="助手能力">
          <span
            v-for="(capability, index) in currentMode.capabilities"
            :key="capability"
            class="capability-item"
            :class="capabilityClassNames[index] || 'capability-blue'"
          >
            <span aria-hidden="true">{{ ['?', '⌖', '⇄'][index] || '?' }}</span>
            {{ capability }}
          </span>
        </div>

      </div>
    </main>

    <footer class="assistant-footer">
      <form class="input-shell" @submit.prevent="sendMessage()">
        <span class="input-attach" aria-hidden="true">↵</span>
        <input
          v-model="inputValue"
          type="text"
          :placeholder="dataQueryInputPlaceholder"
          :aria-label="dataQueryInputPlaceholder"
          :disabled="isChatBusy || (currentMode.key === 'data-query' && dataQueryAccessStatus !== 'covered')"
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
