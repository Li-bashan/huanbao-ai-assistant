<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import {
  clearDataQueryAdminToken,
  createDataQueryUser,
  deleteDataQueryUser,
  listDataQueryUsers,
  readDataQueryAdminToken,
  saveDataQueryAdminToken,
  updateDataQueryUser,
} from '../services/dataQueryAccessApi'

const tokenInput = ref('')
const adminToken = ref(readDataQueryAdminToken())
const users = ref([])
const keyword = ref('')
const enabledFilter = ref('')
const loading = ref(false)
const saving = ref(false)
const authenticated = computed(() => Boolean(adminToken.value))
const showEditor = ref(false)
const errorMessage = ref('')
const notice = ref('')
const editor = reactive({
  id: null,
  userId: '',
  userCode: '',
  userName: '',
  tenantId: 'default',
  tenantName: '',
  orgId: '',
  orgCode: '',
  orgName: '',
  enabled: true,
  remark: '',
  scopeType: 'COMPANY',
  allowedOrgCodes: '',
  allowedIndicatorCodes: '',
  allowGroupRanking: false,
  allowAllOrganizations: false,
})

const clearFeedback = () => {
  errorMessage.value = ''
  notice.value = ''
}

const handleUnauthorized = (error) => {
  if (error?.code !== 'UNAUTHORIZED') return false
  clearDataQueryAdminToken()
  adminToken.value = ''
  users.value = []
  errorMessage.value = '管理员令牌无效，请重新输入。'
  return true
}

const loadUsers = async () => {
  if (!authenticated.value) return
  loading.value = true
  clearFeedback()
  try {
    users.value = (await listDataQueryUsers({ keyword: keyword.value, enabled: enabledFilter.value })) || []
  } catch (error) {
    if (!handleUnauthorized(error)) errorMessage.value = error?.message || '人员配置服务暂时不可用，请稍后重试。'
  } finally {
    loading.value = false
  }
}

const unlock = async () => {
  const value = tokenInput.value.trim()
  if (!value) {
    errorMessage.value = '请输入管理员令牌。'
    return
  }
  if (!saveDataQueryAdminToken(value)) {
    errorMessage.value = '当前浏览器无法保存会话令牌，请检查浏览器设置。'
    return
  }
  adminToken.value = value
  tokenInput.value = ''
  await loadUsers()
}

const signOut = () => {
  clearDataQueryAdminToken()
  adminToken.value = ''
  users.value = []
  clearFeedback()
}

const resetEditor = () => {
  editor.id = null
  editor.userId = ''
  editor.userCode = ''
  editor.userName = ''
  editor.tenantId = 'default'
  editor.tenantName = ''
  editor.orgId = ''
  editor.orgCode = ''
  editor.orgName = ''
  editor.enabled = true
  editor.remark = ''
  editor.scopeType = 'COMPANY'
  editor.allowedOrgCodes = ''
  editor.allowedIndicatorCodes = ''
  editor.allowGroupRanking = false
  editor.allowAllOrganizations = false
}

const openCreate = () => {
  clearFeedback()
  resetEditor()
  showEditor.value = true
}

const openEdit = (user) => {
  clearFeedback()
  editor.id = user.id
  editor.userId = user.userId || ''
  editor.userCode = user.userCode || ''
  editor.userName = user.userName
  editor.tenantId = user.tenantId || 'default'
  editor.tenantName = user.tenantName || ''
  editor.orgId = user.orgId || ''
  editor.orgCode = user.orgCode || ''
  editor.orgName = user.orgName || ''
  editor.enabled = user.enabled
  editor.remark = user.remark || ''
  editor.scopeType = user.scopeType || 'COMPANY'
  editor.allowedOrgCodes = (user.allowedOrgCodes || []).join(', ')
  editor.allowedIndicatorCodes = (user.allowedIndicatorCodes || []).join(', ')
  editor.allowGroupRanking = Boolean(user.allowGroupRanking)
  editor.allowAllOrganizations = Boolean(user.allowAllOrganizations)
  showEditor.value = true
}

const closeEditor = () => {
  if (saving.value) return
  showEditor.value = false
}

