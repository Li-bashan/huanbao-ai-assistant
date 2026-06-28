const officeStrongKeywords = [
  '整理会议纪要',
  '会议纪要',
  '整理纪要',
  '写一份',
  '帮我写',
  '生成',
  '草拟',
  '起草',
  '润色',
  '优化',
  '改写',
  '总结',
  '工作总结',
  '汇报材料',
  '通知',
  '公告',
  '摘要',
  '提炼',
  '事项说明',
  '审批说明',
  '帮我整理',
  '整理一份',
  '形成材料',
  '输出成文档',
  '整理成',
]

const workflowStrongKeywords = [
  '打开',
  '发起',
  '填写',
  '提交',
  '新建',
  '创建',
  '进入',
  '跳转',
  '定位',
  '查看我的待办',
  '我的待办',
  '待办任务',
  '打开表单',
  '发起流程',
  '打开菜单',
  '采购请示单在哪',
  '合同评审在哪',
  '在哪里发起',
  '怎么发起',
  '帮我开',
]

const policyKeywords = [
  '制度',
  '办法',
  '规定',
  '哪些情况可以',
  '怎么申请',
  '如何申请',
  '标准是多少',
  '差旅费',
  '出差',
  '报销',
  '住宿费',
  '伙食补助',
  '公务车',
  '公务用车',
  '公务用车如何申请',
  '谁审批',
  '由谁审批',
  '怎么审批',
  '费用承担',
  '管理办法',
  '制度规定',
  '依据是什么',
  '是否可以',
  '能不能报销',
  '需要什么条件',
  '适用范围',
]

const workflowWeakKeywords = ['流程', '表单', '审批', '审批流程']
const policyModeKeys = ['policy']
const workflowModeKeys = ['workflow']
const workflowActionPatterns = [
  {
    label: '办理具体流程事项',
    test: (content) =>
      content.includes('办理') &&
      [
        '申请单',
        '表单',
        '流程',
        '请示单',
        '待办',
        '采购请示',
        '合同评审',
        '出差申请',
        '公务用车申请',
      ].some((keyword) => content.includes(keyword)),
  },
  {
    label: '申请单办理动作',
    test: (content) =>
      ['打开', '发起', '填写', '提交', '新建', '创建', '进入', '跳转'].some((action) =>
        content.includes(action),
      ) && content.includes('申请单'),
  },
]

function matchKeywords(content, keywords) {
  return keywords.filter((keyword) => content.includes(keyword))
}

function matchWorkflowActions(content) {
  return [
    ...matchKeywords(content, workflowStrongKeywords),
    ...workflowActionPatterns
      .filter((pattern) => pattern.test(content))
      .map((pattern) => pattern.label),
  ]
}

export function detectIntent(text = '', options = {}) {
  const content = String(text || '').trim()

  if (!content) {
    return {
      modeKey: '',
      confidence: 0,
      reason: '输入为空',
    }
  }

  return detectIntentWithMode(content, options)
}

export function detectIntentWithMode(text = '', options = {}) {
  const content = String(text || '').trim()

  if (!content) {
    return {
      modeKey: '',
      confidence: 0,
      reason: '输入为空',
    }
  }

  const currentModeKey = options.currentModeKey || ''
  const officeMatches = matchKeywords(content, officeStrongKeywords)
  const workflowActionMatches = matchWorkflowActions(content)
  const policyMatches = matchKeywords(content, policyKeywords)
  const workflowWeakMatches = matchKeywords(content, workflowWeakKeywords)

  if (officeMatches.length) {
    return {
      modeKey: 'office',
      confidence: 0.9,
      reason: `命中办公材料处理意图：${officeMatches.join('、')}`,
    }
  }

  if (workflowActionMatches.length) {
    return {
      modeKey: 'workflow',
      confidence: 0.86,
      reason: `命中流程办理动作：${workflowActionMatches.join('、')}`,
    }
  }

  if (policyMatches.length) {
    return {
      modeKey: 'policy',
      confidence: Math.min(0.92, 0.66 + policyMatches.length * 0.1),
      reason: `命中制度咨询关键词：${policyMatches.join('、')}`,
    }
  }

  if (policyModeKeys.includes(currentModeKey) && workflowWeakMatches.length) {
    return {
      modeKey: 'policy',
      confidence: 0.62,
      reason: `当前为制度问答，流程弱信号按制度咨询处理：${workflowWeakMatches.join('、')}`,
    }
  }

  if (workflowWeakMatches.length) {
    return {
      modeKey: '',
      confidence: 0.2,
      reason: `仅命中流程弱信号，不自动切换：${workflowWeakMatches.join('、')}`,
    }
  }

  if (workflowModeKeys.includes(currentModeKey)) {
    return {
      modeKey: '',
      confidence: 0.1,
      reason: '当前为流程助手，但未命中明确流程动作',
    }
  }

  return {
    modeKey: '',
    confidence: 0,
    reason: '未命中明确意图关键词',
  }
}
