function normalizeFieldValue(value = '') {
  return String(value || '')
    .replace(/^[：:，,\s]+/, '')
    .replace(/[。；;，,\s]+$/, '')
    .trim()
}

function pickMatch(text, patterns = []) {
  for (const pattern of patterns) {
    const matched = text.match(pattern)
    const value = normalizeFieldValue(matched?.[1])
    if (value) return value
  }

  return ''
}

function shouldApplyUserDefaults(text, fields) {
  const content = String(text || '')
  return (
    Object.keys(fields).length > 0 ||
    /预填|发起|创建|新建|帮我/.test(content)
  )
}

export const ENABLE_WORKFLOW_PREFILL = false

export function parsePurchaseRequestFields(text = '', options = {}) {
  const content = String(text || '').trim()
  const currentUser = options.currentUser || {}
  const fields = {}

  const projectName = pickMatch(content, [
    /项目名称(?:是|为|叫)?\s*([^，,。；;\n]+?)(?=，|,|。|；|;|\n|联系人|联系电话|电话|手机号|手机|预算|$)/,
    /项目叫\s*([^，,。；;\n]+?)(?=，|,|。|；|;|\n|联系人|联系电话|电话|手机号|手机|预算|$)/,
  ])

  const contact = pickMatch(content, [
    /联系人(?:是|为)?\s*([^，,。；;\n]+?)(?=，|,|。|；|;|\n|项目|联系电话|电话|手机号|手机|预算|$)/,
  ])

  const contactPhone = pickMatch(content, [
    /联系电话(?:是|为)?\s*([0-9+\-\s]{5,20})(?=，|,|。|；|;|\n|项目|联系人|预算|$)/,
    /电话(?:是|为)?\s*([0-9+\-\s]{5,20})(?=，|,|。|；|;|\n|项目|联系人|预算|$)/,
    /手机号(?:是|为)?\s*([0-9+\-\s]{5,20})(?=，|,|。|；|;|\n|项目|联系人|预算|$)/,
    /手机(?:是|为)?\s*([0-9+\-\s]{5,20})(?=，|,|。|；|;|\n|项目|联系人|预算|$)/,
  ])

  const budgetAmount = pickMatch(content, [
    /预算金额(?:是|为)?\s*([0-9]+(?:\.[0-9]+)?)(?:\s*元)?/,
    /预算(?:是|为)?\s*([0-9]+(?:\.[0-9]+)?)(?:\s*元)?/,
  ])

  if (projectName) fields['项目名称'] = projectName
  if (contact) fields['联系人'] = contact
  if (contactPhone) fields['联系电话'] = contactPhone.replace(/\s+/g, '')
  if (budgetAmount) fields['预算金额(元)'] = budgetAmount

  if (shouldApplyUserDefaults(content, fields)) {
    if (!fields['联系人'] && currentUser.name) {
      fields['联系人'] = currentUser.name
    }

    if (!fields['联系电话'] && currentUser.mobilePhone) {
      fields['联系电话'] = currentUser.mobilePhone
    }
  }

  if (import.meta.env?.DEV) {
    console.info('WORKFLOW PREFILL PARSED', fields)
  }

  return fields
}

