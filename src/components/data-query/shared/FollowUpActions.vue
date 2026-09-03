<script setup>
import { computed } from 'vue'

const props = defineProps({
  followUps: { type: Array, default: () => [] },
  disabled: { type: Boolean, default: false },
})

const emit = defineEmits(['select'])

const normalizedFollowUps = computed(() => (Array.isArray(props.followUps) ? props.followUps : []).slice(0, 6).map((followUp, index) => {
  if (typeof followUp === 'string') {
    const label = followUp.trim()
    return label ? { id: `follow-up-${index}`, label, query: label } : null
  }
  if (!followUp || typeof followUp !== 'object' || Array.isArray(followUp)) return null
  const label = String(followUp.label || followUp.title || followUp.query || '').trim()
  const query = String(followUp.query || followUp.prompt || label).trim()
  return label && query
    ? { id: String(followUp.id || `follow-up-${index}`), label, query }
    : null
}).filter(Boolean))
</script>

<template>
  <div v-if="normalizedFollowUps.length" class="data-query-followups" aria-label="继续分析">
    <span>继续分析</span>
    <button
      v-for="followUp in normalizedFollowUps"
      :key="followUp.id"
      type="button"
      :disabled="disabled"
      @click="emit('select', followUp)"
    >
      {{ followUp.label }}
    </button>
  </div>
</template>
