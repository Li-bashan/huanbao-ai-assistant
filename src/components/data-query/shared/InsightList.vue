<script setup>
import { computed } from 'vue'

const props = defineProps({
  insights: { type: Array, default: () => [] },
})

const normalizedInsights = computed(() => (Array.isArray(props.insights) ? props.insights : []).slice(0, 8).map((insight) => {
  if (typeof insight === 'string') return { type: 'fact', text: insight.trim() }
  if (!insight || typeof insight !== 'object' || Array.isArray(insight)) return null
  return {
    type: String(insight.type || 'fact'),
    text: String(insight.text || insight.label || insight.description || '').trim(),
  }
}).filter((insight) => insight?.text))
</script>

<template>
  <ul v-if="normalizedInsights.length" class="data-query-insight-list" aria-label="关键发现">
    <li v-for="(insight, index) in normalizedInsights" :key="`${insight.type}-${insight.text}-${index}`">
      <span class="data-query-insight-mark">{{ insight.type === 'attention' ? '!' : '·' }}</span>
      <span>{{ insight.text }}</span>
    </li>
  </ul>
</template>