const saveUser = async () => {
  const userId = editor.userId.trim()
  const userName = editor.userName.trim()
  const tenantId = editor.tenantId.trim()
  if (!userId) {
    errorMessage.value = '用户 ID 不能为空，必须与门户可信身份一致。'
    return
  }
  if (!userName) {
    errorMessage.value = '姓名不能为空。'
    return
  }
  if (userName.length > 80) {
    errorMessage.value = '姓名不能超过 80 个字符。'
    return
  }
  if (editor.remark.length > 255) {
    errorMessage.value = '备注不能超过 255 个字符。'
    return
  }
  if (!tenantId) {
    errorMessage.value = '租户 ID 不能为空。'
    return
  }

  saving.value = true
  clearFeedback()
  try {
    const toList = (value) => value.split(/[,，\s]+/).map((item) => item.trim()).filter(Boolean)
    const payload = {
      userId,
      userCode: editor.userCode.trim(),
      userName,
      tenantId,
      tenantName: editor.tenantName.trim(),
      orgId: editor.orgId.trim(),
      orgCode: editor.orgCode.trim(),
      orgName: editor.orgName.trim(),
      enabled: editor.enabled,
      remark: editor.remark.trim(),
      scopeType: editor.scopeType,
      allowedOrgCodes: toList(editor.allowedOrgCodes),
      allowedIndicatorCodes: toList(editor.allowedIndicatorCodes),
      allowGroupRanking: editor.allowGroupRanking,
      allowAllOrganizations: editor.allowAllOrganizations,
    }
    if (editor.id) {
      await updateDataQueryUser(editor.id, payload)
      notice.value = '人员信息已更新。'
    } else {
      await createDataQueryUser(payload)
      notice.value = '人员已加入开放名单。'
    }
    showEditor.value = false
    await loadUsers()
  } catch (error) {
    if (!handleUnauthorized(error)) errorMessage.value = error?.message || '保存失败，请稍后重试。'
  } finally {
    saving.value = false
  }
}

const toggleUser = async (user) => {
  clearFeedback()
  try {
    if (!user.userId || !user.tenantId) {
      errorMessage.value = '该旧名单还没有完成可信身份映射，请编辑后补齐用户 ID 和租户 ID。'
      return
    }
    await updateDataQueryUser(user.id, { ...user, enabled: !user.enabled })
    notice.value = user.enabled ? '人员已停用。' : '人员已启用。'
    await loadUsers()
  } catch (error) {
    if (!handleUnauthorized(error)) errorMessage.value = error?.message || '状态更新失败，请稍后重试。'
  }
}

const removeUser = async (user) => {
  if (!window.confirm(`确定删除 ${user.userName} 吗？`)) return
  clearFeedback()
  try {
    await deleteDataQueryUser(user.id)
    notice.value = '人员已删除。'
    await loadUsers()
  } catch (error) {
    if (!handleUnauthorized(error)) errorMessage.value = error?.message || '删除失败，请稍后重试。'
  }
}

const formatTime = (value) => {
  if (!value) return '-'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false })
}

onMounted(() => {
  if (authenticated.value) loadUsers()
})
</script>

