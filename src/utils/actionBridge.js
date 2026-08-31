import { isProxy, isRef, toRaw } from 'vue'

const DEFAULT_PORTAL_ORIGIN = 'http://172.17.3.34:5300'

function normalizeOrigin(value) {
  if (!value || typeof value !== 'string') return ''

  try {
    const origin = new URL(value).origin
    return origin === 'null' ? '' : origin
  } catch {
    return ''
  }
}

export function resolvePortalOrigin(explicitOrigin = '') {
  const configuredOrigin = normalizeOrigin(
    explicitOrigin || import.meta.env?.VITE_IGIX_PORTAL_ORIGIN || '',
  )
  if (configuredOrigin) return configuredOrigin

  const referrerOrigin = normalizeOrigin(
    typeof document !== 'undefined' ? document.referrer : '',
  )
  return referrerOrigin || DEFAULT_PORTAL_ORIGIN
}

function createFallbackUuid() {
  const bytes = new Uint8Array(16)

  if (globalThis.crypto?.getRandomValues) {
    globalThis.crypto.getRandomValues(bytes)
  } else {
    for (let index = 0; index < bytes.length; index += 1) {
      bytes[index] = Math.floor(Math.random() * 256)
    }
  }

  bytes[6] = (bytes[6] & 0x0f) | 0x40
  bytes[8] = (bytes[8] & 0x3f) | 0x80

  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

export function createActionId() {
  return globalThis.crypto?.randomUUID?.() || createFallbackUuid()
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
  const { actionId: _ignoredActionId, ...payloadWithoutActionId } = cleanPayload
  const actionPayload = {
    ...payloadWithoutActionId,
    actionId: createActionId(),
  }
  const sentAt = new Date().toISOString()
  const targetOrigin = resolvePortalOrigin()

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

  window.parent.postMessage(
    {
      type: 'IGIX_AI_ACTION',
      payload: actionPayload,
    },
    targetOrigin,
  )

  return actionPayload
}
