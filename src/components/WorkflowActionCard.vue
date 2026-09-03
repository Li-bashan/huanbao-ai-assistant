<script setup>
import { ArrowUpRight, LoaderCircle } from '@lucide/vue'
import { computed, ref } from 'vue'
import { ENABLE_WORKFLOW_PREFILL } from '../config/workflowActions.js'
import { sendIgixAction } from '../utils/actionBridge.js'

const props = defineProps({
  card: {
    type: Object,
    required: true,
  },
})

const emit = defineEmits(['action'])
const loadingAction = ref('')
const bridgeState = ref('IDLE')
const bridgeActionId = ref('')
const bridgeErrorMessage = ref('')

const bridgeStateLabel = computed(() => {
  const labels = {
    IDLE: '待发送',
    SENDING: '正在发送',
    SENT: '已发出',
    WAITING_ACK: '等待门户响应',
    SUCCESS: '已确认',
    FAILED: '门户处理失败',
    TIMEOUT: '等待超时',
  }
  return labels[bridgeState.value] || '待发送'
})

const bridgeStateHint = computed(() => {
  if (bridgeState.value === 'SUCCESS') return '门户已返回确认，动作已被门户接收。'
  if (bridgeState.value === 'FAILED') {
    return bridgeErrorMessage.value || '门户未确认该动作，请检查门户权限或稍后重试。'
  }
  if (bridgeState.value === 'TIMEOUT') {
    return '未在 5 秒内收到门户确认，当前不能确认已办理。'
  }
  if (bridgeState.value === 'WAITING_ACK') return '已请求打开，等待门户处理。'
  if (bridgeState.value === 'SENT') return '已请求打开，等待门户处理。'
  return ''
})

const isBridgePending = computed(() => ['SENDING', 'SENT', 'WAITING_ACK'].includes(bridgeState.value))

const getActionPayload = (action) => {
  if (action.includes('预填') && !ENABLE_WORKFLOW_PREFILL) return null
  return (
    props.card.actionPayloads?.[action] ||
    (action.includes('预填') ? Object.values(props.card.actionPayloads || {})[0] : null)
  )
}

const handleBridgeState = (state, detail = {}) => {
  bridgeState.value = state
  bridgeActionId.value = detail.actionId || bridgeActionId.value
  bridgeErrorMessage.value = detail.errorMessage || ''
}

const triggerAction = async (action) => {
  if (loadingAction.value || isBridgePending.value) return

  const actionPayload = getActionPayload(action)
  loadingAction.value = action

  if (!actionPayload) {
    emit('action', action)
    window.setTimeout(() => {
      if (loadingAction.value === action) loadingAction.value = ''
    }, 900)
    return
  }

  bridgeState.value = 'SENDING'
  bridgeActionId.value = ''
  bridgeErrorMessage.value = ''

  try {
    await sendIgixAction(actionPayload, {
      status: props.card.status,
      onStateChange: handleBridgeState,
    })
  } catch (error) {
    handleBridgeState('FAILED', {
      errorMessage: '动作桥接异常，当前不能确认已办理。',
    })
  } finally {
    if (loadingAction.value === action) loadingAction.value = ''
  }
}
</script>

<template>
  <section class="workflow-card" aria-label="流程助手动作卡片">
    <div class="workflow-card-header">
      <div class="workflow-card-kicker-row">
        <span class="workflow-card-kicker">已识别事项</span>
        <span
          v-if="card.status"
          class="workflow-card-status"
          :class="`workflow-card-status-${card.status}`"
        >
          {{ card.status === 'verified' ? '已验证入口' : '待确认入口' }}
        </span>
      </div>
      <strong>{{ card.workflowName }}</strong>
      <p>{{ card.description }}</p>
    </div>

    <div class="workflow-section">
      <div class="workflow-section-title">可执行操作</div>
      <div v-if="card.actions?.length" class="workflow-action-list">
        <button
          v-for="action in card.actions"
          :key="action"
          class="workflow-action-button"
          type="button"
          :disabled="Boolean(loadingAction)"
          :aria-busy="loadingAction === action"
          @click="triggerAction(action)"
        >
          <LoaderCircle v-if="loadingAction === action" class="workflow-action-button-icon workflow-action-button-icon-spinning" :size="15" aria-hidden="true" />
          <ArrowUpRight v-else class="workflow-action-button-icon" :size="15" aria-hidden="true" />
          <span>{{ action }}</span>
        </button>
      </div>
      <p v-else class="workflow-empty-fields">
        {{ card.unavailableReason || '该事项暂未配置可执行动作。' }}
      </p>
    </div>

    <div v-if="card.actions?.length" class="workflow-section" aria-live="polite">
      <div class="workflow-section-title">动作状态</div>
      <div
        class="workflow-card-status"
        :class="bridgeState === 'SUCCESS' ? 'workflow-card-status-verified' : 'workflow-card-status-pending'"
      >
        {{ bridgeStateLabel }}
      </div>
      <p v-if="bridgeStateHint" class="workflow-empty-fields">
        {{ bridgeStateHint }}
      </p>
      <p v-if="bridgeActionId" class="workflow-empty-fields">
        动作编号：{{ bridgeActionId }}
      </p>
    </div>

    <div class="workflow-section">
      <div class="workflow-section-title">需要准备</div>
      <ul v-if="card.requiredFields?.length" class="workflow-field-list">
        <li v-for="field in card.requiredFields" :key="field">{{ field }}</li>
      </ul>
      <p v-else class="workflow-empty-fields">暂无必填字段。</p>
    </div>
  </section>
</template>
