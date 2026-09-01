const USER_FIELD_KEYS = [
  'userId',
  'code',
  'name',
  'mobilePhone',
  'orgId',
  'orgCode',
  'orgName',
  'unitId',
  'unitCode',
  'unitName',
  'tenantId',
  'tenantName',
]

const USER_REQUEST_MESSAGE_TYPE = 'IGIX_AI_USER_REQUEST'
const USER_RESPONSE_MESSAGE_TYPE = 'IGIX_AI_USER_RESPONSE'
const USER_REQUEST_TIMEOUT_MS = 1800

function normalizeText(value) {
  const text = String(value || '').trim()
  return text || ''
}

function resolveParentOrigin() {
  const configuredOrigin = normalizeText(import.meta.env?.VITE_IGIX_PORTAL_ORIGIN)

  if (configuredOrigin) return configuredOrigin.replace(/\/$/, '')

  if (document.referrer) {
    try {
      return new URL(document.referrer).origin
    } catch {
      // Fall through to the known iGIX platform origin.
    }
  }

  return 'http://172.17.3.34:5300'
}

function createRequestId() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID()
  return `igix-user-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

export function normalizeIgixUser(rawUser) {
  if (!rawUser || typeof rawUser !== 'object') return null

  const normalizedUser = {
    userId: normalizeText(rawUser.userId || rawUser.id),
    code: normalizeText(rawUser.code),
    name: normalizeText(rawUser.name),
    mobilePhone: normalizeText(rawUser.mobilePhone),
    orgId: normalizeText(rawUser.orgId),
    orgCode: normalizeText(rawUser.orgCode || rawUser.org?.code || rawUser.organizationCode),
    orgName: normalizeText(rawUser.orgName),
    unitId: normalizeText(rawUser.unitId),
    unitCode: normalizeText(rawUser.unitCode || rawUser.unit?.code),
    unitName: normalizeText(rawUser.unitName),
    tenantId: normalizeText(rawUser.tenantId || rawUser.tenantCode),
    tenantName: normalizeText(rawUser.tenantName),
    portalIdentity: normalizeText(rawUser.portalIdentity || rawUser.signedIdentity || rawUser.identityToken),
    portalIdentityTimestamp: normalizeText(rawUser.portalIdentityTimestamp || rawUser.identityTimestamp),
    portalIdentitySignature: normalizeText(rawUser.portalIdentitySignature || rawUser.identitySignature),
  }

  const hasValue = USER_FIELD_KEYS.some((key) => Boolean(normalizedUser[key]))
  return hasValue ? normalizedUser : null
}

function getWindowCandidates() {
  const candidates = []

  try {
    candidates.push(['top', window.top])
  } catch {
    // Ignore inaccessible window references.
  }

  try {
    candidates.push(['parent', window.parent])
  } catch {
    // Ignore inaccessible window references.
  }

  candidates.push(['self', window])

  return candidates.filter(([, candidate], index, list) =>
    candidate && list.findIndex(([, item]) => item === candidate) === index,
  )
}

function findUserInfosService() {
  for (const [scope, candidateWindow] of getWindowCandidates()) {
    try {
      const userInfos = candidateWindow?.gspframeworkService?.common?.userInfos

      if (typeof userInfos?.get === 'function') {
        return { scope, userInfos }
      }
    } catch (error) {
      if (import.meta.env?.DEV) {
        console.warn(`IGIX USER SERVICE INACCESSIBLE: ${scope}`, error)
      }
    }
  }

  return null
}

export function getPortalIdentityHeaders(user = null) {
  const identity = normalizeText(user?.portalIdentity || user?.signedIdentity)
  const timestamp = normalizeText(user?.portalIdentityTimestamp || user?.identityTimestamp)
  const signature = normalizeText(user?.portalIdentitySignature || user?.identitySignature)
  if (!identity || !timestamp || !signature) return {}
  return {
    'X-Portal-Identity': identity,
    'X-Portal-Identity-Timestamp': timestamp,
    'X-Portal-Identity-Signature': signature,
  }
}

function requestUserFromParent() {
  let parentWindow = null

  try {
    if (window.parent && window.parent !== window) {
      parentWindow = window.parent
    }
  } catch {
    return Promise.resolve(null)
  }

  if (!parentWindow) return Promise.resolve(null)

  const parentOrigin = resolveParentOrigin()
  const requestId = createRequestId()

  return new Promise((resolve) => {
    let settled = false

    const finish = (userInfo) => {
      if (settled) return
      settled = true
      window.clearTimeout(timeoutId)
      window.removeEventListener('message', handleMessage)
      resolve(userInfo)
    }

    const handleMessage = (event) => {
      if (event.source !== parentWindow || event.origin !== parentOrigin) return

      const data = event.data
      if (
        !data ||
        data.type !== USER_RESPONSE_MESSAGE_TYPE ||
        data.requestId !== requestId
      ) {
        return
      }

      finish(normalizeIgixUser(data.userInfo))
    }

    const timeoutId = window.setTimeout(() => finish(null), USER_REQUEST_TIMEOUT_MS)
    window.addEventListener('message', handleMessage)

    try {
      parentWindow.postMessage(
        {
          type: USER_REQUEST_MESSAGE_TYPE,
          requestId,
        },
        parentOrigin,
      )
    } catch (error) {
      if (import.meta.env?.DEV) {
        console.warn('IGIX CURRENT USER BRIDGE UNAVAILABLE', error)
      }
      finish(null)
    }
  })
}

async function getIgixCurrentUserDirect() {
  try {
    const userService = findUserInfosService()

    if (!userService) {
      if (import.meta.env?.DEV) {
        console.info('IGIX CURRENT USER SERVICE NOT FOUND; trying parent postMessage bridge')
      }

      return null
    }

    const rawUser = await Promise.resolve(userService.userInfos.get())
    const userInfo = normalizeIgixUser(rawUser)

    if (import.meta.env?.DEV) {
      console.info('IGIX CURRENT USER', {
        scope: userService.scope,
        userInfo,
      })
    }

    return userInfo
  } catch (error) {
    if (import.meta.env?.DEV) {
      console.warn('IGIX CURRENT USER UNAVAILABLE', error)
    }

    return null
  }
}

export async function getIgixCurrentUser() {
  const directUser = await getIgixCurrentUserDirect()
  if (directUser) return directUser

  const bridgedUser = await requestUserFromParent()

  if (bridgedUser) {
    if (import.meta.env?.DEV) {
      console.info('IGIX CURRENT USER', {
        scope: 'parent-postMessage',
        userInfo: bridgedUser,
      })
    }

    return bridgedUser
  }

  if (import.meta.env?.DEV) {
    console.info('IGIX CURRENT USER UNAVAILABLE: direct service and parent bridge both failed')
  }

  return null
}
