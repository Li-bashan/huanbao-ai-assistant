// 试点阶段的智能问数白名单。
// 这里的内容会进入前端构建产物，只能作为页面展示限制，不能当作正式权限认证。
export const DATA_QUERY_ALLOWED_USER_NAMES = [
  // 在这里继续添加门户 userInfo.name，例如：'张三',
  '刘昊澎',
  '姚国旗',
  '仇亿伦',
  '李金钟',
  '王邯宝',
  '系统管理员',
  '迟全虎'
]

export function isDataQueryUserAllowed(userName) {
  const normalizedUserName = String(userName || '').trim()
  if (!normalizedUserName) return false

  return DATA_QUERY_ALLOWED_USER_NAMES.some(
    (allowedUserName) => String(allowedUserName || '').trim() === normalizedUserName,
  )
}
