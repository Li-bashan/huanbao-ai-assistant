import { isProxy, isRef, toRaw } from 'vue'

const DEFAULT_PORTAL_ORIGIN = 'http://172.17.3.34:5300'
const ACTION_AUDIT_PATH = '/api/ai/action-audits'

export const ACTION_ACK_TIMEOUT_MS = 5000
export const ACTION_MESSAGE_TYPE = 'IGIX_AI_ACTION'
export const ACTION_ACK_MESSAGE_TYPE = 'IGIX_AI_ACTION_ACK'
export const ACTION_ACK_STATUSES = Object.freeze(['SUCCESS', 'FAILED', 'TIMEOUT'])

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

function truncateText(value, maxLength) {
  const text = String(value || '').trim()
  return text.length > maxLength ? text.slice(0, maxLength) : text
}

function getGatewayAuditUrl() {
  const baseUrl = String(import.meta.env?.VITE_AI_GATEWAY_BASE_URL || '').replace(/\/$/, '')
  return `${baseUrl}${ACTION_AUDIT_PATH}`
}

function getAuditUser(meta = {}) {
  const user = meta.currentUser || {}
  return {
    userId: truncateText(user.userId || user.id, 80),
    userName: truncateText(user.name || user.userName, 80),
  }
}

function buildAuditPayload(actionPayload, meta, result, errorMessage = '') {
  const user = getAuditUser(meta)

  return {
    actionId: actionPayload.actionId,
    userId: user.userId,
    userName: user.userName,
    query: truncateText(meta.query, 1000),
    action: truncateText(actionPayload.action, 40),
    formCode: truncateText(actionPayload.formCode, 100),
    funcId: truncateText(actionPayload.funcId, 80),
    status: truncateText(meta.status, 40),
    hasFields: Object.prototype.hasOwnProperty.call(actionPayload, 'fields'),
    sentAt: meta.sentAt,
    result,
    errorMessage: truncateText(errorMessage, 1000),
  }
}

function reportAuditFailure(phase, error) {
  console.warn(`[IGIX ACTION AUDIT ${phase} FAILED]`, error)
}

async function recordActionAudit(actionPayload, meta, result, errorMessage = '') {
  if (typeof fetch !== 'function') {
    reportAuditFailure(result, new Error('fetch unavailable'))
    return false
  }

  const requestBody = buildAuditPayload(actionPayload, meta, result, errorMessage)

  try {
    const response = await fetch(getGatewayAuditUrl(), {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(requestBody),
    })

    if (!response?.ok) {
      throw new Error(`HTTP ${response?.status || 'unknown'}`)
    }

    return true
  } catch (error) {
    reportAuditFailure(result, error)
    return false
  }
}

function notifySafely(callback, ...args) {
  if (typeof callback !== 'function') return

  try {
    callback(...args)
  } catch (error) {
    console.warn('[IGIX ACTION CALLBACK FAILED]', error)
  }
}

function normalizeAckTimestamp(value) {
  return typeof value === 'string' && value.trim() ? value : new Date().toISOString()
}

function createActionResult(actionPayload, portalOrigin, status, timestamp, errorMessage = '') {
  return {
    ...actionPayload,
    actionId: actionPayload.actionId,
    portalOrigin,
    status,
    timestamp: normalizeAckTimestamp(timestamp),
    errorMessage: truncateText(errorMessage, 1000),
  }
}

function finishWithoutListener(actionPayload, portalOrigin, meta, status, errorMessage) {
  const result = createActionResult(
    actionPayload,
    portalOrigin,
    status,
    new Date().toISOString(),
    errorMessage,
  )
  notifySafely(meta.onStateChange, status, result)
  notifySafely(meta.onComplete, result)
  void recordActionAudit(actionPayload, meta, status, result.errorMessage)
  return Promise.resolve(result)
}

export function sendIgixAction(payload = {}, meta = {}) {
  const cleanPayload = sanitizePayload(payload) || {}
  const { actionId: _ignoredActionId, ...payloadWithoutActionId } = cleanPayload
  const actionPayload = {
    ...payloadWithoutActionId,
    actionId: createActionId(),
  }
  const actionId = actionPayload.actionId
  const sentAt = new Date().toISOString()
  const portalOrigin = resolvePortalOrigin(meta.portalOrigin)
  const auditMeta = { ...meta, sentAt }

  notifySafely(meta.onStateChange, 'SENDING', {
    actionId: actionPayload.actionId,
    portalOrigin,
  })

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
      portalOrigin,
    })
  }

  void recordActionAudit(actionPayload, auditMeta, 'ATTEMPT')

  if (typeof structuredClone === 'function') {
    try {
      structuredClone(actionPayload)
    } catch (error) {
      console.warn('[IGIX ACTION CLONE ERROR]', error)
      return finishWithoutListener(
        actionPayload,
        portalOrigin,
        auditMeta,
        'FAILED',
        '动作参数无法安全发送。',
      )
    }
  }

  if (typeof window === 'undefined') {
    return finishWithoutListener(
      actionPayload,
      portalOrigin,
      auditMeta,
      'FAILED',
      '当前环境无法访问门户窗口。',
    )
  }

  try {
    if (!window.parent || typeof window.parent.postMessage !== 'function') {
      throw new Error('parent window unavailable')
    }
  } catch (error) {
    return finishWithoutListener(
      actionPayload,
      portalOrigin,
      auditMeta,
      'FAILED',
      '当前页面未连接到门户父窗口。',
    )
  }

  return new Promise((resolve) => {
    let settled = false
    let timeoutId = 0
    let waitingStateTimerId = 0

    const finish = (status, timestamp, errorMessage = '') => {
      if (settled) return
      settled = true
      window.clearTimeout(timeoutId)
      window.clearTimeout(waitingStateTimerId)
      window.removeEventListener('message', handleMessage)

      const result = createActionResult(
        actionPayload,
        portalOrigin,
        status,
        timestamp,
        errorMessage,
      )
      notifySafely(meta.onStateChange, status, result)
      notifySafely(meta.onComplete, result)
      void recordActionAudit(actionPayload, auditMeta, status, result.errorMessage)
      resolve(result)
    }

    const handleMessage = (event) => {
      if (
        event.origin !== portalOrigin ||
        event.source !== window.parent ||
        event.data?.type !== ACTION_ACK_MESSAGE_TYPE ||
        event.data?.actionId !== actionId
      ) {
        return
      }

      const status = event.data?.status
      if (!ACTION_ACK_STATUSES.includes(status)) return

      finish(status, event.data?.timestamp, event.data?.errorMessage)
    }

    window.addEventListener('message', handleMessage)
    timeoutId = window.setTimeout(() => {
      finish('TIMEOUT', new Date().toISOString(), '门户未在 5000ms 内返回 ACK。')
    }, ACTION_ACK_TIMEOUT_MS)

    try {
      window.parent.postMessage(
        {
          type: ACTION_MESSAGE_TYPE,
          actionId,
          payload: actionPayload,
        },
        portalOrigin,
      )
      notifySafely(meta.onStateChange, 'SENT', {
        actionId,
        portalOrigin,
      })
      waitingStateTimerId = window.setTimeout(() => {
        if (settled) return
        notifySafely(meta.onStateChange, 'WAITING_ACK', {
          actionId: actionPayload.actionId,
          portalOrigin,
        })
      }, 0)
    } catch (error) {
      finish('FAILED', new Date().toISOString(), '动作消息未能发送到门户。')
    }
  })
}
