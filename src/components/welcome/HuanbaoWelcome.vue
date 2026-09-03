<script setup>
import { computed } from 'vue'

const props = defineProps({
  userName: { type: String, default: '' },
  dataQueryAccessStatus: { type: String, default: 'idle' },
  disabled: { type: Boolean, default: false },
})

const emit = defineEmits(['select', 'access-blocked'])

const capabilityCards = [
  {
    key: 'policy',
    icon: '📖',
    title: '规章制度查询',
    questions: ['差旅住宿报销标准', '公务用车管理规定'],
  },
  {
    key: 'data-query',
    icon: '📊',
    title: '生产智能问数',
    questions: ['各电厂发电量查询', '项目公司生产排名'],
  },
  {
    key: 'office-ai',
    icon: '📝',
    title: '办公公文起草',
    questions: ['起草系统维护通知', '规范会议纪要格式'],
  },
  {
    key: 'workflow',
    icon: '⚡',
    title: '业务流程指引',
    questions: ['打开采购请示单', '查阅我的待办任务'],
  },
]

const greeting = computed(() =>
  props.userName ? `您好，${props.userName}！我是环宝 AI 智能助手` : '您好！我是环宝 AI 智能助手',
)

const dataQueryNeedsAuthorization = computed(() => props.dataQueryAccessStatus === 'not-covered')

const dataQueryStatusLabel = computed(() => {
  if (dataQueryNeedsAuthorization.value) return '🔒 需授权'
  if (props.dataQueryAccessStatus === 'checking') return '核验中'
  return ''
})

const isCardBlocked = (card) => card.key === 'data-query' && dataQueryNeedsAuthorization.value

const selectCapability = (card) => {
  if (props.disabled) return
  if (isCardBlocked(card)) {
    emit('access-blocked')
    return
  }

  emit('select', card.questions[0], { modeKey: card.key, direct: false })
}

const selectQuestion = (card, question) => {
  if (props.disabled) return
  if (isCardBlocked(card)) {
    emit('access-blocked')
    return
  }

  emit('select', question, { modeKey: card.key, direct: true })
}

const handleCardKeydown = (event, card) => {
  if (event.key === 'Enter' || event.key === ' ') {
    event.preventDefault()
    selectCapability(card)
  }
}
</script>

<template>
  <section class="huanbao-welcome" aria-label="环宝 AI 智能助手欢迎区">
    <div class="huanbao-welcome-copy">
      <span class="huanbao-welcome-tag">智能自适应</span>
      <h2>{{ greeting }}</h2>
      <p>已接入规章制度知识库、办公公文助手、生产智能问数及业务办理流程。</p>
    </div>

    <div class="huanbao-capability-grid" aria-label="环宝 AI 智能助手四项能力">
      <article
        v-for="card in capabilityCards"
        :key="card.key"
        class="huanbao-capability-card"
        :class="{ 'is-locked': isCardBlocked(card) }"
        :aria-disabled="isCardBlocked(card)"
        :tabindex="disabled ? -1 : 0"
        :aria-label="`${card.title}${isCardBlocked(card) ? '，需授权' : ''}`"
        @click="selectCapability(card)"
        @keydown="handleCardKeydown($event, card)"
      >
        <div class="huanbao-capability-head">
          <span class="huanbao-capability-icon" aria-hidden="true">{{ card.icon }}</span>
          <h3>{{ card.title }}</h3>
          <span v-if="card.key === 'data-query' && dataQueryStatusLabel" class="huanbao-capability-status">
            {{ dataQueryStatusLabel }}
          </span>
        </div>
        <div class="huanbao-capability-questions">
          <button
            v-for="question in card.questions"
            :key="question"
            type="button"
            class="huanbao-capability-question"
            :disabled="disabled"
            @click.stop="selectQuestion(card, question)"
          >
            <span aria-hidden="true">·</span>
            <span>{{ question }}</span>
          </button>
        </div>
      </article>
    </div>

    <p v-if="dataQueryNeedsAuthorization" class="huanbao-welcome-hint" role="status">
      生产智能问数需要授权，点击问数入口后可查看开通指引。
    </p>
  </section>
</template>
