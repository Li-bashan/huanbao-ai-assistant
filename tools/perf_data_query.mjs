#!/usr/bin/env node

import { createHmac, randomUUID } from 'node:crypto'

const DEFAULT_QUERY = '今年项目公司发电量排名'
const DEFAULT_CONCURRENCY = 20
const DEFAULT_TIMEOUT_MS = 30_000
const METRIC_NAMES = [
  'hikaricp.connections.active',
  'hikaricp.connections.idle',
  'hikaricp.connections.max',
  'hikaricp.connections.pending',
]

const args = parseArgs(process.argv.slice(2))
if (args.help) {
  printUsage()
  process.exit(0)
}

const baseUrl = normalizeUrl(args.baseUrl || process.env.PERF_BASE_URL || 'http://127.0.0.1:8088')
const dataQueryBaseUrl = normalizeUrl(
  args.dataQueryBaseUrl || process.env.PERF_DATAQUERY_BASE_URL || '',
)
const identitySecret = String(
  args.identitySecret || process.env.PERF_IDENTITY_SECRET || '',
).trim()
const query = String(args.query || process.env.PERF_QUERY || DEFAULT_QUERY).trim()
const concurrency = positiveInt(
  args.concurrency || process.env.PERF_CONCURRENCY || DEFAULT_CONCURRENCY,
  'concurrency',
)
const timeoutMs = positiveInt(
  args.timeoutMs || process.env.PERF_TIMEOUT_MS || DEFAULT_TIMEOUT_MS,
  'timeoutMs',
)
const users = loadUsers(args.usersJson || process.env.PERF_USERS_JSON || '')

if (!identitySecret) fail('PERF_IDENTITY_SECRET is required; refusing to run without signed identity.')
if (!query) fail('query must not be blank')
if (users.length < concurrency) {
  fail(concurrency + ' concurrent requests require at least ' + concurrency + ' distinct authorized test users; got ' + users.length + '.')
}

const startedAt = new Date().toISOString()
const wallClockStart = performance.now()
const barrier = createBarrier(concurrency)
const requests = Array.from({ length: concurrency }, (_, index) => runRequest(
  users[index],
  index,
  barrier,
))
const results = await Promise.all(requests)
const wallClockMs = performance.now() - wallClockStart

const latencies = results
  .map((item) => item.latencyMs)
  .filter(Number.isFinite)
  .sort((a, b) => a - b)
const errors = results.filter((item) => !item.ok)
const completed = results.filter((item) => item.completed)
const chartDecisionReady = results.filter((item) => item.chartDecisionReady)
const decisionWithin10s = results.filter((item) => item.decisionReadyWithin10s)
const metrics = await probeHikariMetrics(dataQueryBaseUrl, timeoutMs)

const report = {
  test: 'data-query-20-concurrency',
  startedAt,
  finishedAt: new Date().toISOString(),
  target: baseUrl + '/api/ai/data-query/chat',
  query,
  concurrency,
  totalRequests: results.length,
  durationMs: round(wallClockMs),
  throughputQps: round(results.length / Math.max(wallClockMs / 1000, 0.001), 3),
  latencyMs: {
    average: round(average(latencies)),
    p95: round(percentile(latencies, 0.95)),
    p99: round(percentile(latencies, 0.99)),
    min: round(latencies[0]),
    max: round(latencies[latencies.length - 1]),
  },
  errors: {
    count: errors.length,
    rate: round((errors.length / Math.max(results.length, 1)) * 100, 2) + '%',
  },
  protocol: {
    completedCount: completed.length,
    chartDecisionReadyCount: chartDecisionReady.length,
    decisionWithin10sCount: decisionWithin10s.length,
    decisionWithin10sRate: round((decisionWithin10s.length / Math.max(results.length, 1)) * 100, 2) + '%',
  },
  hikari: metrics,
  requests: results.map(({ index, userId, status, latencyMs, completed: isCompleted, chartDecisionReady: isReady, decisionReadyWithin10s, error }) => ({
    index,
    userId,
    status,
    latencyMs: round(latencyMs),
    completed: isCompleted,
    chartDecisionReady: isReady,
    decisionReadyWithin10s,
    ...(error ? { error } : {}),
  })),
}

process.stdout.write(JSON.stringify(report, null, 2) + '\n')
if (errors.length > 0) process.exitCode = 1

