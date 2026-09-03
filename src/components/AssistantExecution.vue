<script setup>
import { ChevronRight, CircleAlert, CircleCheck, LoaderCircle, PauseCircle } from '@lucide/vue'
import { computed } from 'vue'

const props = defineProps({
  process: { type: Object, required: true },
})

const emit = defineEmits(['toggle'])

const isActive = computed(() => ['pending', 'running'].includes(props.process.status))

const processStatusLabel = computed(() => {
  const labels = {
    pending: '准备中',
    running: '进行中',
    success: '已完成',
    failed: '未完成',
    stopped: '已停止',
    paused: '等待继续',
  }
  return labels[props.process.status] || '准备中'
})

const capabilities = computed(() => {
  if (Array.isArray(props.process.capabilities) && props.process.capabilities.length) {
    return props.process.capabilities
  }

  const modeKey = props.process.modeKey
  if (modeKey === 'data-query') return ['智能问数']
  if (['office', 'office-ai', 'general'].includes(modeKey)) return ['智能办公']
  if (modeKey === 'policy') return ['制度问答']
  return []
})

const isSuccess = computed(() => props.process.status === 'success')
const completionLabel = computed(() => `已调用：${capabilities.value.join(' · ')}`)

const stageLabel = computed(() => {
  if (props.process.status === 'failed') return '这次处理未完成'
  if (props.process.status === 'stopped') return '本次处理已停止'
  if (props.process.status === 'paused') return '等待继续处理'
  if (props.process.status === 'success') return completionLabel.value
  if (isActive.value) {
    if (props.process.stage) return props.process.stage
    if (capabilities.value.includes('制度问答')) return '正在检索制度依据'
    if (capabilities.value.includes('智能问数')) return '正在查询生产数据'
    if (capabilities.value.includes('智能办公') || capabilities.value.includes('办公智能')) return '正在整理办公材料'
    return '正在处理您的需求'
  }
  if (props.process.stage) return props.process.stage
  if (capabilities.value.includes('制度问答')) return '正在检索制度依据...'
  if (capabilities.value.includes('智能问数')) return '正在分析生产指标...'
  if (capabilities.value.includes('智能办公') || capabilities.value.includes('办公智能')) return '正在拟制办公材料...'
  return '正在处理您的需求...'
})

const statusIcon = computed(() => {
  if (props.process.status === 'failed') return CircleAlert
  if (props.process.status === 'stopped' || props.process.status === 'paused') return PauseCircle
  if (props.process.status === 'running' || props.process.status === 'pending') return LoaderCircle
  return CircleCheck
})
</script>

<template>
  <section
    v-if="process.visible"
    class="status-capsule"
    :class="[`status-capsule-${process.status}`, { 'status-capsule-expanded': process.expanded }]"
    aria-label="助手处理状态"
  >
    <button
      type="button"
      class="status-capsule-header"
      :aria-expanded="isActive ? undefined : process.expanded"
      :aria-label="isActive ? `${stageLabel}，请稍候` : isSuccess ? stageLabel : `${stageLabel}，${processStatusLabel}`"
      :disabled="isActive"
      @click="emit('toggle')"
    >
      <span
        class="status-capsule-dot"
        :class="{ 'status-capsule-dot-pulsing': process.status === 'running' || process.status === 'pending' }"
        aria-hidden="true"
      ></span>
      <component
        :is="statusIcon"
        class="status-capsule-icon"
        :class="{ 'status-capsule-icon-spinning': process.status === 'running' || process.status === 'pending' }"
        :size="15"
        :stroke-width="2"
        aria-hidden="true"
      />
      <span class="status-capsule-title">{{ stageLabel }}</span>
      <span v-if="!isSuccess" class="status-capsule-status">{{ isActive ? '请稍候' : processStatusLabel }}</span>
      <ChevronRight
        v-if="!isActive"
        class="status-capsule-chevron"
        :class="{ 'status-capsule-chevron-expanded': process.expanded }"
        :size="16"
        :stroke-width="2"
        aria-hidden="true"
      />
    </button>

    <Transition name="status-capsule-collapse">
      <div v-if="process.expanded && !isActive" class="status-capsule-body">
        <div class="status-capsule-capability-label">已调用能力</div>
        <div v-if="capabilities.length" class="status-capsule-capabilities">
          <span v-for="capability in capabilities" :key="capability" class="status-capsule-capability">
            {{ capability }}
          </span>
        </div>
      </div>
    </Transition>
  </section>
</template>