const workflowMappings = [
  {
    businessModule: '采购模块',
    workflowName: '通用性备案表',
    id: 'purchase_general_record_card',
    formCode: 'tyxbabCard',
    funcId: '3d78001b-3077-4a30-92c6-3f7e1acfb0a1',
    actionType: 'open_form',
    status: 'pending',
    supportPrefill: true,
    description: '打开通用性备案表详情或新增表单。',
    keywords: ['通用性备案', '通用性备案表'],
  },
  {
    businessModule: '采购模块',
    workflowName: '通用性备案表台账',
    id: 'purchase_general_record_list',
    formCode: 'tyxbabList',
    funcId: '39fda9a8-a44d-4c5c-ba36-0d4a5d7d15b9',
    actionType: 'open_menu',
    status: 'pending',
    supportPrefill: false,
    description: '打开通用性备案表台账。',
    keywords: ['通用性备案台账', '通用性备案表台账'],
  },
  {
    businessModule: '采购模块',
    workflowName: '采购请示单台账',
    id: 'purchase_request_list',
    formCode: 'CgqsdBbList',
    funcId: 'e9eae7ff-792f-4413-adc0-4a888737b7b2',
    actionType: 'open_menu',
    status: 'pending',
    supportPrefill: false,
    description: '打开采购请示单台账。',
    keywords: ['采购请示单台账', '采购台账', '采购申请台账'],
  },
  {
    businessModule: '采购模块',
    workflowName: '采购请示单',
    id: 'purchase_request',
    formCode: 'CGQSD',
    funcId: '9744034a-7fcc-4510-97fa-f563aecd26e6',
    actionType: 'open_form_prefill',
    status: 'verified',
    supportPrefill: true,
    description: '打开采购请示单。',
    keywords: ['采购请示单', '采购申请', '发起采购', '打开采购'],
    prefillFields: ['项目名称', '联系人', '联系电话', '预算金额(元)'],
    parseFields: parsePurchaseRequestFields,
  },
  {
    businessModule: '采购模块',
    workflowName: '项目中标人确定报批表台账',
    id: 'winning_bidder_approval_list',
    formCode: 'XmzbrqdbpbList',
    funcId: '96292cf7-5a5e-4a3b-b255-2f199f81e159',
    actionType: 'open_menu',
    status: 'pending',
    supportPrefill: false,
    description: '打开项目中标人确定报批表台账。',
    keywords: ['中标人确定台账', '项目中标人确定报批表台账'],
  },
  {
    businessModule: '采购模块',
    workflowName: '项目中标人确定报批表',
    id: 'winning_bidder_approval',
    formCode: 'XmzbrqdbpbCard',
    funcId: '4d469a6b-993b-4c70-8879-5c9e2e6c70fe',
    actionType: 'open_form_prefill',
    status: 'pending',
    supportPrefill: true,
    description: '打开项目中标人确定报批表。',
    keywords: ['中标人确定', '项目中标人确定', '项目中标人确定报批'],
  },
  {
    businessModule: '运维模块',
    workflowName: '设备更新技改项目立项（备案）台账',
    id: 'tech_upgrade_project_record_list',
    formCode: 'jishugaizao',
    funcId: 'f1f86288-6812-459d-b9b5-6351e2f04150',
    actionType: 'open_menu',
    status: 'pending',
    supportPrefill: false,
    description: '打开设备更新技改项目立项备案台账。',
    keywords: ['技改项目立项台账', '设备更新技改台账', '设备更新技改项目立项备案台账'],
  },
  {
    businessModule: '运维模块',
    workflowName: '设备更新技改项目立项（备案）审批',
    id: 'tech_upgrade_project_approval',
    formCode: 'TechUpgradeProjectApproval',
    funcId: 'be5c44ce-8a34-4266-80d2-13cf9e086992',
    actionType: 'open_form_prefill',
    status: 'pending',
    supportPrefill: true,
    description: '打开设备更新技改项目立项备案审批表。',
    keywords: ['技改项目立项', '设备更新技改项目立项', '设备更新技改项目立项备案审批'],
  },
  {
    businessModule: '运维模块',
    workflowName: '检修项目立项（备案）台账',
    id: 'maintenance_project_record_list',
    formCode: 'jxxmlxbaspbList',
    funcId: '1cf165d4-fd81-43ff-8051-4019e77ac049',
    actionType: 'open_menu',
    status: 'pending',
    supportPrefill: false,
    description: '打开检修项目立项备案台账。',
    keywords: ['检修项目立项台账', '检修项目备案台账', '检修项目立项备案台账'],
  },
  {
    businessModule: '运维模块',
    workflowName: '检修项目立项（备案）审批',
    id: 'maintenance_project_approval',
    formCode: 'jxxmlxbaspbForm',
    funcId: '8ed14722-111b-4d42-9ccb-5737341a89ec',
    actionType: 'open_form_prefill',
    status: 'pending',
    supportPrefill: true,
    description: '打开检修项目立项备案审批表。',
    keywords: ['检修项目立项', '检修项目备案审批', '检修项目立项备案审批'],
  },
  {
    businessModule: '运维模块',
    workflowName: '运维系统看板',
    id: 'operation_maintenance_dashboard',
    formCode: 'zhbgkbdh',
    funcId: 'dd4424da-ed41-49c6-83f2-c758912a135a',
    actionType: 'open_menu',
    status: 'pending',
    supportPrefill: false,
    description: '打开运维系统看板。',
    keywords: ['运维系统看板', '运维看板'],
  },
  {
    businessModule: '科技模块',
    workflowName: '科技创新项目（备案）审批台账',
    id: 'technology_project_approval_list',
    formCode: 'technologyPage',
    funcId: '3ee222e9-cd14-40ac-96c1-51103b3e3624',
    actionType: 'open_menu',
    status: 'pending',
    supportPrefill: false,
    description: '打开科技创新项目备案审批台账。',
    keywords: ['科技创新项目', '科技创新项目台账', '科技创新项目备案审批台账'],
  },
  {
    businessModule: '综合模块',
    workflowName: '领导人员外出报备',
    id: 'leader_out_report',
    formCode: 'ldrywcbblList',
    funcId: '3f2c1a18-a190-45d4-9104-e8f30b6d275f',
    actionType: 'open_menu',
    status: 'pending',
    supportPrefill: false,
    description: '打开领导人员外出报备入口。',
    keywords: ['领导人员外出', '领导人员外出报备', '领导外出报备', '外出报备'],
  },
  {
    businessModule: '综合模块',
    workflowName: '学生实习、公众接待申请',
    id: 'internship_reception_apply',
    formCode: 'InshipVaList',
    funcId: '13c7dd62-055a-4c7d-acd5-f3ff0b720e3e',
    actionType: 'open_menu',
    status: 'pending',
    supportPrefill: false,
    description: '打开学生实习、公众接待申请入口。',
    keywords: ['学生实习', '公众接待', '学生实习公众接待申请'],
  },
  {
    businessModule: '综合模块',
    workflowName: '信息披露申请',
    id: 'information_disclosure_apply',
    formCode: 'DisclosePage',
    funcId: 'd0727c7f-9bfe-49db-858c-eef3034c6d7b',
    actionType: 'open_form_prefill',
    status: 'pending',
    supportPrefill: true,
    description: '打开信息披露申请表。',
    keywords: ['信息披露', '信息披露申请'],
  },
  {
    businessModule: '综合模块',
    workflowName: '议案汇总清单审批',
    id: 'motion_catalog_approval',
    formCode: 'MotionCatalogCard',
    funcId: '79c113c3-531a-4258-b9a6-57059e863048',
    actionType: 'open_related',
    status: 'unsupported',
    supportPrefill: false,
    description: '菜单类型为联查，适合从关联业务中打开审批详情。',
    keywords: ['议案汇总', '议案汇总清单', '议案汇总清单审批'],
    unavailableReason: '该事项属于联查类入口，暂未接入直接打开协议。',
  },
]

