/*
 * Rebuild the Dify Master Chatflow from the already configured policy and
 * enterprise-analysis drafts.
 *
 * This script is intended to run inside an authenticated Dify console page.
 * It only saves the Master draft. Publishing is a separate, explicit step so
 * the old published version remains the rollback point until draft tests pass.
 * No API keys or database credentials belong in this file.
 */

const MASTER_APP_ID = '8135ce3d-d508-45da-be98-d20a0ec743bb'
const POLICY_APP_ID = 'd9cff882-0120-4e7a-98ad-58ede0ac36cb'
const DATA_APP_ID = '200bb456-20bf-48ab-af38-c7b9e59ff070'

const deepCopy = (value) => JSON.parse(JSON.stringify(value))

const getCsrfHeaders = () => {
  const cookie = document.cookie.split('; ').find((item) => item.startsWith('csrf_token='))
  const csrf = cookie ? decodeURIComponent(cookie.split('=').slice(1).join('=')) : ''
  return {
    'Content-Type': 'application/json',
    'X-CSRF-Token': csrf,
  }
}

const getDraft = async (appId) => {
  const response = await fetch(`/console/api/apps/${appId}/workflows/draft`, {
    credentials: 'include',
    headers: getCsrfHeaders(),
  })
  if (!response.ok) throw new Error(`读取 Dify 草稿失败: ${appId} ${response.status}`)
  return response.json()
}

const replaceTemplateNodeIds = (value, idMap) => {
  if (typeof value !== 'string') return value
  return Object.entries(idMap).reduce(
    (result, [from, to]) => result.replaceAll(`{{#${from}.`, `{{#${to}.`),
    value,
  )
}

const replaceTemplateNodeIdsDeep = (value, idMap) => {
  if (typeof value === 'string') return replaceTemplateNodeIds(value, idMap)
  if (Array.isArray(value)) return value.map((item) => replaceTemplateNodeIdsDeep(item, idMap))
  if (!value || typeof value !== 'object') return value
  return Object.fromEntries(
    Object.entries(value).map(([key, item]) => [key, replaceTemplateNodeIdsDeep(item, idMap)]),
  )
}

const remapSelectorNodeIds = (selector, idMap) => {
  if (!Array.isArray(selector) || selector.length === 0) return selector
  return [idMap[selector[0]] || selector[0], ...selector.slice(1)]
}

const remapNodeReferences = (node, idMap) => {
  const remapped = replaceTemplateNodeIdsDeep(node, idMap)
  if (remapped.data?.context) {
    remapped.data.context.variable_selector = remapSelectorNodeIds(
      remapped.data.context.variable_selector,
      idMap,
    )
  }
  for (const key of [
    'query_variable_selector',
    'input_variable_selector',
    'output_variable_selector',
    'answer_variable_selector',
  ]) {
    if (remapped.data && Array.isArray(remapped.data[key])) {
      remapped.data[key] = remapSelectorNodeIds(remapped.data[key], idMap)
    }
  }
  for (const variable of remapped.data?.variables || []) {
    variable.value_selector = remapSelectorNodeIds(variable.value_selector, idMap)
  }
  for (const branch of remapped.data?.cases || []) {
    for (const condition of branch.conditions || []) {
      condition.variable_selector = remapSelectorNodeIds(condition.variable_selector, idMap)
    }
  }
  return remapped
}

const nodeMapFor = (nodes, prefix, sharedStartId, sourceStartId) =>
  Object.fromEntries(
    nodes.map((node) => [
      node.id,
      node.id === sourceStartId ? sharedStartId : `${prefix}${node.id}`,
    ]),
  )

const cloneNodes = (nodes, idMap, offset = { x: 0, y: 0 }, excludedIds = new Set()) =>
  nodes
    .filter((node) => !excludedIds.has(node.id))
    .map((source) => {
      const node = remapNodeReferences(deepCopy(source), idMap)
      node.id = idMap[source.id] || source.id
      node.position = {
        x: Number(source.position?.x || source.positionAbsolute?.x || 0) + offset.x,
        y: Number(source.position?.y || source.positionAbsolute?.y || 0) + offset.y,
      }
      node.positionAbsolute = { ...node.position }
      node.selected = false
      if (node.data) node.data.selected = false
      return node
    })

