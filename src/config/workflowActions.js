export const workflowActions = [
  {
    workflowName: '采购请示单',
    description: '用于发起采购事项审批。',
    keywords: ['采购请示单', '采购申请', '发起采购', '打开采购'],
    actions: ['打开采购请示单', '查看填写说明', '帮我预填表单'],
    requiredFields: ['采购事项名称', '预算金额', '采购方式', '经办部门'],
  },
  {
    workflowName: '合同评审流程',
    description: '用于发起合同评审和审批办理。',
    keywords: ['合同评审', '合同审批', '发起合同', '合同评审单'],
    actions: ['打开合同评审单', '查看填写说明', '帮我预填表单'],
    requiredFields: ['合同名称', '相对方', '合同金额', '合同类型', '经办部门'],
  },
  {
    workflowName: '我的待办',
    description: '用于查看和处理当前待办事项。',
    keywords: ['待办', '我的待办', '查看待办'],
    actions: ['打开我的待办', '查看待办说明'],
    requiredFields: [],
  },
]

export function detectWorkflowAction(text = '') {
  const content = String(text || '').trim()
  const matchedWorkflow = workflowActions.find((workflow) =>
    workflow.keywords.some((keyword) => content.includes(keyword)),
  )

  if (!matchedWorkflow) {
    return {
      matched: false,
    }
  }

  return {
    matched: true,
    workflowName: matchedWorkflow.workflowName,
    description: matchedWorkflow.description,
    actions: matchedWorkflow.actions,
    requiredFields: matchedWorkflow.requiredFields,
  }
}
