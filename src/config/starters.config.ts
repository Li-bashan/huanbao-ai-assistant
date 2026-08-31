export type PromptStarterCategory = '制度问答' | '智能问数' | '流程发起' | '协同任务'
export type PromptStarterTheme = 'blue' | 'purple' | 'orange' | 'emerald'
export type PromptStarterMode = 'all' | 'policy' | 'data-query' | 'workflow' | 'office'

export interface PromptStarter {
  id: string
  category: PromptStarterCategory
  prompt: string
  themeColor: PromptStarterTheme
  supportedModes: PromptStarterMode[]
  targetOrgs: string[]
  isCompound: boolean
}

export const promptStarters: PromptStarter[] = [
  {
    id: 'policy-travel-reimbursement',
    category: '制度问答',
    prompt: '差旅费包括哪些费用？出差住宿费和伙食补助标准是多少？',
    themeColor: 'blue',
    supportedModes: ['policy'],
    targetOrgs: ['all'],
    isCompound: false,
  },
  {
    id: 'policy-official-vehicle',
    category: '制度问答',
    prompt: '公务用车如何申请，需要哪些审批条件？',
    themeColor: 'blue',
    supportedModes: ['policy'],
    targetOrgs: ['all'],
    isCompound: false,
  },
  {
    id: 'policy-environment-compliance',
    category: '制度问答',
    prompt: '帮我查一下环保合规检查中关于危险废物管理的制度依据。',
    themeColor: 'blue',
    supportedModes: ['policy'],
    targetOrgs: ['安环', '安全环保', '生产', '电厂'],
    isCompound: false,
  },
  {
    id: 'policy-attendance-approval',
    category: '制度问答',
    prompt: '加班和调休分别需要谁审批，制度依据是什么？',
    themeColor: 'blue',
    supportedModes: ['policy'],
    targetOrgs: ['综合', '人资', '人力', 'all'],
    isCompound: false,
  },
  {
    id: 'data-waste-intake',
    category: '智能问数',
    prompt: '查询本月生活垃圾入厂量完成情况，并按项目公司对比。',
    themeColor: 'purple',
    supportedModes: ['data-query'],
    targetOrgs: ['生产', '电厂', '运维', 'all'],
    isCompound: false,
  },
  {
    id: 'data-power-generation',
    category: '智能问数',
    prompt: '查询近三个月发电量变化趋势，标出变化最大的月份。',
    themeColor: 'purple',
    supportedModes: ['data-query'],
    targetOrgs: ['生产', '电厂', '运维'],
    isCompound: false,
  },
  {
    id: 'data-company-ranking',
    category: '智能问数',
    prompt: '查询今年各项目公司垃圾处理量排名，展示前十名。',
    themeColor: 'purple',
    supportedModes: ['data-query'],
    targetOrgs: ['生产', '经营', '电厂', 'all'],
    isCompound: false,
  },
  {
    id: 'data-dashboard',
    category: '智能问数',
    prompt: '查询本月生产指标看板，包含入厂量、发电量和上网电量。',
    themeColor: 'purple',
    supportedModes: ['data-query'],
    targetOrgs: ['生产', '运维', '电厂', 'all'],
    isCompound: false,
  },
  {
    id: 'workflow-purchase-request',
    category: '流程发起',
    prompt: '打开采购请示单',
    themeColor: 'orange',
    supportedModes: ['workflow'],
    targetOrgs: ['采购', '综合', 'all'],
    isCompound: false,
  },
  {
    id: 'workflow-contract-review',
    category: '流程发起',
    prompt: '发起合同评审流程，需要准备哪些材料？',
    themeColor: 'orange',
    supportedModes: ['workflow'],
    targetOrgs: ['采购', '法务', '综合', 'all'],
    isCompound: false,
  },
  {
    id: 'workflow-todo',
    category: '流程发起',
    prompt: '查看我的待办任务',
    themeColor: 'orange',
    supportedModes: ['workflow'],
    targetOrgs: ['all'],
    isCompound: false,
  },
  {
    id: 'office-meeting-minutes',
    category: '协同任务',
    prompt: '帮我把下面的会议内容整理成正式会议纪要，补齐议题、结论和责任人。',
    themeColor: 'emerald',
    supportedModes: ['office'],
    targetOrgs: ['all'],
    isCompound: false,
  },
  {
    id: 'office-maintenance-notice',
    category: '协同任务',
    prompt: '帮我写一份关于智慧办公系统临时维护的通知。',
    themeColor: 'emerald',
    supportedModes: ['office'],
    targetOrgs: ['运维', '信息', '综合', 'all'],
    isCompound: false,
  },
  {
    id: 'office-weekly-summary',
    category: '协同任务',
    prompt: '根据本周工作事项整理一份简洁、适合领导查看的工作总结。',
    themeColor: 'emerald',
    supportedModes: ['office'],
    targetOrgs: ['all'],
    isCompound: false,
  },
  {
    id: 'office-polish-approval',
    category: '协同任务',
    prompt: '帮我润色一段事项审批说明，语气正式、逻辑清楚、控制在三百字以内。',
    themeColor: 'emerald',
    supportedModes: ['office'],
    targetOrgs: ['综合', '财务', '采购', 'all'],
    isCompound: false,
  },
  {
    id: 'compound-data-to-report',
    category: '协同任务',
    prompt: '查询本月生活垃圾入厂量和发电量，并整理成一份周报要点。',
    themeColor: 'emerald',
    supportedModes: ['data-query', 'office'],
    targetOrgs: ['生产', '电厂', '运维'],
    isCompound: true,
  },
]

