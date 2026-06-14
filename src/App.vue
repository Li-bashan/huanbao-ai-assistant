<script setup>
import { nextTick, ref } from 'vue'

const recommendQuestions = [
  '采购请示单在哪里发起？',
  '合同评审卡片如何使用？',
  '如何查看我的待办任务？',
]

const messages = ref([
  {
    id: 1,
    role: 'assistant',
    content: '很高兴为您服务！您可以试着问我：',
  },
])
const inputValue = ref('')
const chatBodyRef = ref(null)

const getAssistantReply = (question) => {
  if (question.includes('采购请示单')) {
    return '采购请示单可在招标采购模块中发起。后续接入系统后，可直接为您打开对应表单入口。'
  }

  if (question.includes('合同评审')) {
    return '合同评审卡片可在合同管理相关模块中查看和办理。后续可支持定位表单入口。'
  }

  if (question.includes('待办')) {
    return '您可以在门户首页“我的待办”区域查看待办事项，后续可支持一键跳转待办中心。'
  }

  return '已收到您的问题，后续将接入智慧办公知识库和流程服务进行回答。'
}

const scrollToBottom = async () => {
  await nextTick()
  if (chatBodyRef.value) {
    chatBodyRef.value.scrollTop = chatBodyRef.value.scrollHeight
  }
}

const sendMessage = async (question = inputValue.value) => {
  const content = question.trim()
  if (!content) return

  messages.value.push({
    id: Date.now(),
    role: 'user',
    content,
  })
  messages.value.push({
    id: Date.now() + 1,
    role: 'assistant',
    content: getAssistantReply(content),
  })

  inputValue.value = ''
  await scrollToBottom()
}

const handleRecommendClick = (question) => {
  sendMessage(question)
}

const newChat = async () => {
  messages.value = [
    {
      id: Date.now(),
      role: 'assistant',
      content: '很高兴为您服务！您可以试着问我：',
    },
  ]
  inputValue.value = ''
  await scrollToBottom()
}
</script>

<template>
  <section class="ai-assistant" aria-label="环宝智能问答助手">
    <header class="assistant-header">
      <div class="brand">
        <div class="assistant-avatar" aria-hidden="true">
          <span>环</span>
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
          <span aria-hidden="true">↗</span>
        </button>
      </div>
    </header>

    <main ref="chatBodyRef" class="chat-body">
      <section class="welcome-card" aria-label="助手欢迎信息">
        <div class="welcome-copy">
          <span class="welcome-tag">智慧办公 AI 助手</span>
          <h2>您好，我是环宝智能问答助手。</h2>
          <p>可为您解答智慧办公使用问题、定位业务表单入口，并指引流程办理路径。</p>
        </div>
        <div class="mini-avatar" aria-hidden="true">
          <div class="mini-sprout"></div>
          <span>环</span>
        </div>
      </section>

      <section class="message-list" aria-label="对话消息">
        <div
          v-for="message in messages"
          :key="message.id"
          class="message"
          :class="message.role === 'user' ? 'message-user' : 'message-assistant'"
        >
          {{ message.content }}
        </div>
      </section>

      <div class="recommend-list" aria-label="推荐问法">
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

      <div class="capability-list" aria-label="助手能力">
        <span class="capability-item"><span aria-hidden="true">□</span>知识问答</span>
        <span class="capability-item"><span aria-hidden="true">◇</span>表单定位</span>
        <span class="capability-item"><span aria-hidden="true">△</span>流程指引</span>
      </div>

      <div v-if="messages.length === 1" class="empty-state">
        <strong>对话记录将显示在这里</strong>
        <span>您可以开始提问，助手将为您提供专业解答</span>
      </div>
    </main>

    <footer class="assistant-footer">
      <form class="input-shell" @submit.prevent="sendMessage()">
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
