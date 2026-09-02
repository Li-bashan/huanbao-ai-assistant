<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { Check, ChevronDown, Sparkles } from '@lucide/vue'

const props = defineProps({
  modes: {
    type: Array,
    default: () => [],
  },
  currentModeKey: {
    type: String,
    default: '',
  },
  open: {
    type: Boolean,
    default: false,
  },
  disabled: {
    type: Boolean,
    default: false,
  },
  adaptive: {
    type: Boolean,
    default: false,
  },
})

const emit = defineEmits(['update:open', 'select'])
const selectorRef = ref(null)
const menuRef = ref(null)
const popoverStyle = ref({})

const currentMode = computed(
  () => props.modes.find((mode) => mode.key === props.currentModeKey) || props.modes[0],
)
const currentModeLabel = computed(() => (props.adaptive ? '智能自适应' : currentMode.value?.label || ''))

const close = () => emit('update:open', false)

const repositionPopover = async () => {
  await nextTick()

  const selector = selectorRef.value
  const menu = menuRef.value
  if (!selector || !menu) return

  const container = selector.closest('.ai-assistant') || document.documentElement
  const selectorRect = selector.getBoundingClientRect()
  const containerRect = container.getBoundingClientRect()
  const boundaryPadding = 8
  const menuWidth = menu.getBoundingClientRect().width
  const minLeft = containerRect.left + boundaryPadding
  const maxRight = containerRect.right - boundaryPadding
  let left = selectorRect.width - menuWidth
  let menuLeft = selectorRect.left + left

  if (menuLeft < minLeft) {
    left += minLeft - menuLeft
    menuLeft = minLeft
  }

  if (menuLeft + menuWidth > maxRight) {
    left -= menuLeft + menuWidth - maxRight
  }

  popoverStyle.value = {
    left: `${Math.round(left)}px`,
    right: 'auto',
  }
}

const openAndFocusCurrent = async () => {
  if (props.disabled) return

  emit('update:open', true)
  await repositionPopover()
  const focusSelector = props.adaptive
    ? '.mode-option-adaptive'
    : '.mode-option.active'
  selectorRef.value?.querySelector(focusSelector)?.focus()
}

const toggle = () => {
  if (props.open) {
    close()
    return
  }

  openAndFocusCurrent()
}

const selectMode = (modeKey) => {
  emit('select', modeKey)
  close()
}

const handleOutsidePointer = (event) => {
  if (props.open && !selectorRef.value?.contains(event.target)) close()
}

const handleKeydown = (event) => {
  if (!props.open) return

  if (event.key === 'Escape') {
    event.preventDefault()
    close()
    selectorRef.value?.querySelector('.mode-current-button')?.focus()
  }
}

const handleResize = () => {
  if (props.open) repositionPopover()
}

onMounted(() => {
  document.addEventListener('pointerdown', handleOutsidePointer)
  document.addEventListener('keydown', handleKeydown)
  window.addEventListener('resize', handleResize)
})

onUnmounted(() => {
  document.removeEventListener('pointerdown', handleOutsidePointer)
  document.removeEventListener('keydown', handleKeydown)
  window.removeEventListener('resize', handleResize)
})
</script>

<template>
  <div ref="selectorRef" class="mode-switch" :class="{ 'mode-switch-disabled': disabled }">
    <span class="capability-count" aria-hidden="true">智能能力 · {{ modes.length }}项</span>
    <button
      class="mode-current-button"
      type="button"
      :disabled="disabled"
      :aria-expanded="open"
      aria-haspopup="menu"
      aria-controls="assistant-capability-menu"
      :title="disabled ? '当前正在处理请求' : `当前能力：${currentModeLabel}`"
      @click="toggle"
      @keydown.arrow-down.prevent="openAndFocusCurrent"
    >
      <Sparkles
        v-if="adaptive"
        class="mode-current-icon"
        :size="15"
        :stroke-width="1.9"
        aria-hidden="true"
      />
      <component
        :is="currentMode?.icon"
        v-else-if="currentMode?.icon"
        class="mode-current-icon"
        :size="15"
        :stroke-width="1.9"
        aria-hidden="true"
      />
      <span class="mode-current-label">{{ currentModeLabel }}</span>
      <ChevronDown class="mode-caret" :size="14" :stroke-width="2" aria-hidden="true" />
    </button>

    <div
      v-if="open"
      ref="menuRef"
      id="assistant-capability-menu"
      class="mode-popover"
      role="menu"
      aria-label="智能能力"
      :style="popoverStyle"
    >
      <div class="mode-popover-head">
        <strong>智能能力</strong>
        <span>已接入 {{ modes.length }} 项专业 AI 能力</span>
      </div>
      <button
        class="mode-option mode-option-adaptive"
        :class="{ active: adaptive }"
        type="button"
        role="menuitemradio"
        :aria-checked="adaptive"
        @click="selectMode('adaptive')"
      >
        <Sparkles class="mode-option-icon" :size="16" :stroke-width="1.9" aria-hidden="true" />
        <span class="mode-option-copy">
          <span class="mode-option-title">智能自适应</span>
          <span class="mode-option-desc">自动识别并选择合适能力</span>
        </span>
        <Check v-if="adaptive" class="mode-option-check" :size="15" :stroke-width="2.2" aria-hidden="true" />
      </button>
      <button
        v-for="mode in modes"
        :key="mode.key"
        class="mode-option"
        :class="{ active: !adaptive && currentModeKey === mode.key }"
        type="button"
        role="menuitemradio"
        :aria-checked="!adaptive && currentModeKey === mode.key"
        @click="selectMode(mode.key)"
      >
        <component
          :is="mode.icon"
          class="mode-option-icon"
          :size="16"
          :stroke-width="1.9"
          aria-hidden="true"
        />
        <span class="mode-option-copy">
          <span class="mode-option-title">{{ mode.label }}</span>
          <span class="mode-option-desc">{{ mode.desc || mode.shortDescription }}</span>
        </span>
        <Check
          v-if="!adaptive && currentModeKey === mode.key"
          class="mode-option-check"
          :size="15"
          :stroke-width="2.2"
          aria-hidden="true"
        />
      </button>
    </div>
  </div>
</template>
