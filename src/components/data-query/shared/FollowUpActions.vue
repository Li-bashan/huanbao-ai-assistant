<script setup>
import { computed } from 'vue'

const props = defineProps({
  followUps: { type: Array, default: () => [] },
  disabled: { type: Boolean, default: false },
})

const emit = defineEmits(['select'])

const SUPPORTED_ACTIONS = new Set([
  'VIEW_TREND',
  'TREND',
  'VIEW_RANKING',
  'RANKING',
  'VIEW_DETAILS',
  'DETAILS',
  'COMPARE_YOY',
  'YOY',
  'COMPARE_MOM',
  'MOM',
  'VIEW_TOP_N',
  'VIEW_NEXT_N',
])
const UNSUPPORTED_INTENT_PATTERN = /(分布|下钻|诊断|归因|相关性|异常|热力|漏斗|雷达|散点)/
const SUPPORTED_TEXT_PATTERN = /(趋势|排名|排行|明细|同比|环比|前\s*\d+|后\s*\d+|上月|去年同期)/

const normalizeAction = (followUp) => String(followUp?.action || followUp?.intent || followUp?.type || '').trim().toUpperCase()

const isSupportedFollowUp = (followUp) => {
  const action = normalizeAction(followUp)
  if (action) return SUPPORTED_ACTIONS.has(action)
  const text = typeof followUp === 'string'
    ? followUp.trim()
    : `${followUp?.label || ''} ${followUp?.query || followUp?.prompt || ''}`.trim()
  return SUPPORTED_TEXT_PATTERN.test(text) && !UNSUPPORTED_INTENT_PATTERN.test(text)
}

const normalizedFollowUps = computed(() => (Array.isArray(props.followUps) ? props.followUps : []).map((followUp, index) => {
  if (typeof followUp === 'string') {
    const label = followUp.trim()
    return label ? { id: `follow-up-${index}`, label, query: label } : null
  }
  if (!followUp || typeof followUp !== 'object' || Array.isArray(followUp)) return null
  const label = String(followUp.label || followUp.title || followUp.query || '').trim()
  const query = String(followUp.query || followUp.prompt || label).trim()
  return label && query
    ? { id: String(followUp.id || `follow-up-${index}`), label, query, action: normalizeAction(followUp) }
    : null
}).filter(Boolean).filter(isSupportedFollowUp).slice(0, 6))
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
