const USER_FIELD_KEYS = [
  'userId',
  'code',
  'name',
  'mobilePhone',
  'orgId',
  'orgName',
  'unitId',
  'unitName',
  'tenantName',
]

function normalizeText(value) {
  const text = String(value || '').trim()
  return text || ''
}

export function normalizeIgixUser(rawUser) {
  if (!rawUser || typeof rawUser !== 'object') return null

  const normalizedUser = {
    userId: normalizeText(rawUser.userId || rawUser.id),
    code: normalizeText(rawUser.code),
    name: normalizeText(rawUser.name),
    mobilePhone: normalizeText(rawUser.mobilePhone),
    orgId: normalizeText(rawUser.orgId),
    orgName: normalizeText(rawUser.orgName),
    unitId: normalizeText(rawUser.unitId),
    unitName: normalizeText(rawUser.unitName),
    tenantName: normalizeText(rawUser.tenantName),
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

export async function getIgixCurrentUser() {
  try {
    const userService = findUserInfosService()

    if (!userService) {
      if (import.meta.env?.DEV) {
        console.info('IGIX CURRENT USER SERVICE NOT FOUND')
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
