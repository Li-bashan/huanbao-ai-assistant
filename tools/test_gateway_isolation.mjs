const baseUrl = String(process.env.GATEWAY_BASE_URL || 'http://localhost:8088').replace(/\/$/, '')
const identitySecret = String(process.env.GATEWAY_IDENTITY_SECRET || '').trim()
const userCount = Number.parseInt(process.env.GATEWAY_TEST_USERS || '10', 10)

if (!identitySecret) {
  console.error('GATEWAY_IDENTITY_SECRET is required; refusing to run an unauthenticated isolation test.')
  process.exit(2)
}

const hmac = async value => {
  const key = await crypto.subtle.importKey(
    'raw', new TextEncoder().encode(identitySecret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign'],
  )
  const bytes = new Uint8Array(await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(value)))
  return Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join('')
}

const signedHeaders = async user => {
  const payload = Buffer.from(JSON.stringify(user)).toString('base64url')
  const timestamp = String(Math.floor(Date.now() / 1000))
  return {
    'Content-Type': 'application/json',
    Accept: 'text/event-stream',
    'X-Portal-Identity': payload,
    'X-Portal-Identity-Timestamp': timestamp,
    'X-Portal-Identity-Signature': await hmac(`${timestamp}.${payload}`),
  }
}

const readSse = async response => {
  const reader = response.body?.getReader()
  if (!reader) throw new Error('Gateway returned no SSE body')
  const decoder = new TextDecoder()
  let buffer = ''
  const events = []
  const consume = chunk => {
    buffer += chunk
    const blocks = buffer.split(/\r?\n\r?\n/)
    buffer = blocks.pop() || ''
    for (const block of blocks) {
      const event = block.match(/^event:\s*(.+)$/m)?.[1] || 'message'
      const raw = block.match(/^data:\s*(.+)$/m)?.[1] || '{}'
      let data = raw
      try { data = JSON.parse(raw) } catch { /* keep text */ }
      events.push({ event, data })
    }
  }
  while (true) {
    const { value, done } = await reader.read()
    if (done) break
    consume(decoder.decode(value, { stream: true }))
  }
  consume(decoder.decode())
  if (buffer.trim()) consume(`${buffer}\n\n`)
  return events
}

const send = async (user, clientConversationId, query) => {
  const response = await fetch(`${baseUrl}/api/ai/data-query/chat`, {
    method: 'POST',
    headers: await signedHeaders(user),
    body: JSON.stringify({
      query,
      conversationId: clientConversationId,
      requestId: `client-test-${user.userId}-${Date.now()}`,
      untrustedClientContext: user,
    }),
  })
  const events = response.ok ? await readSse(response) : []
  if (!response.ok) throw new Error(`${user.userId} ${response.status}`)
  const completed = events.find(item => item.event === 'completed')?.data || {}
  const error = events.find(item => item.event === 'error')?.data
  if (error) throw new Error(`${user.userId} Gateway error: ${JSON.stringify(error)}`)
  return {
    requestId: response.headers.get('x-request-id'),
    clientConversationId: completed.conversationId || clientConversationId,
    events,
  }
}

const users = Array.from({ length: userCount }, (_, index) => ({
  userId: `isolation-test-${index + 1}`,
  userCode: `IT${index + 1}`,
  userName: `隔离测试${index + 1}`,
  orgCode: 'TEST',
  orgName: '隔离测试组织',
  tenantId: 'isolation-test-tenant',
}))

const firstRound = await Promise.all(users.map(user => send(
  user, `hb-isolation-${user.userId}`, '查询今年项目公司发电量排名',
)))
const secondRound = await Promise.all(users.map((user, index) => send(
  user, `hb-isolation-${user.userId}`, `只看前${index + 3}`,
)))

const all = [...firstRound, ...secondRound]
const requestIds = new Set(all.map(item => item.requestId).filter(Boolean))
const clientConversationIds = new Set(firstRound.map(item => item.clientConversationId))
if (requestIds.size !== all.length) throw new Error(`requestId collision: ${requestIds.size}/${all.length}`)
if (clientConversationIds.size !== userCount) throw new Error(`conversation collision: ${clientConversationIds.size}/${userCount}`)
if (all.some(item => JSON.stringify(item.events).includes('dify_conversation_id'))) {
  throw new Error('Gateway leaked dify_conversation_id in SSE')
}

console.log(JSON.stringify({
  baseUrl,
  userCount,
  rounds: 2,
  requestIds: requestIds.size,
  independentClientConversations: clientConversationIds.size,
  note: 'Dify user/hash and Dify conversation uniqueness must be checked from Gateway mapping SQL; they are intentionally not returned to the browser.',
}, null, 2));
