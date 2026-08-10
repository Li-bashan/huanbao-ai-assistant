<script setup>
import {
  Expand,
  History,
  Maximize2,
  MessageSquarePlus,
  Minimize2,
  PanelRightClose,
  X,
} from '@lucide/vue'
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { assistantModes, defaultAssistantModeKey } from './config/assistantModes'
import { ENABLE_WORKFLOW_PREFILL, detectWorkflowAction } from './config/workflowActions'
import { sendChatMessage, streamChatMessage } from './services/chatApi'
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
import { renderMarkdown } from './utils/markdown'
import {
  copyText,
  exportMarkdown,
  exportWordHtml,
  getPlainMessageText,
} from './utils/messageExport'

const MODE_STORAGE_KEY = 'huanbao_current_mode'
const capabilityClassNames = ['capability-blue', 'capability-green', 'capability-purple']
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
const currentConversationId = ref(createConversationId())
const conversationHistory = ref([])
const showHistory = ref(false)
const showModeMenu = ref(false)
const historySearch = ref('')
const historyFilter = ref('all')
const copiedMessageId = ref('')
const currentUser = ref(null)
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
    ? `您好，${currentUser.value.name}，我是${currentMode.value.title}。`
    : currentMode.value.welcomeTitle,
)
const hasMessages = computed(() => messages.value.length > 0)
const isStreamingMode = computed(() => currentMode.value.key === 'office-ai')
const conversationTitle = computed(() => createConversationTitle(sanitizeMessages(messages.value)))
const historyFilters = [
  { key: 'all', label: '全部' },
  { key: 'policy', label: '制度' },
  { key: 'office-ai', label: '办公' },
  { key: 'workflow', label: '流程' },
]
let closeModeTimer = null
const assistantWindow = createIgixAssistantWindow()
let unbindWindowEscape = null
let unsubscribeWindowState = null

const createMessageId = () => Date.now() + Math.random()

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

const sendMessage = async (question = inputValue.value) => {
  const content = question.trim()
  if (!content) return

  const detectedIntent = detectIntent(content, { currentModeKey: currentMode.value.key })
  const routedMode = detectedIntent.modeKey ? getModeByKey(detectedIntent.modeKey) : currentMode.value

  if (detectedIntent.modeKey && routedMode.key !== currentMode.value.key) {
    currentModeKey.value = routedMode.key
    saveModeKey(routedMode.key)
    conversationId.value = ''
    showModeMenu.value = false
  }

  if (currentMode.value.key === 'workflow') {
    await sendWorkflowMessage(content)
    return
  }

  const loadingMessageId = createMessageId()

  messages.value.push({
    id: createMessageId(),
    role: 'user',
    content,
    loading: false,
  })
  messages.value.push({
    id: loadingMessageId,
    role: 'assistant',
    content: isStreamingMode.value ? '环宝正在生成中...' : '环宝正在思考中...',
    loading: true,
    streaming: isStreamingMode.value,
    sources: [],
    messageId: '',
    expandedSourceId: '',
  })

  inputValue.value = ''
  await scrollToBottom()

  try {
    const requestOptions = {
      conversationId: conversationId.value,
      apiMode: currentMode.value.apiMode,
      modeKey: currentMode.value.key,
    }

    const result = isStreamingMode.value
      ? await streamChatMessage(content, {
          ...requestOptions,
          onMessage: (messageContent, meta = {}) => {
            const streamingMessage = messages.value.find(
              (message) => message.id === loadingMessageId,
            )
            if (!streamingMessage) return

            if (meta.replace) {
              streamingMessage.content = messageContent || '环宝正在生成中...'
              scrollToBottom()
              return
            }

            if (
              streamingMessage.streaming &&
              streamingMessage.content === '环宝正在生成中...'
            ) {
              streamingMessage.content = ''
            }

            streamingMessage.content += messageContent
            scrollToBottom()
          },
        })
      : await sendChatMessage(content, requestOptions)

    conversationId.value = result.conversationId

    const loadingMessage = messages.value.find((message) => message.id === loadingMessageId)
    if (loadingMessage) {
      loadingMessage.content = result.answer || loadingMessage.content
      loadingMessage.loading = false
      loadingMessage.streaming = false
      loadingMessage.sources = result.sources || []
      loadingMessage.messageId = result.messageId || ''
      loadingMessage.expandedSourceId = ''
    }
  } catch (error) {
    console.error(error)
    const loadingMessage = messages.value.find((message) => message.id === loadingMessageId)
    if (loadingMessage) {
      loadingMessage.content = '当前服务暂时不可用，请稍后重试。'
      loadingMessage.loading = false
      loadingMessage.streaming = false
      loadingMessage.sources = []
      loadingMessage.messageId = ''
      loadingMessage.expandedSourceId = ''
    }
  }

  await scrollToBottom()
}

const handleRecommendClick = (question) => {
  sendMessage(question)
}

const canUseMessageTools = (message) =>
  message.role === 'assistant' && !message.loading && (message.content || message.workflowCard)

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
  conversationHistory.value = getConversationHistory()
  showHistory.value = !showHistory.value
  showModeMenu.value = false
}

