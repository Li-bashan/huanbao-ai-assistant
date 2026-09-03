<script setup>
defineProps({
  label: { type: String, default: '正在整理经营数据...' },
})
</script>

<template>
  <section class="data-query-loading-skeleton" role="status" aria-live="polite" aria-busy="true">
    <span class="data-query-loading-label">{{ label }}</span>
    <div class="data-query-loading-title skeleton-block"></div>
    <div class="data-query-loading-summary skeleton-block"></div>
    <div class="data-query-loading-metrics">
      <div v-for="index in 3" :key="index" class="data-query-loading-metric skeleton-block"></div>
    </div>
    <div class="data-query-loading-table skeleton-block"></div>
  </section>
</template>

<style scoped>
.data-query-loading-skeleton {
  display: grid;
  gap: 10px;
  margin: 10px 0;
  padding: 14px;
  border: 1px solid #e2ebf4;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.88);
}

.data-query-loading-label {
  color: #6d8195;
  font-size: 11px;
  font-weight: 700;
}

.skeleton-block {
  position: relative;
  overflow: hidden;
  border-radius: 7px;
  background: #edf3f8;
}

.skeleton-block::after {
  position: absolute;
  inset: 0;
  content: '';
  transform: translateX(-100%);
  background: linear-gradient(90deg, transparent, rgba(255, 255, 255, 0.78), transparent);
  animation: data-query-skeleton-shimmer 1.5s infinite;
}

.data-query-loading-title {
  width: 48%;
  height: 18px;
}

.data-query-loading-summary {
  width: 82%;
  height: 12px;
}

.data-query-loading-metrics {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 7px;
}

.data-query-loading-metric {
  height: 48px;
}

.data-query-loading-table {
  height: 132px;
}

@keyframes data-query-skeleton-shimmer {
  100% {
    transform: translateX(100%);
  }
}

@media (prefers-reduced-motion: reduce) {
  .skeleton-block::after {
    animation: none;
  }
}
</style>
