import { getPortalIdentityHeaders } from '../utils/igixUser.js'

const ADMIN_TOKEN_STORAGE_KEY = 'huanbao_data_query_admin_token'

export class DataQueryApiError extends Error {
  constructor(message, code = '') {
    super(message)
    this.name = 'DataQueryApiError'
    this.code = code
  }
}

function getGatewayBaseUrl() {
  return String(import.meta.env.VITE_AI_GATEWAY_BASE_URL || '').replace(/\/$/, '')
}

function buildGatewayUrl(path, query = '') {
  return `${getGatewayBaseUrl()}${path}${query}`
}

async function parseResponse(response, fallbackMessage) {
  let body = null
  try {
    body = await response.json()
  } catch {
    // Keep the status-based error below when the server does not return JSON.
  }

  if (!response.ok || body?.success === false) {
    const code = body?.code || 'DATA_QUERY_API_ERROR'
    const message = ['CURRENT_USER_MISSING', 'UNAUTHENTICATED'].includes(code)
      ? '暂未获取到当前登录信息，请在门户环境中重试。'
      : code === 'DATA_QUERY_NOT_COVERED'
        ? '您所在部门暂不支持生产指标智能问数，如有业务需要，请联系管理员申请。'
          : code === 'IDENTITY_UNTRUSTED'
            ? '当前登录身份仅处于试点声明模式，无法完成服务端校验。'
            : code === 'UNAUTHORIZED'
          ? '管理员令牌无效，请重新输入。'
          : fallbackMessage
    throw new DataQueryApiError(message, code)
  }

  return body?.data
}

export async function checkDataQueryAccess(userName) {
  const user = userName && typeof userName === 'object' ? userName : { name: userName }
  const normalizedUserId = String(user.userId || '').trim()
  if (!normalizedUserId) {
    throw new DataQueryApiError(
      '暂未获取到当前登录信息，请在门户环境中重试。',
      'CURRENT_USER_MISSING',
    )
  }

  const baseUrl = getGatewayBaseUrl()
  if (!baseUrl) {
    throw new DataQueryApiError('智能问数网关未配置，请联系管理员。', 'GATEWAY_CONFIG_MISSING')
  }

  let response
  try {
    response = await fetch(buildGatewayUrl('/api/ai/data-query/access'), {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        ...getPortalIdentityHeaders(user),
      },
      body: JSON.stringify({
        untrustedClientContext: {
          userId: normalizedUserId,
          userCode: String(user.code || user.userCode || '').trim(),
          userName: String(user.name || user.userName || '').trim(),
          orgCode: String(user.orgCode || user.orgId || '').trim(),
          orgName: String(user.orgName || user.unitName || '').trim(),
          tenantId: String(user.tenantId || user.tenantName || '').trim(),
        },
        clientContext: { assistantMode: 'data-query' },
      }),
    })
  } catch {
    throw new DataQueryApiError('智能问数开放范围服务暂时不可用，请稍后重试。', 'NETWORK_ERROR')
  }

  return parseResponse(response, '智能问数开放范围服务暂时不可用，请稍后重试。')
}

function getAdminToken() {
  try {
    return sessionStorage.getItem(ADMIN_TOKEN_STORAGE_KEY) || ''
  } catch {
    return ''
  }
}

export function readDataQueryAdminToken() {
  return getAdminToken()
}

export function saveDataQueryAdminToken(token) {
  const normalizedToken = String(token || '').trim()
  if (!normalizedToken) return false
  try {
    sessionStorage.setItem(ADMIN_TOKEN_STORAGE_KEY, normalizedToken)
    return true
  } catch {
    return false
  }
}

export function clearDataQueryAdminToken() {
  try {
    sessionStorage.removeItem(ADMIN_TOKEN_STORAGE_KEY)
  } catch {
    // Ignore storage failures; the page can still be used for the current request.
  }
}

async function adminRequest(path, options = {}) {
  const token = getAdminToken()
  const headers = {
    Accept: 'application/json',
    'X-Admin-Token': token,
    ...(options.body ? { 'Content-Type': 'application/json' } : {}),
    ...(options.headers || {}),
  }

  let response
  try {
    response = await fetch(buildGatewayUrl(`/api/ai/data-query/users${path}`), {
      ...options,
      headers,
    })
  } catch {
    throw new DataQueryApiError('人员配置服务暂时不可用，请稍后重试。', 'NETWORK_ERROR')
  }

  return parseResponse(response, '人员配置服务暂时不可用，请稍后重试。')
}

export function listDataQueryUsers({ keyword = '', enabled = '' } = {}) {
  const params = new URLSearchParams()
  if (keyword.trim()) params.set('keyword', keyword.trim())
  if (enabled !== '') params.set('enabled', enabled)
  const query = params.toString() ? `?${params.toString()}` : ''
  return adminRequest(query)
}

export function createDataQueryUser(payload) {
  return adminRequest('', { method: 'POST', body: JSON.stringify(payload) })
}

export function updateDataQueryUser(id, payload) {
  return adminRequest(`/${encodeURIComponent(id)}`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  })
}

export function deleteDataQueryUser(id) {
  return adminRequest(`/${encodeURIComponent(id)}`, { method: 'DELETE' })
}