async function runRequest(user, index, barrierPromise) {
  const started = performance.now()
  const requestId = 'perf-' + Date.now() + '-' + index + '-' + randomUUID()
  const conversationId = 'perf-' + user.userId + '-' + index + '-' + randomUUID()

  try {
    await barrierPromise()
    const controller = new AbortController()
    const timeout = setTimeout(() => controller.abort(), timeoutMs)
    let response
    let body
    try {
      response = await fetch(baseUrl + '/api/ai/data-query/chat', {
        method: 'POST',
        headers: {
          ...signedHeaders(user),
          Accept: 'text/event-stream',
        },
        body: JSON.stringify({
          query,
          conversationId,
          requestId,
          untrustedClientContext: user,
        }),
        signal: controller.signal,
      })
      body = await response.text()
    } finally {
      clearTimeout(timeout)
    }

    const events = response.ok ? parseSse(body) : []
    const analysis = findAnalysisResponse(events)
    const hasCompleted = events.some((event) => event.name === 'completed')
    const chartReady = hasCompleted && hasChart(analysis) && hasSummary(analysis)
    const latencyMs = performance.now() - started

    if (!response.ok) {
      return failure(index, user.userId, response.status, latencyMs, 'HTTP ' + response.status)
    }

    const errorEvent = events.find((event) => event.name === 'error')
    if (errorEvent) {
      return failure(index, user.userId, response.status, latencyMs, 'SSE error: ' + safeMessage(errorEvent.data))
    }
    if (!hasCompleted) {
      return failure(index, user.userId, response.status, latencyMs, 'completed event missing')
    }

    return {
      index,
      userId: user.userId,
      status: response.status,
      latencyMs,
      ok: true,
      completed: true,
      chartDecisionReady: chartReady,
      decisionReadyWithin10s: chartReady && latencyMs <= 10_000,
    }
  } catch (error) {
    return failure(
      index,
      user.userId,
      0,
      performance.now() - started,
      error?.name === 'AbortError' ? 'timeout after ' + timeoutMs + 'ms' : String(error?.message || error),
    )
  }
}

function signedHeaders(user) {
  const payload = Buffer.from(JSON.stringify({
    userId: user.userId,
    userCode: user.userCode || '',
    userName: user.userName,
    orgCode: user.orgCode || '',
    orgName: user.orgName || '',
    tenantId: user.tenantId || '',
  }), 'utf8').toString('base64url')
  const timestamp = String(Math.floor(Date.now() / 1000))
  const signature = createHmac('sha256', identitySecret)
    .update(timestamp + '.' + payload)
    .digest('hex')
  return {
    'Content-Type': 'application/json',
    'X-Portal-Identity': payload,
    'X-Portal-Identity-Timestamp': timestamp,
    'X-Portal-Identity-Signature': signature,
  }
}

async function probeHikariMetrics(serviceUrl, requestTimeoutMs) {
  if (!serviceUrl) {
    return { status: 'NOT_CONFIGURED', note: 'Set PERF_DATAQUERY_BASE_URL to probe actuator metrics.' }
  }

  const values = {}
  for (const metricName of METRIC_NAMES) {
    const controller = new AbortController()
    const timeout = setTimeout(() => controller.abort(), Math.min(requestTimeoutMs, 5_000))
    try {
      const response = await fetch(
        serviceUrl + '/actuator/metrics/' + encodeURIComponent(metricName),
        { headers: { Accept: 'application/json' }, signal: controller.signal },
      )
      const body = await response.text()
      if (!response.ok) {
        values[metricName] = { status: response.status }
        continue
      }
      const parsed = JSON.parse(body)
      values[metricName] = {
        status: response.status,
        measurements: parsed.measurements || [],
      }
    } catch (error) {
      values[metricName] = {
        status: 'ERROR',
        error: error?.name === 'AbortError' ? 'timeout' : String(error?.message || error),
      }
    } finally {
      clearTimeout(timeout)
    }
  }

  const exposed = Object.values(values).some((value) => value.status === 200)
  return {
    status: exposed ? 'EXPOSED' : 'NOT_EXPOSED',
    serviceUrl,
    metrics: values,
    note: exposed
      ? 'Values are sampled after the request batch; active/max/pending should be interpreted with the service time series.'
      : 'Hikari metrics are not exposed by the target service; connection occupancy cannot be claimed from this run.',
  }
}

