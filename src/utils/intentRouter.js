import { DATA_QUERY_INDICATORS } from '../config/dataQueryCatalog.js'

const officeStrongKeywords = [
  '整理会议纪要', '会议纪要', '整理纪要', '写一份', '帮我写', '生成', '草拟', '起草',
  '润色', '优化', '改写', '总结', '工作总结', '汇报材料', '通知', '公告', '摘要',
  '提炼', '事项说明', '审批说明', '情况说明', '写一个', '帮我整理', '整理一份', '形成材料', '输出成文档', '整理成',
]

const workflowStrongKeywords = [
  '打开', '发起', '填写', '提交', '新建', '创建', '进入', '跳转', '定位', '查看我的待办',
  '我的待办', '待办任务', '打开表单', '发起流程', '打开菜单', '采购请示单在哪', '合同评审在哪',
  '在哪里发起', '怎么发起', '帮我开', '领导人员外出报备', '领导外出报备',
]

const policyKeywords = [
  '制度', '办法', '规定', '哪些情况可以', '怎么申请', '如何申请', '标准是多少', '差旅费',
  '出差', '报销', '住宿费', '伙食补助', '公务车', '公务用车', '公务用车如何申请', '谁审批',
  '由谁审批', '怎么审批', '费用承担', '管理办法', '制度规定', '依据是什么', '是否可以',
  '能不能报销', '需要什么条件', '适用范围',
]

// 只收生产指标业务词，避免“统计会议纪要任务”等办公问题误入问数。
const dataMetricKeywords = [
  '焚烧量', '垃圾处理量', '处理量', '发电量', '上网电量', '入炉量', '生产量', '产量',
  '完成率', '生产指标', '生产完成情况', '生产经营指标', '生产情况',
  ...DATA_QUERY_INDICATORS.flatMap((indicator) => [indicator.name, ...indicator.aliases]),
]
const dataQueryKeywords = [
  '查询', '查一下', '查查', '看看', '对比', '统计', '多少', '怎么样', '完成情况',
  '排名', '排行', '最高', '最低', '数据',
]
const dataTimeKeywords = ['本月', '上月', '去年同期', '今年', '去年', '近三个月', '近半年', '累计', '季度', '月份', '日期', '时间范围']

const workflowActionPatterns = [
  {
    label: '办理具体流程事项',
    test: (content) =>
      content.includes('办理') &&
      ['申请单', '表单', '流程', '请示单', '待办', '采购请示', '合同评审', '出差申请', '公务用车申请']
        .some((keyword) => content.includes(keyword)),
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

function matchDataQuery(content) {
  const metricMatches = matchKeywords(content, dataMetricKeywords)
  const queryMatches = matchKeywords(content, dataQueryKeywords)
  const timeMatches = matchKeywords(content, dataTimeKeywords)

  if (!metricMatches.length || (!queryMatches.length && !timeMatches.length)) return []
  return [...metricMatches, ...queryMatches, ...timeMatches]
}

export function detectIntent(text = '', options = {}) {
  const content = String(text || '').trim()
  if (!content) return { modeKey: '', confidence: 0, reason: '输入为空' }
  return detectIntentWithMode(content, options)
}

export function detectIntentWithMode(text = '', options = {}) {
  const content = String(text || '').trim()
  if (!content) return { modeKey: '', confidence: 0, reason: '输入为空' }

  const currentModeKey = options.currentModeKey || ''
  const workflowActionMatches = matchWorkflowActions(content)
  const officeMatches = matchKeywords(content, officeStrongKeywords)
  const dataMatches = matchDataQuery(content)
  const policyMatches = matchKeywords(content, policyKeywords)

  if (workflowActionMatches.length) {
    return {
      modeKey: 'workflow',
      modeKeys: ['workflow'],
      confidence: 0.92,
      reason: `命中流程办理动作：${workflowActionMatches.join('、')}`,
    }
  }

  if (officeMatches.length) {
    const modeKeys = ['office-ai']
    if (dataMatches.length) modeKeys.unshift('data-query')
    return {
      modeKey: 'office-ai',
      modeKeys,
      confidence: 0.9,
      reason: `命中办公材料处理意图：${officeMatches.join('、')}`,
    }
  }

  if (dataMatches.length) {
    return {
      modeKey: 'data-query',
      modeKeys: ['data-query'],
      confidence: 0.9,
      reason: `命中生产指标问数意图：${dataMatches.join('、')}`,
    }
  }

  if (policyMatches.length) {
    return {
      modeKey: 'policy',
      modeKeys: ['policy'],
      confidence: Math.min(0.92, 0.66 + policyMatches.length * 0.1),
      reason: `命中制度咨询关键词：${policyMatches.join('、')}`,
    }
  }

  // 没有明确跨域意图时继承当前模块，承接“那上个月呢”“详细一点”等上下文追问。
  if (currentModeKey === 'data-query') {
    return {
      modeKey: '',
      modeKeys: ['data-query'],
      confidence: 0.7,
      reason: '当前为智能问数，保持生产指标上下文',
    }
  }
  if (currentModeKey === 'workflow') {
    return {
      modeKey: '',
      modeKeys: ['workflow'],
      confidence: 0.1,
      reason: '当前为流程助手，但未命中明确流程动作',
    }
  }
  return { modeKey: '', modeKeys: [], confidence: 0, reason: '未命中明确意图关键词' }
}