const cancelCloseModeMenu = () => {
  if (closeModeTimer) {
    clearTimeout(closeModeTimer)
    closeModeTimer = null
  }
}

const openModeMenu = () => {
  cancelCloseModeMenu()
  showModeMenu.value = true
  showHistory.value = false
}

const scheduleCloseModeMenu = () => {
  cancelCloseModeMenu()
  closeModeTimer = setTimeout(() => {
    showModeMenu.value = false
    closeModeTimer = null
  }, 150)
}

const toggleModeMenu = () => {
  cancelCloseModeMenu()
  showModeMenu.value = !showModeMenu.value
  showHistory.value = false
}

const resetConversationState = async () => {
  messages.value = []
  inputValue.value = ''
  conversationId.value = ''
  currentConversationId.value = createConversationId()
  clearCurrentConversation()
  await scrollToBottom()
}

const switchMode = async (modeKey) => {
  const nextMode = getModeByKey(modeKey)
  cancelCloseModeMenu()
  showModeMenu.value = false

  if (nextMode.key === currentMode.value.key) return

  const activeConversation = saveActiveConversation()
  if (activeConversation) {
    conversationHistory.value = saveConversationToHistory(activeConversation)
  }

  currentModeKey.value = nextMode.key
  saveModeKey(nextMode.key)
  await resetConversationState()
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
  conversationId.value = conversation.conversationId || ''
  messages.value = sanitizeMessages(conversation.messages || [])
  showHistory.value = false
  showModeMenu.value = false

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
  conversationId.value = ''
  currentConversationId.value = createConversationId()
  showHistory.value = false
  showModeMenu.value = false
  clearCurrentConversation()
  await scrollToBottom()
}

onMounted(async () => {
  unbindWindowEscape = assistantWindow.bindEscape()
  unsubscribeWindowState = assistantWindow.onStateChange((state) => {
    windowState.value = state
  })
  assistantWindow.getState()

  currentUser.value = await getIgixCurrentUser()
  conversationHistory.value = getConversationHistory()
  const currentConversation = getCurrentConversation()

  if (currentConversation?.messages?.length) {
    currentModeKey.value = getModeByKey(currentConversation.modeKey || currentModeKey.value).key
    saveModeKey(currentModeKey.value)
    currentConversationId.value = currentConversation.id || createConversationId()
    conversationId.value = currentConversation.conversationId || ''
    messages.value = sanitizeMessages(currentConversation.messages)
    await scrollToBottom()
  }
})

onUnmounted(() => {
  if (unbindWindowEscape) unbindWindowEscape()
  if (unsubscribeWindowState) unsubscribeWindowState()
  assistantWindow.destroy()
})

watch(
  [messages, conversationId],
  () => {
    saveActiveConversation()
  },
  { deep: true },
)
</script>

<template>
  <section
    class="ai-assistant"
    :class="`assistant-view-${windowState.view || 'compact'}`"
    :aria-label="currentMode.title"
  >
    <header class="assistant-header">
      <div class="brand">
        <div class="assistant-avatar" aria-hidden="true">
          <img src="/huanbao-avatar.png" alt="" />
        </div>
        <div class="brand-copy">
          <h1>{{ currentMode.title }}</h1>
          <p><span class="status-dot"></span>在线服务中</p>
        </div>
      </div>

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
        <section class="welcome-card" aria-label="助手欢迎信息">
          <div class="welcome-copy">
            <span class="welcome-tag">{{ currentMode.badge }}</span>
            <h2>{{ welcomeTitle }}</h2>
            <p>{{ currentMode.welcomeDesc }}</p>
          </div>
        </section>

        <section class="message-list" aria-label="对话消息">
          <div v-if="!hasMessages" class="message-row message-row-assistant">
            <span class="message-avatar" aria-hidden="true">
              <img src="/huanbao-avatar.png" alt="" />
            </span>
            <div class="message message-assistant">{{ currentMode.guideText }}</div>
          </div>

          <div
            v-for="message in messages"
            :key="message.id"
            class="message-row"
            :class="message.role === 'user' ? 'message-row-user' : 'message-row-assistant'"
          >
            <span v-if="message.role === 'assistant'" class="message-avatar" aria-hidden="true">
              <img src="/huanbao-avatar.png" alt="" />
            </span>
            <div
              class="message"
              :class="message.role === 'user' ? 'message-user' : 'message-assistant'"
            >
              <div
                v-if="message.role === 'assistant'"
                class="markdown-content"
                v-html="renderMarkdown(message.content)"
              ></div>
              <template v-else>{{ message.content }}</template>

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

        <div v-if="!hasMessages" class="recommend-list" aria-label="推荐问法">
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

        <div v-if="!hasMessages" class="capability-list" aria-label="助手能力">
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
          :placeholder="currentMode.placeholder"
          :aria-label="currentMode.placeholder"
        />
        <button class="send-button" type="submit">发送</button>
      </form>
    </footer>
  </section>
</template>
