<script setup>
import { computed, nextTick, ref } from 'vue'
import { sendChatMessage } from './services/chatApi'
import { renderMarkdown } from './utils/markdown'

const recommendQuestions = [
  '差旅费包括哪些费用？',
  '出差住宿费和伙食补助标准是多少？',
  '公务用车如何申请？',
]

const messages = ref([])
const inputValue = ref('')
const chatBodyRef = ref(null)
const conversationId = ref('')
const hasMessages = computed(() => messages.value.length > 0)

const createMessageId = () => Date.now() + Math.random()

const scrollToBottom = async () => {
  await nextTick()
  if (chatBodyRef.value) {
    chatBodyRef.value.scrollTop = chatBodyRef.value.scrollHeight
  }
}

const sendMessage = async (question = inputValue.value) => {
  const content = question.trim()
  if (!content) return

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
    content: '环宝正在思考中...',
    loading: true,
    sources: [],
    messageId: '',
    expandedSourceId: '',
  })

  inputValue.value = ''
  await scrollToBottom()

  try {
    const result = await sendChatMessage(content, {
      conversationId: conversationId.value,
    })
    conversationId.value = result.conversationId

    const loadingMessage = messages.value.find((message) => message.id === loadingMessageId)
    if (loadingMessage) {
      loadingMessage.content = result.answer
      loadingMessage.loading = false
      loadingMessage.sources = result.sources || []
      loadingMessage.messageId = result.messageId || ''
      loadingMessage.expandedSourceId = ''
    }
  } catch {
    const loadingMessage = messages.value.find((message) => message.id === loadingMessageId)
    if (loadingMessage) {
      loadingMessage.content = '当前服务暂时不可用，请稍后重试。'
      loadingMessage.loading = false
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

const toggleSource = (message, sourceId) => {
  message.expandedSourceId = message.expandedSourceId === sourceId ? '' : sourceId
}

const newChat = async () => {
  messages.value = []
  inputValue.value = ''
  conversationId.value = ''
  await scrollToBottom()
}
</script>

<template>
  <section class="ai-assistant" aria-label="环宝制度问答助手">
    <header class="assistant-header">
      <div class="brand">
        <div class="assistant-avatar" aria-hidden="true">
          <img src="/huanbao-avatar.png" alt="" />
        </div>
        <div class="brand-copy">
          <h1>环宝制度问答助手</h1>
          <p><span class="status-dot"></span>在线服务中</p>
        </div>
      </div>

      <div class="header-actions" aria-label="助手操作">
        <button class="new-chat-button" type="button" @click="newChat">
          <span class="plus-icon" aria-hidden="true">+</span>
          新对话
        </button>
        <button class="expand-button" type="button" aria-label="展开助手">
          <span aria-hidden="true">⛶</span>
        </button>
      </div>
    </header>

    <main ref="chatBodyRef" class="chat-body">
      <div class="chat-content">
        <section class="welcome-card" aria-label="助手欢迎信息">
          <div class="welcome-copy">
            <span class="welcome-tag">公司制度知识库</span>
            <h2>您好，我是环宝制度问答助手。</h2>
            <p>可为您查询公司内部制度、管理办法和流程规范，支持差旅费、公务用车、审批要求、报销标准等制度问题解答。</p>
          </div>
        </section>

        <section class="message-list" aria-label="对话消息">
          <div v-if="!hasMessages" class="message-row message-row-assistant">
            <span class="message-avatar" aria-hidden="true">
              <img src="/huanbao-avatar.png" alt="" />
            </span>
            <div class="message message-assistant">很高兴为您服务！您可以这样查询制度：</div>
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
            </div>
          </div>
        </section>

        <div v-if="!hasMessages" class="recommend-list" aria-label="推荐问法">
          <button
            v-for="question in recommendQuestions"
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
          <span class="capability-item capability-blue"><span aria-hidden="true">?</span>制度查询</span>
          <span class="capability-item capability-green"><span aria-hidden="true">⌖</span>报销标准</span>
          <span class="capability-item capability-purple"><span aria-hidden="true">⇄</span>审批规则</span>
        </div>

        <div v-if="!hasMessages" class="empty-state">
          <strong>对话记录将显示在这里</strong>
          <span>您可以开始提问，助手将为您提供专业解答</span>
        </div>
      </div>
    </main>

    <footer class="assistant-footer">
      <form class="input-shell" @submit.prevent="sendMessage()">
        <span class="input-attach" aria-hidden="true">↵</span>
        <input
          v-model="inputValue"
          type="text"
          placeholder="请输入您要查询的制度问题..."
          aria-label="请输入您要查询的制度问题"
        />
        <button class="send-button" type="submit">发送</button>
      </form>
    </footer>
  </section>
</template>