const normalizeMode = (mode) => {
  const modeKey = typeof mode === 'object' ? mode?.key : mode
  if (modeKey === 'adaptive' || modeKey === 'all') return 'all'
  if (modeKey === 'office-ai' || modeKey === 'general' || modeKey === 'office') return 'office'
  return modeKey || 'policy'
}

const shuffle = (items) => [...items].sort(() => Math.random() - 0.5)

const supportsMode = (starter, mode) =>
  starter.supportedModes.includes('all') || starter.supportedModes.includes(mode)

const matchesOrg = (starter, orgName) => {
  if (starter.targetOrgs.includes('all')) return true
  const normalizedOrgName = String(orgName || '').trim().toLowerCase()
  if (!normalizedOrgName) return false

  return starter.targetOrgs.some((org) => {
    const normalizedOrg = org.toLowerCase()
    return normalizedOrgName.includes(normalizedOrg) || normalizedOrg.includes(normalizedOrgName)
  })
}

export function getRecommendedStarters(currentMode, userOrgName = '') {
  const mode = normalizeMode(currentMode)
  const modeStarters = promptStarters.filter((starter) => supportsMode(starter, mode))
  const modePool = modeStarters.length ? modeStarters : promptStarters
  const orgSpecificPool = modePool.filter(
    (starter) => !starter.targetOrgs.includes('all') && matchesOrg(starter, userOrgName),
  )
  const orgGeneralPool = modePool.filter(
    (starter) => starter.targetOrgs.includes('all') && matchesOrg(starter, userOrgName),
  )
  const selected = []
  const selectedIds = new Set()

  const addFrom = (pool) => {
    shuffle(pool).forEach((starter) => {
      if (selected.length >= 4 || selectedIds.has(starter.id)) return
      selected.push(starter)
      selectedIds.add(starter.id)
    })
  }

  addFrom(orgSpecificPool)
  addFrom(orgGeneralPool)
  addFrom(modePool)

  // 当前模式题库不足 4 条时，用通用 all 题库补齐，保持推荐区始终有内容。
  if (selected.length < 4) {
    addFrom(promptStarters.filter((starter) => starter.targetOrgs.includes('all')))
  }

  return selected.slice(0, 4)
}
