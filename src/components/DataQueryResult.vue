<script setup>
import { computed } from 'vue'
import AccessBlockedView from './data-query/views/AccessBlockedView.vue'
import LoadingSkeleton from './data-query/views/LoadingSkeleton.vue'
import ProtocolFallbackView from './data-query/views/ProtocolFallbackView.vue'
import GenericAnalysisView from './data-query/views/GenericAnalysisView.vue'

const EMPTY_STATE_TEXT = '当前统计期间暂无可用数据。'
const PROTOCOL_ERROR_TEXT = '结果协议校验失败，请稍后重试。'
const NO_DATA_STATUSES = new Set([
  'NO_DATA',
  'NO_DATA_IN_PERIOD',
  'SUCCESS_EMPTY',
  'EMPTY',
])
const ACCESS_DENIED_STATUSES = new Set([
  'ACCESS_DENIED',
  'DATA_QUERY_NOT_COVERED',
  'NO_ACCESS',
  'IDENTITY_UNVERIFIED',
])

const isPlainObject = (value) =>
  value !== null && typeof value === 'object' && !Array.isArray(value)

const readText = (value) => String(value ?? '').trim()

const props = defineProps({
  // `data` is the canonical v2 input. `protocol` remains for the current App.vue
  // message shape until that caller is migrated to the canonical prop name.
  data: { type: Object, default: null },
  answer: { type: String, default: '' },
  protocol: { type: Object, default: null },
  chartOption: { type: Object, default: null },
  loading: { type: Boolean, default: false },
  accessDenied: { type: Boolean, default: false },
  accessStatus: { type: String, default: '' },
  accessDeniedReason: { type: String, default: '' },
  windowView: { type: String, default: 'compact' },
})

const emit = defineEmits(['follow-up', 'clarification'])

const sourceData = computed(() => {
  if (isPlainObject(props.data)) return props.data
  if (isPlainObject(props.protocol)) return props.protocol
  return {}
})

const normalizedPayload = computed(() => {
  const source = sourceData.value
  const rawContent = isPlainObject(source.content) ? source.content : {}

  return {
    protocolVersion: readText(source.protocolVersion || source.protocol_version),
    protocolValid: source.protocolValid === true,
    status: readText(source.status).toUpperCase(),
    messageType: readText(source.messageType || source.message_type).toLowerCase(),
    analysisType: readText(source.analysisType || source.analysis_type).toUpperCase(),
    clarification: isPlainObject(source.clarification) ? source.clarification : null,
    meta: isPlainObject(source.meta) ? source.meta : {},
    // Keep all business fields nested under content. No meta period label is inferred.
    content: {
      title: readText(rawContent.title || rawContent.heading),
      summary: readText(rawContent.summary || rawContent.answer || rawContent.text || rawContent.description),
      metrics: Array.isArray(rawContent.metrics) ? rawContent.metrics : [],
      table: isPlainObject(rawContent.table) ? rawContent.table : null,
      chart: isPlainObject(rawContent.chart) ? rawContent.chart : null,
      insights: Array.isArray(rawContent.insights) ? rawContent.insights : [],
      evidence: Array.isArray(rawContent.evidence) ? rawContent.evidence : [],
      dataInfo: isPlainObject(rawContent.dataInfo) ? rawContent.dataInfo : {},
      followUps: Array.isArray(rawContent.followUps)
        ? rawContent.followUps
        : Array.isArray(rawContent.follow_ups)
          ? rawContent.follow_ups
          : [],
    },
  }
})

const status = computed(() => normalizedPayload.value.status)
const isNoData = computed(() => NO_DATA_STATUSES.has(status.value))
const isAccessDenied = computed(() => {
  const source = sourceData.value
  return Boolean(
    props.accessDenied ||
      source.accessDenied === true ||
      source.access?.denied === true ||
      source.meta?.accessDenied === true ||
      ACCESS_DENIED_STATUSES.has(status.value) ||
      ['denied', 'not-covered'].includes(readText(props.accessStatus).toLowerCase()),
  )
})

const accessDeniedReason = computed(() => {
  const source = sourceData.value
  return (
    readText(props.accessDeniedReason) ||
    readText(source.accessDeniedReason || source.access?.reason || source.message) ||
    '您所在部门暂不支持生产指标智能问数，如有业务需要，请联系管理员申请。'
  )
})

const loading = computed(() =>
  props.loading || ['LOADING', 'PENDING', 'PROCESSING', 'IN_PROGRESS'].includes(status.value),
)

const isValidProtocol = computed(() =>
  normalizedPayload.value.protocolVersion === '2.0' &&
  normalizedPayload.value.protocolValid === true,
)

const resolvedViewComponent = computed(() => GenericAnalysisView)

const fallbackText = computed(() => {
  if (isNoData.value) return normalizedPayload.value.content.summary || EMPTY_STATE_TEXT
  return normalizedPayload.value.content.summary || props.answer || PROTOCOL_ERROR_TEXT
})
</script>

<template>
  <div class="data-query-result-root">
    <!-- 1. 未授权阻断 -->
    <AccessBlockedView v-if="isAccessDenied" :reason="accessDeniedReason" />

    <!-- 2. 加载骨架屏 -->
    <LoadingSkeleton v-else-if="loading" />

    <!-- 3. 结构化协议分发：当前阶段统一进入通用分析视图。 -->
    <component
      :is="resolvedViewComponent"
      v-else-if="isValidProtocol"
      :content="normalizedPayload.content"
      :meta="normalizedPayload.meta"
      :data-info="normalizedPayload.content?.dataInfo"
      :status="normalizedPayload.status"
      :message-type="normalizedPayload.messageType"
      :analysis-type="normalizedPayload.analysisType"
      :clarification="normalizedPayload.clarification"
      :chart-option="chartOption"
      :window-view="windowView"
      @follow-up="emit('follow-up', $event)"
      @clarification="emit('clarification', $event)"
    />

    <!-- 4. 降级或非结构化文本 -->
    <ProtocolFallbackView v-else :raw-text="fallbackText" />
  </div>
</template>

<style scoped>
.data-query-result-root {
  width: 100%;
  min-width: 0;
}
</style>
