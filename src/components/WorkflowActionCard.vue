<script setup>
import { ArrowUpRight, LoaderCircle } from '@lucide/vue'
import { ref } from 'vue'

defineProps({
  card: {
    type: Object,
    required: true,
  },
})

const emit = defineEmits(['action'])
const loadingAction = ref('')

const triggerAction = (action) => {
  if (loadingAction.value) return

  loadingAction.value = action
  emit('action', action)
  window.setTimeout(() => {
    if (loadingAction.value === action) loadingAction.value = ''
  }, 900)
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

    <div class="workflow-section">
      <div class="workflow-section-title">需要准备</div>
      <ul v-if="card.requiredFields?.length" class="workflow-field-list">
        <li v-for="field in card.requiredFields" :key="field">{{ field }}</li>
      </ul>
      <p v-else class="workflow-empty-fields">暂无必填字段。</p>
    </div>
  </section>
</template>