function parseSse(text) {
  return text
    .split(/\r?\n\r?\n/)
    .map((block) => block.trim())
    .filter(Boolean)
    .map((block) => {
      const name = block.match(/^event:\s*(.+)$/m)?.[1]?.trim() || 'message'
      const dataLines = block.match(/^data:\s*(.*)$/gm) || []
      const raw = dataLines.map((line) => line.replace(/^data:\s?/, '')).join('\n')
      let data = raw
      try { data = JSON.parse(raw) } catch { /* keep non-JSON SSE data */ }
      return { name, data }
    })
}

function findAnalysisResponse(events) {
  for (const event of events) {
    const candidate = event.data?.response || event.data
    if (candidate?.protocolVersion === '2.0') return candidate
  }
  return null
}

function hasChart(response) {
  return Boolean(response?.content?.chart || response?.content?.charts || response?.chart)
}

function hasSummary(response) {
  return Boolean(
    response?.content?.summary
      || response?.content?.insights?.length
      || response?.content?.text
      || response?.content?.decision,
  )
}

function failure(index, userId, status, latencyMs, error) {
  return {
    index,
    userId,
    status,
    latencyMs,
    ok: false,
    completed: false,
    chartDecisionReady: false,
    decisionReadyWithin10s: false,
    error,
  }
}

function loadUsers(raw) {
  if (!raw) return []
  let parsed
  try { parsed = JSON.parse(raw) } catch { fail('PERF_USERS_JSON must be valid JSON.') }
  if (!Array.isArray(parsed)) fail('PERF_USERS_JSON must be an array.')
  const users = parsed.map((item) => ({
    userId: String(item?.userId || '').trim(),
    userCode: String(item?.userCode || item?.code || '').trim(),
    userName: String(item?.userName || item?.name || '').trim(),
    orgCode: String(item?.orgCode || item?.orgId || '').trim(),
    orgName: String(item?.orgName || item?.unitName || '').trim(),
    tenantId: String(item?.tenantId || '').trim(),
  }))
  const invalidIndex = users.findIndex((user) => !user.userId || !user.userName)
  if (invalidIndex >= 0) fail('PERF_USERS_JSON entry ' + (invalidIndex + 1) + ' needs userId and userName.')
  const distinct = new Set(users.map((user) => user.userId))
  if (distinct.size !== users.length) fail('PERF_USERS_JSON must contain distinct userId values.')
  return users
}

function parseArgs(values) {
  const result = {}
  for (const value of values) {
    if (value === '--help' || value === '-h') result.help = true
    else if (value.startsWith('--') && value.includes('=')) {
      const [key, ...rest] = value.slice(2).split('=')
      result[toCamelCase(key)] = rest.join('=')
    } else fail('Unknown argument: ' + value + '. Use --help for usage.')
  }
  return result
}

function createBarrier(count) {
  let release
  let arrived = 0
  const allReady = new Promise((resolve) => { release = resolve })
  return async () => {
    arrived += 1
    if (arrived === count) release()
    await allReady
  }
}

function normalizeUrl(value) {
  return String(value || '').trim().replace(/\/$/, '')
}

function positiveInt(value, name) {
  const parsed = Number.parseInt(value, 10)
  if (!Number.isInteger(parsed) || parsed <= 0) fail(name + ' must be a positive integer.')
  return parsed
}

function percentile(values, ratio) {
  if (!values.length) return NaN
  return values[Math.min(values.length - 1, Math.ceil(values.length * ratio) - 1)]
}

function average(values) {
  return values.length ? values.reduce((sum, value) => sum + value, 0) / values.length : NaN
}

function round(value, digits = 2) {
  return Number.isFinite(value) ? Number(value.toFixed(digits)) : null
}

function safeMessage(data) {
  const value = typeof data === 'string' ? data : JSON.stringify(data)
  return String(value || 'unknown SSE error').slice(0, 200)
}

function toCamelCase(value) {
  return value.replace(/-([a-z])/g, (_, letter) => letter.toUpperCase())
}

function fail(message) {
  console.error('ERROR: ' + message)
  process.exit(2)
}

function printUsage() {
  console.log([
    'Usage: PERF_IDENTITY_SECRET=... PERF_USERS_JSON=JSON node tools/perf_data_query.mjs',
    '',
    'Options:',
    '  --base-url=URL              Gateway base URL (default: http://127.0.0.1:8088)',
    '  --data-query-base-url=URL   Data-query base URL for Hikari metrics',
    '  --concurrency=20            Number of concurrent requests',
    '  --timeout-ms=30000          Per-request timeout',
    '  --query=TEXT                Query text',
    '  --identity-secret=SECRET    Signed identity secret',
    '  --users-json=JSON           Array of at least N authorized test users',
  ].join('\n'))
}
