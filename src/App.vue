<script setup>
import { computed, nextTick, ref } from 'vue'
import { sendChatMessage } from './services/chatApi'

const recommendQuestions = [
  '采购请示单在哪里发起？',
  '合同评审卡片如何使用？',
  '如何查看我的待办任务？',
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
    }
  } catch {
    const loadingMessage = messages.value.find((message) => message.id === loadingMessageId)
    if (loadingMessage) {
      loadingMessage.content = '当前服务暂时不可用，请稍后重试。'
      loadingMessage.loading = false
    }
  }

  await scrollToBottom()
}

const handleRecommendClick = (question) => {
  sendMessage(question)
}

const newChat = async () => {
  messages.value = []
  inputValue.value = ''
  conversationId.value = ''
  await scrollToBottom()
}
</script>

<template>
  <section class="ai-assistant" aria-label="环宝智能问答助手">
    <header class="assistant-header">
      <div class="brand">
        <div class="assistant-avatar" aria-hidden="true">
          <img src="/huanbao-avatar.png" alt="" />
        </div>
        <div class="brand-copy">
          <h1>环宝智能问答助手</h1>
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
            <span class="welcome-tag">智慧办公 AI 助手</span>
            <h2>您好，我是环宝智能问答助手。</h2>
            <p>可为您解答智慧办公使用问题、定位业务表单入口，并指引流程办理路径。</p>
          </div>
        </section>

        <section class="message-list" aria-label="对话消息">
          <div v-if="!hasMessages" class="message-row message-row-assistant">
            <span class="message-avatar" aria-hidden="true">
              <img src="/huanbao-avatar.png" alt="" />
            </span>
            <div class="message message-assistant">很高兴为您服务！您可以试着问我：</div>
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
              {{ message.content }}
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
          <span class="capability-item capability-blue"><span aria-hidden="true">?</span>知识问答</span>
          <span class="capability-item capability-green"><span aria-hidden="true">⌖</span>表单定位</span>
          <span class="capability-item capability-purple"><span aria-hidden="true">⇄</span>流程指引</span>
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
          placeholder="请输入您的问题..."
          aria-label="请输入您的问题"
        />
        <button class="send-button" type="submit">发送</button>
      </form>
    </footer>
  </section>
</template>
