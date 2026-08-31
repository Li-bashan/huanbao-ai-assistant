export const DIFY_EXECUTION_EVENTS = Object.freeze([
  'workflow_started',
  'workflow_finished',
  'node_started',
  'node_finished',
  'node_retry',
  'iteration_started',
  'iteration_next',
  'iteration_completed',
  'loop_started',
  'loop_next',
  'loop_completed',
  'parallel_branch_started',
  'parallel_branch_finished',
  'datasource_processing',
  'datasource_completed',
  'datasource_error',
  'workflow_paused',
  'human_input_required',
  'human_input_form_timeout',
  'agent_thought',
  'error',
  'message_end',
])

export const DIFY_STREAM_EVENTS = Object.freeze([
  'message',
  'agent_message',
  'agent_thought',
  'message_file',
  'message_end',
  'message_replace',
  'workflow_started',
  'workflow_finished',
  'node_started',
  'node_finished',
  'iteration_started',
  'iteration_next',
  'iteration_completed',
  'loop_started',
  'loop_next',
  'loop_completed',
  'parallel_branch_started',
  'parallel_branch_finished',
  'text_chunk',
  'text_replace',
  'agent_log',
  'tts_message',
  'tts_message_end',
  'human_input_required',
  'human_input_form_filled',
  'human_input_form_timeout',
  'workflow_paused',
  'datasource_processing',
  'datasource_completed',
  'datasource_error',
  'error',
])

const EXECUTION_EVENT_SET = new Set(DIFY_EXECUTION_EVENTS)
const ACTIVE_NODE_STATUSES = new Set(['running', 'retrying'])

const normalizeStatus = (status, fallback = 'success') => {
  const normalized = String(status || '').toLowerCase()
  if (['failed', 'failure', 'error', 'exception'].includes(normalized)) return 'failed'
  if (['stopped', 'stop', 'cancelled', 'canceled'].includes(normalized)) return 'stopped'
  if (normalized === 'paused') return 'paused'
  if (['retry', 'retrying'].includes(normalized)) return 'retrying'
  if (['success', 'succeeded', 'completed', 'complete'].includes(normalized)) return 'success'
  if (['running', 'processing', 'started'].includes(normalized)) return 'running'
  if (['waiting', 'pending'].includes(normalized)) return 'waiting'
  return fallback
}

const getEventData = (event) =>
  event?.data && typeof event.data === 'object' ? event.data : event || {}

const getValue = (data, ...keys) => {
  for (const key of keys) {
    if (data?.[key] !== undefined && data?.[key] !== null && data[key] !== '') return data[key]
  }
  return ''
}

const getEventTime = (data) => {
  const value = Number(getValue(data, 'created_at', 'createdAt'))
  return Number.isFinite(value) && value > 0 ? value * (value < 10_000_000_000 ? 1000 : 1) : Date.now()
}

const getExecutionKey = (data, eventType) =>
  String(
    getValue(data, 'node_id', 'nodeId', 'id') ||
      [
        getValue(data, 'node_id', 'nodeId'),
        getValue(data, 'node_type', 'nodeType'),
        getValue(data, 'iteration_id', 'iterationId'),
        getValue(data, 'loop_id', 'loopId'),
        getValue(data, 'index'),
      ]
        .filter((value) => value !== '')
        .join(':') ||
      `execution-${eventType}-${Date.now()}`,
  )

const getExecutionTitle = (data, eventType) =>
  String(
    getValue(data, 'title', 'node_title', 'nodeTitle', 'nodeName') ||
      ({
        iteration_started: '迭代执行',
        loop_started: '循环执行',
        parallel_branch_started: '并行分支',
        datasource_processing: '处理数据源',
      }[eventType] || getValue(data, 'node_type', 'nodeType') || '执行节点'),
  )

const getElapsedTime = (data, node) => {
  const value = Number(getValue(data, 'elapsed_time', 'elapsedTime'))
  if (Number.isFinite(value)) return value
  if (node?.startedAt) return Math.max(0, (Date.now() - node.startedAt) / 1000)
  return null
}

const hasError = (data) => Boolean(getValue(data, 'error', 'message'))

const isFailedData = (data) =>
  normalizeStatus(getValue(data, 'status', 'state', 'result'), '') === 'failed' || hasError(data)

const getBusinessCapabilities = (modeKey) => {
  if (modeKey === 'data-query') return ['智能问数']
  if (modeKey === 'office' || modeKey === 'office-ai' || modeKey === 'general') return ['办公智能']
  if (modeKey === 'policy') return ['制度问答']
  return []
}

export const createDifyExecutionProcess = (options = {}) => ({
  status: 'pending',
  visible: options.visible === true,
  expanded: true,
  userExpanded: false,
  modeKey: options.modeKey || '',
  capabilities: options.capabilities || getBusinessCapabilities(options.modeKey),
  stage: options.stage || '',
  workflowRunId: '',
  currentNodeId: '',
  startedAt: null,
  finishedAt: null,
  error: '',
  nodes: [],
  thoughts: [],
})