const moduleAliases = [
  { moduleName: '采购模块', keywords: ['采购模块', '采购'] },
  { moduleName: '运维模块', keywords: ['运维模块', '运维'] },
  { moduleName: '科技模块', keywords: ['科技模块', '科技'] },
  { moduleName: '综合模块', keywords: ['综合模块', '综合'] },
]

const availabilityOrder = [
  { key: 'verified', label: '已开放' },
  { key: 'pending', label: '待实测' },
  { key: 'unsupported', label: '暂不支持' },
]

function getOpenActionLabel(workflowName) {
  return `打开${workflowName}`
}

function isExecutableAction(mapping) {
  return (
    mapping.status === 'verified' &&
    ['open_form', 'open_form_prefill', 'open_menu'].includes(mapping.actionType) &&
    Boolean(mapping.formCode) &&
    Boolean(mapping.funcId)
  )
}

function createActionPayload(mapping) {
  if (!isExecutableAction(mapping)) {
    return null
  }

  return {
    action: mapping.actionType === 'open_menu' ? 'open_menu' : 'open_form',
    formCode: mapping.formCode,
    funcId: mapping.funcId,
  }
}

function getWorkflowAvailability(mapping) {
  if (mapping.actionType === 'open_related') {
    return {
      executable: false,
      label: '暂不支持',
      reason: '不支持直接打开：联查类入口暂未接入直接打开协议。',
    }
  }

  if (mapping.status === 'pending') {
    return {
      executable: false,
      label: '待实测',
      reason: '待实测，暂未开放。',
    }
  }

  if (mapping.status === 'unsupported') {
    return {
      executable: false,
      label: '暂不支持',
      reason: '不支持直接打开。',
    }
  }

  if (!mapping.formCode || !mapping.funcId) {
    return {
      executable: false,
      label: '暂不支持',
      reason: '该事项暂未配置可执行动作。',
    }
  }

  if (mapping.id === 'purchase_request' && ENABLE_WORKFLOW_PREFILL) {
    return {
      executable: true,
      label: '已开放',
      reason: '',
    }
  }

  if (mapping.actionType === 'open_menu') {
    return {
      executable: true,
      label: '已开放',
      reason: '',
    }
  }

  return {
    executable: true,
    label: '已开放',
    reason: '',
  }
}

export const workflowActions = workflowMappings.map((mapping) => {
  const openActionLabel = getOpenActionLabel(mapping.workflowName)
  const actionPayload = createActionPayload(mapping)
  const availability = getWorkflowAvailability(mapping)
  const actions = []

  if (actionPayload) {
    actions.push(openActionLabel)
  }

  if (mapping.id === 'purchase_request' && actionPayload && ENABLE_WORKFLOW_PREFILL) {
    actions.push('帮我预填表单')
  }

  return {
    ...mapping,
    module: mapping.businessModule,
    name: mapping.workflowName,
    statusReason: availability.reason,
    workflowName: mapping.workflowName,
    description: mapping.description,
    actions,
    actionPayloads: actionPayload ? { [openActionLabel]: actionPayload } : {},
    availability,
    requiredFields: ENABLE_WORKFLOW_PREFILL ? mapping.prefillFields || [] : [],
    unavailableReason: availability.reason || mapping.unavailableReason || '',
  }
})

