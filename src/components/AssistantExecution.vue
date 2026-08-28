<script setup>
import { ChevronRight, CircleAlert, CircleCheck, LoaderCircle, PauseCircle } from '@lucide/vue'
import { computed } from 'vue'

const props = defineProps({
  process: { type: Object, required: true },
})

const emit = defineEmits(['toggle'])

const processTitle = computed(() =>
  props.process.status === 'running' || props.process.status === 'pending'
    ? '正在执行的节点'
    : props.process.nodes?.at(-1)?.title || '执行节点',
)

const processStatusLabel = computed(() => {
  const labels = {
    pending: '准备中',
    running: '正在执行',
    success: '已完成',
    failed: '执行失败',
    stopped: '已停止',
    paused: '等待继续',
  }
  return labels[props.process.status] || '准备中'
})

const nodeStatusLabel = (status) => ({
  waiting: '等待',
  running: '执行中',
  retrying: '重试中',
  success: '已完成',
  failed: '失败',
  stopped: '已停止',
  paused: '已暂停',
}[status] || '等待')

const formatElapsed = (value) => {
  const seconds = Number(value)
  if (!Number.isFinite(seconds)) return ''
  return seconds >= 1 ? `${seconds.toFixed(3)} s` : `${(seconds * 1000).toFixed(3)} ms`
}

const nodeIcon = (status) => {
  if (status === 'failed') return CircleAlert
  if (status === 'stopped') return PauseCircle
  if (status === 'paused') return PauseCircle
  if (status === 'running' || status === 'retrying') return LoaderCircle
  return CircleCheck
}
</script>

<template>
  <section
    v-if="process.visible"
    class="dify-execution"
    :class="[`dify-execution-${process.status}`, { 'dify-execution-expanded': process.expanded }]"
    aria-label="正在执行的节点"
  >
    <button
      type="button"
      class="dify-execution-header"
      :aria-expanded="process.expanded"
      @click="emit('toggle')"
    >
      <LoaderCircle
        v-if="process.status === 'running' || process.status === 'pending'"
        class="dify-execution-header-icon dify-execution-icon-spinning"
        :size="16"
        :stroke-width="2"
        aria-hidden="true"
      />
      <CircleAlert
        v-else-if="process.status === 'failed'"
        class="dify-execution-header-icon"
        :size="16"
        :stroke-width="2"
        aria-hidden="true"
      />
      <PauseCircle
        v-else-if="process.status === 'stopped' || process.status === 'paused'"
        class="dify-execution-header-icon"
        :size="16"
        :stroke-width="2"
        aria-hidden="true"
      />
      <CircleCheck
        v-else
        class="dify-execution-header-icon"
        :size="16"
        :stroke-width="2"
        aria-hidden="true"
      />
      <span class="dify-execution-title">{{ processTitle }}</span>
      <span class="dify-execution-status">{{ processStatusLabel }}</span>
      <ChevronRight
        class="dify-execution-chevron"
        :class="{ 'dify-execution-chevron-expanded': process.expanded }"
        :size="16"
        :stroke-width="2"
        aria-hidden="true"
      />
    </button>

    <Transition name="dify-execution-collapse">
      <div v-if="process.expanded" class="dify-execution-body">
        <div v-if="!process.nodes.length" class="dify-execution-empty">正在准备执行节点…</div>
        <div v-for="node in process.nodes" :key="node.key" class="dify-execution-node">
          <div class="dify-execution-node-row">
            <component
              :is="nodeIcon(node.status)"
              class="dify-execution-node-icon"
              :class="[
                `dify-execution-node-icon-${node.status}`,
                { 'dify-execution-icon-spinning': node.status === 'running' || node.status === 'retrying' },
              ]"
              :size="15"
              :stroke-width="2"
              :title="nodeStatusLabel(node.status)"
              aria-hidden="true"
            />
            <span class="dify-execution-node-title" :title="node.title">{{ node.title }}</span>
            <span class="dify-execution-node-status">{{ nodeStatusLabel(node.status) }}</span>
            <span v-if="node.elapsedTime !== null" class="dify-execution-node-time">
              {{ formatElapsed(node.elapsedTime) }}
            </span>
          </div>
          <div v-if="node.error" class="dify-execution-node-error">{{ node.error }}</div>
        </div>
      </div>
    </Transition>
  </section>
</template>