const cloneEdges = (
  edges,
  idMap,
  excludedSources = new Set(),
  excludedTargets = new Set(),
) =>
  edges
    .filter((edge) => !excludedSources.has(edge.source))
    .filter((edge) => !excludedTargets.has(edge.target))
    .map((source) => {
      const edge = deepCopy(source)
      edge.source = idMap[source.source] || source.source
      edge.target = idMap[source.target] || source.target
      edge.id = `${edge.source}-${edge.sourceHandle || 'source'}-${edge.target}-target`
      edge.selected = false
      return edge
    })

const graphEdge = (source, target, sourceType, targetType, sourceHandle = 'source') => ({
  id: `${source}-${sourceHandle}-${target}-target`,
  source,
  sourceHandle,
  target,
  targetHandle: 'target',
  type: 'custom',
  zIndex: 0,
  data: {
    sourceType,
    targetType,
    isInIteration: false,
    isInLoop: false,
  },
})

const cleanLlmTemplate = (template, id, title, systemText, userText, position) => {
  const node = deepCopy(template)
  node.id = id
  node.position = position
  node.positionAbsolute = { ...position }
  node.selected = false
  node.data = {
    ...node.data,
    title,
    type: 'llm',
    selected: false,
    context: { enabled: false, variable_selector: [] },
    memory: {
      query_prompt_template: '{{#sys.query#}}',
      window: { enabled: false, size: 1 },
      role_prefix: { user: '', assistant: '' },
    },
    prompt_template: [
      { id: `${id}-system`, role: 'system', text: systemText },
      { id: `${id}-user`, role: 'user', text: userText },
    ],
  }
  return node
}

const cleanAnswerTemplate = (template, id, title, answer, position) => {
  const node = deepCopy(template)
  node.id = id
  node.position = position
  node.positionAbsolute = { ...position }
  node.selected = false
  node.data = {
    ...node.data,
    title,
    type: 'answer',
    answer,
    selected: false,
    variables: [],
  }
  return node
}

const firstTargetAfterStart = (graph, startId) => {
  const edge = graph.edges.find((item) => item.source === startId)
  if (!edge) throw new Error(`工作流缺少开始节点出口: ${startId}`)
  return edge.target
}

const routeHandle = (classifier, label) => {
  const branch = classifier.data.classes.find((item) => item.label === label)
  if (!branch) throw new Error(`问题分类器缺少分支: ${label}`)
  return String(branch.id)
}

