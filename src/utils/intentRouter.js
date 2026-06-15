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
  '办理',
  '申请',
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
]

const policyKeywords = [
  '制度',
  '办法',
  '规定',
  '标准是多少',
  '差旅费',
  '报销',
  '住宿费',
  '伙食补助',
  '公务用车如何申请',
  '谁审批',
  '费用承担',
  '管理办法',
  '依据是什么',
  '是否可以',
  '能不能报销',
]

const workflowWeakKeywords = ['流程', '表单', '审批', '审批流程']

function matchKeywords(content, keywords) {
  return keywords.filter((keyword) => content.includes(keyword))
}

export function detectIntent(text = '') {
  const content = String(text || '').trim()

  if (!content) {
    return {
      modeKey: '',
      confidence: 0,
      reason: '输入为空',
    }
  }

  const officeMatches = matchKeywords(content, officeStrongKeywords)
  const workflowStrongMatches = matchKeywords(content, workflowStrongKeywords)
  const policyMatches = matchKeywords(content, policyKeywords)
  const workflowWeakMatches = matchKeywords(content, workflowWeakKeywords)

  if (officeMatches.length) {
    return {
      modeKey: 'office',
      confidence: 0.9,
      reason: `命中办公材料处理意图：${officeMatches.join('、')}`,
    }
  }

  if (content.includes('公务用车如何申请') || (content.includes('公务用车') && content.includes('如何申请'))) {
    return {
      modeKey: 'policy',
      confidence: 0.88,
      reason: '命中制度查询关键词：公务用车如何申请',
    }
  }

  if (workflowStrongMatches.length) {
    return {
      modeKey: 'workflow',
      confidence: 0.86,
      reason: `命中流程办理动作：${workflowStrongMatches.join('、')}`,
    }
  }

  if (policyMatches.length) {
    return {
      modeKey: 'policy',
      confidence: Math.min(0.9, 0.62 + policyMatches.length * 0.12),
      reason: `命中制度查询关键词：${policyMatches.join('、')}`,
    }
  }

  if (workflowWeakMatches.length) {
    return {
      modeKey: '',
      confidence: 0.2,
      reason: `仅命中流程弱信号，不自动切换：${workflowWeakMatches.join('、')}`,
    }
  }

  return {
    modeKey: '',
    confidence: 0,
    reason: '未命中明确意图关键词',
  }
}
