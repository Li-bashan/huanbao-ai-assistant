<script setup>
import { RefreshCw } from '@lucide/vue'
import { computed, ref, watch } from 'vue'
import { getRecommendedStarters } from '../config/starters.config.ts'

const props = defineProps({
  mode: { type: [String, Object], default: 'policy' },
  orgName: { type: String, default: '' },
  sessionKey: { type: String, default: '' },
  disabled: { type: Boolean, default: false },
})

const emit = defineEmits(['select'])
const emptyStateStarters = ref([])
const batchKey = ref(0)
const isRefreshing = ref(false)

const modeKey = computed(() => (typeof props.mode === 'object' ? props.mode?.key : props.mode))

const refreshStarters = () => {
  emptyStateStarters.value = getRecommendedStarters(modeKey.value, props.orgName)
  batchKey.value += 1
}

const shuffleStarters = () => {
  if (props.disabled) return
  isRefreshing.value = true
  refreshStarters()
  window.setTimeout(() => {
    isRefreshing.value = false
  }, 180)
}

const selectStarter = (starter) => {
  if (props.disabled) return
  emit('select', starter.prompt, starter)
}

watch(() => [modeKey.value, props.orgName, props.sessionKey], refreshStarters, { immediate: true })
</script>

<template>
  <section class="prompt-starters" aria-label="试一试推荐">
    <div class="prompt-starters-head">
      <div>
        <span class="prompt-starters-title">试一试</span>
        <span class="prompt-starters-note">点击即可开始对话</span>
      </div>
      <button
        type="button"
        class="prompt-starters-shuffle"
        :disabled="disabled"
        title="换一批推荐"
        @click="shuffleStarters"
      >
        <RefreshCw :size="14" :class="{ 'prompt-starters-shuffle-spinning': isRefreshing }" aria-hidden="true" />
        <span>换一批</span>
      </button>
    </div>

    <TransitionGroup name="prompt-starter" tag="div" class="prompt-starters-grid" :key="batchKey">
      <button
        v-for="starter in emptyStateStarters"
        :key="starter.id"
        type="button"
        class="prompt-starter-card"
        :class="`prompt-starter-${starter.themeColor}`"
        :disabled="disabled"
        :aria-label="`发送推荐问题：${starter.prompt}`"
        title="点击后立即发送这条推荐问题"
        @click="selectStarter(starter)"
      >
        <span class="prompt-starter-tag">{{ starter.category }}</span>
        <span class="prompt-starter-copy">{{ starter.prompt }}</span>
        <span v-if="starter.isCompound" class="prompt-starter-compound">复合任务</span>
      </button>
    </TransitionGroup>
  </section>
</template>