const upsertNode = (process, data, eventType, status) => {
  const key = getExecutionKey(data, eventType)
  let node = process.nodes.find((item) => item.key === key)

  if (!node) {
    node = {
      key,
      title: getExecutionTitle(data, eventType),
      nodeType: String(getValue(data, 'node_type', 'nodeType') || eventType),
      status: 'waiting',
      startedAt: null,
      finishedAt: null,
      elapsedTime: null,
      error: '',
      retryCount: 0,
    }
    process.nodes.push(node)
  }

  node.title = getExecutionTitle(data, eventType)
  node.nodeType = String(getValue(data, 'node_type', 'nodeType') || node.nodeType)
  node.status = status

  if (status === 'running' || status === 'retrying') {
    node.startedAt ||= getEventTime(data)
    process.currentNodeId = node.key
  }

  if (status === 'retrying') node.retryCount += 1

  if (status === 'success' || status === 'failed' || status === 'stopped' || status === 'paused') {
    node.finishedAt = getEventTime(data)
    node.elapsedTime = getElapsedTime(data, node)
    node.error = String(getValue(data, 'error', 'message') || '')
    if (node.key === process.currentNodeId) process.currentNodeId = ''
  }

  if (hasError(data)) node.error = String(getValue(data, 'error', 'message'))
  return node
}

const finishProcess = (process, status, data = {}) => {
  if (!process.visible || process.status === 'stopped') return

  process.status = status
  process.finishedAt = getEventTime(data)
  process.error = String(getValue(data, 'error', 'message') || process.error || '')
  process.nodes.forEach((node) => {
    if (!ACTIVE_NODE_STATUSES.has(node.status)) return
    node.status = status === 'failed' ? 'failed' : 'success'
    node.finishedAt = process.finishedAt
    node.elapsedTime = getElapsedTime(data, node)
    if (status === 'failed' && !node.error) node.error = process.error
  })
  process.currentNodeId = ''
  if (!process.userExpanded) process.expanded = false
}

export const stopDifyExecution = (process) => {
  if (!process) return
  process.status = 'stopped'
  process.finishedAt = Date.now()
  process.nodes.forEach((node) => {
    if (ACTIVE_NODE_STATUSES.has(node.status)) {
      node.status = 'stopped'
      node.finishedAt = process.finishedAt
      node.elapsedTime = getElapsedTime({}, node)
    }
  })
  process.currentNodeId = ''
  process.error = ''
  if (!process.userExpanded) process.expanded = false
}

export const failDifyExecution = (process, error) => {
  if (!process) return
  process.visible = true
  process.error = String(error?.message || error || '')
  finishProcess(process, 'failed', { error: process.error })
}

export const completeDifyExecution = (process) => {
  if (!process?.visible || process.status === 'stopped' || process.status === 'failed') return
  finishProcess(process, 'success')
}

export const applyDifyExecutionEvent = (process, event) => {
  if (!process || !event || !EXECUTION_EVENT_SET.has(event.event)) return process

  const eventType = event.event
  const data = getEventData(event)
  if (eventType === 'message_end' && !process.visible) return process
  process.visible = true

  if (eventType === 'workflow_started') {
    process.status = 'running'
    process.workflowRunId = String(getValue(data, 'id', 'workflow_run_id', 'workflowRunId') || '')
    process.startedAt = getEventTime(data)
    if (!process.userExpanded) process.expanded = true
    return process
  }

  if (eventType === 'node_started') {
    process.status = 'running'
    upsertNode(process, data, eventType, 'running')
    return process
  }

  if (eventType === 'node_retry') {
    process.status = 'running'
    upsertNode(process, data, eventType, 'retrying')
    return process
  }

  if (eventType === 'node_finished') {
    const status = normalizeStatus(getValue(data, 'status', 'state'), hasError(data) ? 'failed' : 'success')
    upsertNode(process, data, eventType, status)
    if (status === 'failed') process.error = String(getValue(data, 'error', 'message') || '')
    return process
  }

  if (eventType === 'iteration_next' || eventType === 'loop_next') {
    process.status = 'running'
    upsertNode(process, data, eventType, 'running')
    return process
  }

  if (eventType.endsWith('_started') || eventType === 'datasource_processing') {
    process.status = 'running'
    upsertNode(process, data, eventType, 'running')
    return process
  }

  if (
    eventType.endsWith('_completed') ||
    eventType === 'parallel_branch_finished' ||
    eventType === 'datasource_completed' ||
    eventType === 'datasource_error'
  ) {
    const status = eventType === 'datasource_error' || isFailedData(data) ? 'failed' : 'success'
    upsertNode(process, data, eventType.replace('_completed', '_started'), status)
    if (status === 'failed') process.error = String(getValue(data, 'error', 'message') || '')
    return process
  }

  if (eventType === 'agent_thought') {
    const thought = {
      key: String(getValue(data, 'id', 'position') || `thought-${process.thoughts.length + 1}`),
      thought: String(getValue(data, 'thought') || ''),
      observation: String(getValue(data, 'observation') || ''),
      tool: String(getValue(data, 'tool') || ''),
    }
    if (!process.thoughts.some((item) => item.key === thought.key)) process.thoughts.push(thought)
    process.thoughts = process.thoughts.slice(-30)
    return process
  }

  if (
    eventType === 'workflow_paused' ||
    eventType === 'human_input_required' ||
    eventType === 'human_input_form_timeout'
  ) {
    process.status = 'paused'
    process.finishedAt = null
    process.error = String(getValue(data, 'error', 'message') || '')
    if (!process.userExpanded) process.expanded = true
    return process
  }

  if (eventType === 'workflow_finished') {
    finishProcess(process, isFailedData(data) ? 'failed' : 'success', data)
    return process
  }

  if (eventType === 'error') {
    failDifyExecution(process, getValue(data, 'message', 'error') || '执行失败')
    return process
  }

  if (eventType === 'message_end') {
    if (process.status === 'running' || process.status === 'pending') finishProcess(process, 'success', data)
  }

  return process
}