<template>
  <main class="data-query-admin-page">
    <section class="data-query-admin-card">
      <header class="data-query-admin-header">
        <div>
          <span class="data-query-admin-kicker">环宝 AI · 试点配置</span>
          <h1>智能问数人员配置</h1>
          <p>动态维护生产指标智能问数开放名单，配置修改后立即生效。</p>
        </div>
        <a class="data-query-admin-back" href="/">返回智能助手</a>
      </header>

      <section v-if="!authenticated" class="data-query-admin-login">
        <h2>管理员验证</h2>
        <p>请输入后端环境变量 DATA_QUERY_ADMIN_TOKEN 对应的管理员令牌。令牌仅保存在当前会话。</p>
        <form @submit.prevent="unlock">
          <input v-model="tokenInput" type="password" autocomplete="off" placeholder="输入管理员令牌" />
          <button type="submit">进入配置</button>
        </form>
      </section>

      <template v-else>
        <div class="data-query-admin-toolbar">
          <div class="data-query-admin-filters">
            <input v-model="keyword" type="search" placeholder="搜索姓名" @keyup.enter="loadUsers" />
            <select v-model="enabledFilter" aria-label="筛选状态" @change="loadUsers">
              <option value="">全部状态</option>
              <option value="true">已启用</option>
              <option value="false">已停用</option>
            </select>
            <button type="button" class="secondary" @click="loadUsers">查询</button>
          </div>
          <div class="data-query-admin-actions">
            <button type="button" @click="openCreate">新增人员</button>
            <button type="button" class="secondary" @click="signOut">退出</button>
          </div>
        </div>

        <div v-if="loading" class="data-query-admin-empty">正在加载人员名单...</div>
        <div v-else-if="!users.length" class="data-query-admin-empty">暂无匹配人员。</div>
        <div v-else class="data-query-admin-table-wrap">
          <table class="data-query-admin-table">
            <thead><tr><th>用户 ID</th><th>姓名 / 组织</th><th>范围</th><th>状态</th><th>更新时间</th><th>操作</th></tr></thead>
            <tbody>
              <tr v-for="user in users" :key="user.id">
                <td>{{ user.userId || '待迁移' }}</td>
                <td>{{ user.userName }}<small v-if="user.orgName"> · {{ user.orgName }}</small></td>
                <td>{{ user.scopeType || 'NONE' }}</td>
                <td><span class="data-query-admin-status" :class="user.enabled ? 'enabled' : 'disabled'">{{ user.enabled ? '已启用' : '已停用' }}</span></td>
                <td>{{ formatTime(user.updatedAt) }}</td>
                <td class="data-query-admin-row-actions">
                  <button type="button" class="link" @click="openEdit(user)">编辑</button>
                  <button type="button" class="link" @click="toggleUser(user)">{{ user.enabled ? '停用' : '启用' }}</button>
                  <button type="button" class="link danger" @click="removeUser(user)">删除</button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>

      <p v-if="notice" class="data-query-admin-notice">{{ notice }}</p>
      <p v-if="errorMessage" class="data-query-admin-error">{{ errorMessage }}</p>
    </section>

    <div v-if="showEditor" class="data-query-admin-modal-backdrop" @click.self="closeEditor">
      <form class="data-query-admin-modal" @submit.prevent="saveUser">
        <h2>{{ editor.id ? '编辑人员' : '新增人员' }}</h2>
        <label>用户 ID *<input v-model="editor.userId" type="text" maxlength="120" autofocus /></label>
        <label>用户编码<input v-model="editor.userCode" type="text" maxlength="120" /></label>
        <label>姓名 *<input v-model="editor.userName" type="text" maxlength="80" /></label>
        <label>租户 ID *<input v-model="editor.tenantId" type="text" maxlength="120" /></label>
        <label>租户名称<input v-model="editor.tenantName" type="text" maxlength="160" /></label>
        <label>组织编码<input v-model="editor.orgCode" type="text" maxlength="120" /></label>
        <label>组织名称<input v-model="editor.orgName" type="text" maxlength="160" /></label>
        <label>组织范围
          <select v-model="editor.scopeType"><option value="NONE">无</option><option value="ORG">指定组织</option><option value="COMPANY">公司范围</option><option value="GROUP">集团范围</option></select>
        </label>
        <label>允许组织编码<input v-model="editor.allowedOrgCodes" type="text" placeholder="多个编码用逗号分隔" /></label>
        <label>允许指标编码<input v-model="editor.allowedIndicatorCodes" type="text" placeholder="多个编码用逗号分隔，留空表示不限制" /></label>
        <label class="data-query-admin-checkbox"><input v-model="editor.allowGroupRanking" type="checkbox" />允许集团排名</label>
        <label class="data-query-admin-checkbox"><input v-model="editor.allowAllOrganizations" type="checkbox" />允许全部组织</label>
        <label>备注<input v-model="editor.remark" type="text" maxlength="255" /></label>
        <label class="data-query-admin-checkbox"><input v-model="editor.enabled" type="checkbox" />启用</label>
        <div class="data-query-admin-modal-actions">
          <button type="button" class="secondary" @click="closeEditor">取消</button>
          <button type="submit" :disabled="saving">{{ saving ? '保存中...' : '保存' }}</button>
        </div>
      </form>
    </div>
  </main>
</template>
