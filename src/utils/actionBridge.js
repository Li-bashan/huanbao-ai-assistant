import { isProxy, isRef, toRaw } from 'vue'

function createActionId() {
  if (globalThis.crypto?.randomUUID) {
    return globalThis.crypto.randomUUID()
  }

  return `igix_action_${Date.now()}_${Math.random().toString(16).slice(2)}`
}

function isPlainObject(value) {
  if (!value || typeof value !== 'object') return false
  const prototype = Object.getPrototypeOf(value)
  return prototype === Object.prototype || prototype === null
}

export function sanitizePayload(value) {
  if (isRef(value)) {
    return sanitizePayload(value.value)
  }

  const rawValue = isProxy(value) ? toRaw(value) : value

  if (
    rawValue === null ||
    typeof rawValue === 'string' ||
    typeof rawValue === 'number' ||
    typeof rawValue === 'boolean'
  ) {
    return rawValue
  }

  if (Array.isArray(rawValue)) {
    return rawValue
      .map((item) => sanitizePayload(item))
      .filter((item) => item !== undefined)
  }

  if (isPlainObject(rawValue)) {
    return Object.entries(rawValue).reduce((result, [key, item]) => {
      const sanitizedItem = sanitizePayload(item)
      if (sanitizedItem !== undefined) {
        result[key] = sanitizedItem
      }
      return result
    }, {})
  }

  return undefined
}

export function sendIgixAction(payload = {}, meta = {}) {
  const cleanPayload = sanitizePayload(payload) || {}
  const actionPayload = {
    ...cleanPayload,
    actionId: cleanPayload.actionId || createActionId(),
  }
  const sentAt = new Date().toISOString()

  if (import.meta.env?.DEV) {
    console.info('IGIX ACTION SEND', {
      actionId: actionPayload.actionId,
      action: actionPayload.action,
      formCode: actionPayload.formCode,
      funcId: actionPayload.funcId,
      status: meta.status || '',
      hasFields: Object.prototype.hasOwnProperty.call(actionPayload, 'fields'),
      sentAt,
      cleanPayload,
      payload: actionPayload,
    })
  }

  if (typeof structuredClone === 'function') {
    try {
      structuredClone(actionPayload)
    } catch (error) {
      console.error('[IGIX ACTION CLONE ERROR]', error, actionPayload)
      return false
    }
  }

  // TODO: 生产环境应限制父页面 origin，避免向非 iGIX 页面发送业务动作。
  window.parent.postMessage(
    {
      type: 'IGIX_AI_ACTION',
      payload: actionPayload,
    },
    '*',
  )

  return actionPayload
}
