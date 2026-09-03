<script setup>
defineProps({
  reason: { type: String, default: '' },
})
</script>

<template>
  <section class="data-query-access-state data-query-access-state-not-covered" aria-live="polite" aria-label="智能问数权限阻断">
    <div class="data-query-access-state-icon" aria-hidden="true">🔒</div>
    <h2>暂未开放智能问数</h2>
    <p>{{ reason || '当前账号暂不在智能问数开放范围内。' }}</p>
  </section>
</template>

<style scoped>
.data-query-access-state {
  min-height: 142px;
}
</style>