async function run() {
  const [master, policy, data] = await Promise.all([
    getDraft(MASTER_APP_ID),
    getDraft(POLICY_APP_ID),
    getDraft(DATA_APP_ID),
  ])

  const masterGraph = master.graph || {}
  const policyGraph = policy.graph || {}
  const dataGraph = data.graph || {}
  const masterStart = masterGraph.nodes.find((node) => node.data?.type === 'start')
  const masterClassifier = masterGraph.nodes.find((node) => node.data?.type === 'question-classifier')
  const masterLlm = masterGraph.nodes.find((node) => node.data?.type === 'llm')
  const masterAnswer = masterGraph.nodes.find((node) => node.data?.type === 'answer')
  const policyStart = policyGraph.nodes.find((node) => node.data?.type === 'start')
  const dataStart = dataGraph.nodes.find((node) => node.data?.type === 'start')

  if (!masterStart || !masterClassifier || !masterLlm || !masterAnswer || !policyStart || !dataStart) {
    throw new Error('当前 Dify 草稿缺少可复用的开始、分类器、LLM 或回复节点模板')
  }

  const masterStartId = masterStart.id
  const policyMap = nodeMapFor(policyGraph.nodes, 'master_policy_', masterStartId, policyStart.id)
  const dataMap = nodeMapFor(dataGraph.nodes, 'master_data_', masterStartId, dataStart.id)
  const compositeMap = nodeMapFor(dataGraph.nodes, 'master_composite_', masterStartId, dataStart.id)

  const start = deepCopy(masterStart)
  start.data = {
    ...start.data,
    title: '用户问题',
    variables: [
      {
        variable: 'query',
        label: '用户问题',
        type: 'text-input',
        required: true,
        max_length: 2000,
        hint: '',
        placeholder: '',
        default: '',
        options: [],
      },
      {
        variable: 'analysis_state',
        label: '上一轮分析状态',
        type: 'paragraph',
        required: false,
        max_length: 12000,
        options: [],
      },
      {
        variable: 'auth_context_json',
        label: '网关授权上下文',
        type: 'paragraph',
        required: false,
        max_length: 30000,
        options: [],
      },
      {
        variable: 'clarification_json',
        label: '澄清候选选择',
        type: 'paragraph',
        required: false,
        max_length: 6000,
        options: [],
      },
    ],
    selected: false,
  }

  const classifier = deepCopy(masterClassifier)
  classifier.data.selected = false
  classifier.position = { x: 320, y: 120 }
  classifier.positionAbsolute = { ...classifier.position }

  const policyNodes = cloneNodes(policyGraph.nodes, policyMap, { x: 760, y: -80 }, new Set([policyStart.id]))
  const policyEdges = cloneEdges(policyGraph.edges, policyMap, new Set([policyStart.id]))
  const dataNodes = cloneNodes(dataGraph.nodes, dataMap, { x: 720, y: 430 }, new Set([dataStart.id]))
  const dataEdges = cloneEdges(dataGraph.edges, dataMap, new Set([dataStart.id]))

  const compositeFinalId = compositeMap[dataGraph.nodes.find((node) => node.data?.type === 'answer')?.id]
  const compositeNodes = cloneNodes(
    dataGraph.nodes,
    compositeMap,
    { x: 720, y: 980 },
    new Set([dataStart.id, dataGraph.nodes.find((node) => node.data?.type === 'answer')?.id]),
  )
  const compositeEdges = cloneEdges(
    dataGraph.edges,
    compositeMap,
    new Set([dataStart.id, dataGraph.nodes.find((node) => node.data?.type === 'answer')?.id]),
    new Set([dataGraph.nodes.find((node) => node.data?.type === 'answer')?.id]),
  )

  const flowLlmId = 'master_flow_llm'
  const flowAnswerId = 'master_flow_answer'
  const officeLlmId = 'master_office_llm'
  const officeAnswerId = 'master_office_answer'
  const compositeWriterId = 'master_composite_writer'
  const compositeAnswerId = 'master_composite_answer'

  const flowLlm = cleanLlmTemplate(
    masterLlm,
    flowLlmId,
    '流程指引与动作建议',
    '你是环宝流程助手。只处理流程入口、表单和待办指引。不要声称已经替用户打开或提交系统。对于采购请示单，输出简洁的结构化动作建议，正文末尾附加一行 JSON：{"action":"open_form","label":"打开采购请示单","payload":{"formCode":"CGQSD"}}。其他未验证入口只能说明需要业务确认。',
    '{{#sys.query#}}',
    { x: 760, y: 160 },
  )
  const flowAnswer = cleanAnswerTemplate(masterAnswer, flowAnswerId, '流程助手回复', `{{#${flowLlmId}.text#}}`, { x: 1080, y: 160 })

  const officeLlm = cleanLlmTemplate(
    masterLlm,
    officeLlmId,
    '办公材料处理',
    '你是环宝办公智能助手。只处理会议纪要、通知、公文、总结、汇报、邮件和润色改写。直接输出可使用的正文，不要输出 SQL、Dify 节点名、内部工作流信息或 think 标签。',
    '{{#sys.query#}}',
    { x: 760, y: 340 },
  )
  const officeAnswer = cleanAnswerTemplate(masterAnswer, officeAnswerId, '办公智能回复', `{{#${officeLlmId}.text#}}`, { x: 1080, y: 340 })

  const compositeWriter = cleanLlmTemplate(
    masterLlm,
    compositeWriterId,
    '问数结果转经营材料',
    '你是国企经营分析材料撰写助手。以下是已经由确定性查询和统计分析节点生成的结果 JSON。只能引用其中出现的数字和事实，不能自行计算、补造或把相关线索写成因果结论。输出一份适合领导阅读的经营分析材料，并明确数据口径。\n\n确定性分析结果：\n{{#master_composite_analysis_audit.result_text#}}',
    '{{#sys.query#}}',
    { x: 1080, y: 980 },
  )
  const compositeAnswer = cleanAnswerTemplate(masterAnswer, compositeAnswerId, '经营分析材料', `{{#${compositeWriterId}.text#}}`, { x: 1400, y: 980 })

  const nodes = [
    start,
    classifier,
    ...policyNodes,
    ...dataNodes,
    ...compositeNodes,
    flowLlm,
    flowAnswer,
    officeLlm,
    officeAnswer,
    compositeWriter,
    compositeAnswer,
  ]

  const edges = [
    graphEdge(masterStartId, masterClassifier.id, 'start', 'question-classifier'),
    graphEdge(masterClassifier.id, policyMap[firstTargetAfterStart(policyGraph, policyStart.id)], 'question-classifier', 'knowledge-retrieval', routeHandle(classifier, 'qa_policy')),
    graphEdge(masterClassifier.id, dataMap[firstTargetAfterStart(dataGraph, dataStart.id)], 'question-classifier', 'llm', routeHandle(classifier, 'query_data')),
    graphEdge(masterClassifier.id, flowLlmId, 'question-classifier', 'llm', routeHandle(classifier, 'flow_guide')),
    graphEdge(masterClassifier.id, compositeMap[firstTargetAfterStart(dataGraph, dataStart.id)], 'question-classifier', 'llm', routeHandle(classifier, 'composite_data_to_doc')),
    graphEdge(masterClassifier.id, officeLlmId, 'question-classifier', 'llm', routeHandle(classifier, 'general_office')),
    ...policyEdges,
    ...dataEdges,
    ...compositeEdges,
    graphEdge(compositeMap['analysis_audit'], compositeWriterId, 'code', 'llm'),
    graphEdge(compositeWriterId, compositeAnswerId, 'llm', 'answer'),
    graphEdge(flowLlmId, flowAnswerId, 'llm', 'answer'),
    graphEdge(officeLlmId, officeAnswerId, 'llm', 'answer'),
  ]

  const nextDraft = deepCopy(master)
  nextDraft.graph = {
    ...nextDraft.graph,
    nodes,
    edges,
    viewport: { x: 0, y: 0, zoom: 0.42 },
  }
  // The data-query branch uses the Dify SQL tool and its app-scoped DB_* 
  // environment variables. Copy the already configured values into the
  // Master app without serializing them into this repository.
  nextDraft.environment_variables = deepCopy(data.environment_variables || [])

  const response = await fetch(`/console/api/apps/${MASTER_APP_ID}/workflows/draft`, {
    method: 'POST',
    credentials: 'include',
    headers: getCsrfHeaders(),
    body: JSON.stringify({
      graph: nextDraft.graph,
      features: nextDraft.features,
      environment_variables: nextDraft.environment_variables,
      conversation_variables: nextDraft.conversation_variables,
      rag_pipeline_variables: nextDraft.rag_pipeline_variables,
      hash: nextDraft.hash,
    }),
  })
  const result = await response.json().catch(() => ({}))
  if (!response.ok) throw new Error(`保存 Master 草稿失败: ${response.status} ${JSON.stringify(result)}`)

  return {
    status: response.status,
    previousDraftHash: master.hash,
    previousDraftNodeCount: masterGraph.nodes.length,
    previousDraftEdgeCount: masterGraph.edges.length,
    nodeCount: nodes.length,
    edgeCount: edges.length,
    branchHandles: {
      qa_policy: routeHandle(classifier, 'qa_policy'),
      query_data: routeHandle(classifier, 'query_data'),
      flow_guide: routeHandle(classifier, 'flow_guide'),
      composite_data_to_doc: routeHandle(classifier, 'composite_data_to_doc'),
      general_office: routeHandle(classifier, 'general_office'),
    },
    savedHash: result.hash || '',
  }
}

window.__huanbaoDifyMasterRebuildPromise = run()
