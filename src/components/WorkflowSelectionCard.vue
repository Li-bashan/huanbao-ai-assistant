<script setup>
import { ChevronRight } from '@lucide/vue'

defineProps({
  groups: {
    type: Array,
    default: () => [],
  },
})

const emit = defineEmits(['select'])

const getStatusLabel = (status) => {
  if (status === 'verified') return '已开放'
  if (status === 'unsupported') return '暂不支持'
  return '待实测'
}

const selectWorkflow = (workflow) => {
  if (workflow) emit('select', workflow)
}
</script>

<template>
  <section class="workflow-selection-card" aria-label="选择流程入口">
    <div class="workflow-selection-header">
      <span class="workflow-card-kicker">选择入口</span>
      <strong>请选择要打开的业务入口</strong>
      <p>点击具体名称后，我会继续展示入口状态和可执行操作。</p>
    </div>

    <div class="workflow-selection-groups">
      <div v-for="group in groups" :key="group.moduleName" class="workflow-selection-group">
        <div class="workflow-selection-group-title">{{ group.moduleName }}</div>
        <button
          v-for="workflow in group.workflows"
          :key="workflow.id"
          type="button"
          class="workflow-selection-option"
          @click="selectWorkflow(workflow)"
        >
          <span class="workflow-selection-option-copy">
            <strong>{{ workflow.workflowName }}</strong>
            <small>{{ workflow.description }}</small>
          </span>
          <span
            class="workflow-card-status"
            :class="`workflow-card-status-${workflow.status || 'pending'}`"
          >
            {{ getStatusLabel(workflow.status) }}
          </span>
          <ChevronRight :size="15" :stroke-width="2" aria-hidden="true" />
        </button>
      </div>
    </div>
  </section>
</template>