function getMatchedModuleName(content) {
  return moduleAliases.find((module) =>
    module.keywords.some((keyword) => content.includes(keyword)),
  )?.moduleName || ''
}

function isWorkflowListQuery(content) {
  const todoListIntent = [
    '查看我的待办',
    '查看待办',
    '我的待办',
    '待办任务',
    '待办事项',
    '我有哪些待办',
  ].some((keyword) => content.includes(keyword))

  if (todoListIntent) return true

  const listIntent = [
    '有什么流程',
    '有哪些流程',
    '都有什么流程',
    '能发起什么',
    '可以发起什么',
    '能办什么',
    '可以办什么',
    '流程清单',
    '流程列表',
    '配置了什么流程',
  ].some((keyword) => content.includes(keyword))

  return listIntent && ['流程', '发起', '办理', '权限', '模块'].some((keyword) => content.includes(keyword))
}

function groupWorkflows(workflows) {
  const moduleOrder = ['采购模块', '运维模块', '科技模块', '综合模块']

  return moduleOrder
    .map((moduleName) => ({
      moduleName,
      workflows: workflows.filter((workflow) => workflow.businessModule === moduleName),
    }))
    .filter((group) => group.workflows.length)
}

function groupWorkflowsByStatus(workflows) {
  return availabilityOrder
    .map((status) => ({
      ...status,
      workflows: workflows.filter((workflow) => workflow.status === status.key),
    }))
    .filter((group) => group.workflows.length)
}

export function buildWorkflowListMarkdown(workflows, options = {}) {
  const groups = groupWorkflows(workflows)
  const lines = []

  lines.push(
    options.todo
      ? '当前还不能直接读取您的个人待办，先为您列出可定位的业务入口；实际待办和可办理权限以 iGIX 为准。'
      : '当前展示的是已配置入口状态，实际可办理权限以 iGIX 为准。',
  )
  lines.push('')

  if (!groups.length) {
    lines.push('当前未找到已配置的流程入口。')
    return lines.join('\n').trim()
  }

  lines.push('当前已配置流程入口如下：')
  lines.push('请点击下方入口名称继续。')
  lines.push('')

  groups.forEach((group) => {
    lines.push(`### ${group.moduleName}`)
    groupWorkflowsByStatus(group.workflows).forEach((statusGroup) => {
      lines.push(`**${statusGroup.label}**`)
      statusGroup.workflows.forEach((workflow) => {
        lines.push(`- **${workflow.workflowName}**：${workflow.description}`)
      })
    })
    lines.push('')
  })

  return lines.join('\n').trim()
}

export function detectWorkflowAction(text = '', options = {}) {
  const content = String(text || '').trim()

  if (isWorkflowListQuery(content)) {
    const todoListIntent = [
      '查看我的待办',
      '查看待办',
      '我的待办',
      '待办任务',
      '待办事项',
      '我有哪些待办',
    ].some((keyword) => content.includes(keyword))
    const moduleName = getMatchedModuleName(content)
    const matchedWorkflows = moduleName
      ? workflowActions.filter((workflow) => workflow.businessModule === moduleName)
      : workflowActions

    return {
      matched: true,
      type: 'workflow_list',
      content: buildWorkflowListMarkdown(matchedWorkflows, { todo: todoListIntent }),
      workflowGroups: groupWorkflows(matchedWorkflows),
    }
  }

  const matchedWorkflow = workflowActions
    .map((workflow) => {
      const matchedKeywords = workflow.keywords.filter((keyword) => content.includes(keyword))
      return {
        workflow,
        matchedKeywords,
        maxKeywordLength: Math.max(0, ...matchedKeywords.map((keyword) => keyword.length)),
      }
    })
    .filter((item) => item.matchedKeywords.length)
    .sort((a, b) => b.maxKeywordLength - a.maxKeywordLength)[0]?.workflow

  if (!matchedWorkflow) {
    return {
      matched: false,
      type: 'workflow_selection',
      content: '我还没有定位到具体入口，请从下面选择要打开的业务表单或流程。',
      workflowGroups: groupWorkflows(workflowActions),
    }
  }

  const parsedFields = ENABLE_WORKFLOW_PREFILL
    ? matchedWorkflow.parseFields?.(content, options) || {}
    : {}

  return {
    matched: true,
    workflowName: matchedWorkflow.workflowName,
    description: matchedWorkflow.description,
    actions: matchedWorkflow.actions,
    actionPayloads: matchedWorkflow.actionPayloads || {},
    status: matchedWorkflow.status,
    statusReason: matchedWorkflow.statusReason,
    prefillFields: matchedWorkflow.prefillFields || [],
    parsedFields,
    requiredFields: matchedWorkflow.requiredFields,
    unavailableReason: matchedWorkflow.unavailableReason || '',
  }
}
