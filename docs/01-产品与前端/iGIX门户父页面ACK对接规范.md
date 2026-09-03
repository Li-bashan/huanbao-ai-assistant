# iGIX 门户父页面 ACK 对接规范

更新时间：2026-09-03

当前对接状态：`FRONTEND_READY / HOST_PENDING`

本规范描述环宝 AI 智能助手 iframe 与 iGIX 门户父页面之间的流程动作通信。当前仓库只提供子页面动作桥接和 ACK 等待骨架，不包含 iGIX 门户父页面工程，因此不能宣称流程动作已经完成端到端闭环。

## 1. 对接边界

流程助手只请求门户打开已配置的表单或菜单，不绕过 iGIX 权限，也不自动提交业务表单。子页面收到 `SUCCESS` ACK 前，只能展示已请求打开、已发出或等待门户处理；ACK 只代表父页面已经按自身权限和业务规则处理了该动作。

当前 Gate 结论如下：

| 能力 | 状态 | 说明 |
| --- | --- | --- |
| 子页面动作发送、来源校验、5 秒超时 | `FRONTEND_READY` | 已在 `src/utils/actionBridge.js` 实现。 |
| 父页面 iframe 来源校验和实际执行 | `HOST_PENDING` | 当前仓库不含 iGIX 门户父页面工程，必须在真实门户中接入验证。 |
| 动作审计生命周期 | `PARTIAL` | 当前 Gateway 只有单行插入，尚无 ATTEMPT 生命周期和同 `actionId` 幂等更新。 |

## 2. 父页面接入前置条件

父页面正式接入前必须同时满足以下条件：

1. 父页面和子页面分别配置明确的允许 Origin。子页面构建时配置 `VITE_IGIX_PORTAL_ORIGIN`，不得把不确定的来源交给运行时猜测；生产通信不得使用通配符目标 Origin。
2. 父页面维护已注册 iframe 的引用和允许的子页面 Origin。不能仅凭消息里的 `payload` 或所谓的 portalOrigin 判断消息可信。
3. 父页面按自己的登录身份、租户、功能权限和 `action` 白名单校验动作。子页面传来的 `status`、`userId`、`fields` 都不能成为父页面授予权限的依据。
4. 父页面按 `actionId` 做幂等消费。同一个 `actionId` 重复到达时不得重复打开表单，应返回同一处理结果或明确的幂等结果。
5. 父页面只有在动作已经完成权限判断和门户侧处理后，才能返回 ACK。发送 ACK 不等于表单提交成功，当前协议也不包含自动提交能力。
6. Gateway 审计接口需要支持 ATTEMPT 到最终结果的生命周期更新，或者提供等价的幂等 upsert。当前唯一索引下重复插入同一个 `actionId` 会产生冲突，前端会捕获该错误，但不能据此宣称审计闭环。

## 3. 子页面发送协议

子页面通过已经解析的 `portalOrigin` 向父页面发送 `IGIX_AI_ACTION`：

```js
window.parent.postMessage(
  {
    type: 'IGIX_AI_ACTION',
    actionId: '550e8400-e29b-41d4-a716-446655440000',
    payload: {
      action: 'open_form',
      formCode: 'CGQSD',
      funcId: '9744034a-7fcc-4510-97fa-f563aecd26e6',
      actionId: '550e8400-e29b-41d4-a716-446655440000',
    },
  },
  portalOrigin,
)
```

### 3.1 顶层字段

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `type` | string | 是 | 固定为 `IGIX_AI_ACTION`。 |
| `actionId` | string | 是 | 子页面为本次动作生成的 UUID。 |
| `payload` | object | 是 | 经子页面清洗后的普通对象。 |

顶层 `actionId` 和 `payload.actionId` 必须相同。父页面应拒绝缺失、格式异常或两者不一致的消息。

### 3.2 payload 字段

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `action` | string | 是 | 当前支持 `open_form`、`open_menu`。 |
| `formCode` | string | 是 | iGIX 表单、菜单、台账或看板编码。 |
| `funcId` | string | 是 | iGIX 功能 ID。 |
| `actionId` | string | 是 | 与消息顶层 `actionId` 相同。 |
| `fields` | object | 否 | 当前试点默认不发送；重新启用前需要单独完成字段协议和权限验收。 |

父页面应把 `action`、`formCode`、`funcId` 作为组合键做白名单校验，并拒绝未知动作或未配置入口。流程配置中的 `verified` 只代表子页面配置状态，不代表当前用户拥有门户权限。

## 4. 父页面安全接收示例

以下示例中的 `registeredIframe` 和 `assistantOrigin` 必须来自父页面自己的可信配置或已注册 iframe，不得从收到的消息中推导：

```js
function handleAssistantAction(event) {
  if (event.origin !== assistantOrigin) return
  if (event.source !== registeredIframe.contentWindow) return

  const data = event.data
  if (!data || data.type !== 'IGIX_AI_ACTION') return
  if (!data.actionId || data.actionId !== data.payload?.actionId) return

  const payload = data.payload
  if (!isAllowedAction(payload.action, payload.formCode, payload.funcId)) {
    sendActionAck(event, 'FAILED', '动作不在门户允许范围内')
    return
  }
  if (alreadyConsumed(data.actionId)) {
    sendActionAck(event, getSavedStatus(data.actionId), getSavedError(data.actionId))
    return
  }

  markActionAttempt(data.actionId)
  executeWithCurrentPortalUserPermission(payload)
    .then(() => {
      saveActionResult(data.actionId, 'SUCCESS', '')
      sendActionAck(event, 'SUCCESS', '')
    })
    .catch((error) => {
      const message = normalizeErrorMessage(error)
      saveActionResult(data.actionId, 'FAILED', message)
      sendActionAck(event, 'FAILED', message)
    })
}

function sendActionAck(event, status, errorMessage) {
  event.source.postMessage(
    {
      type: 'IGIX_AI_ACTION_ACK',
      actionId: event.data.actionId,
      status,
      timestamp: new Date().toISOString(),
      errorMessage: errorMessage || '',
    },
    event.origin,
  )
}
```

父页面应把 `message` 监听器注册在可控生命周期内，并在 iframe 销毁或切换时清理对应的引用和幂等缓存。ACK 的目标 Origin 必须使用已经校验通过的 `event.origin`，不能使用不受限的目标 Origin。

## 5. ACK 回执协议

父页面向原始 iframe 返回：

```js
{
  type: 'IGIX_AI_ACTION_ACK',
  actionId: '550e8400-e29b-41d4-a716-446655440000',
  status: 'SUCCESS',
  timestamp: '2026-09-03T10:00:00.000Z',
  errorMessage: '',
}
```

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `type` | string | 是 | 固定为 `IGIX_AI_ACTION_ACK`。 |
| `actionId` | string | 是 | 必须与原动作完全相同。 |
| `status` | string | 是 | `SUCCESS`、`FAILED` 或 `TIMEOUT`。 |
| `timestamp` | string | 是 | 父页面生成的 ISO 8601 时间。 |
| `errorMessage` | string | 是 | 成功时为空字符串，失败或超时时提供可展示的短原因。 |

状态语义：

- `SUCCESS`：父页面已完成权限判断和对应的门户侧动作处理，子页面可以展示已确认。
- `FAILED`：动作参数、权限校验或门户处理失败，父页面应提供可诊断但不泄露敏感信息的原因。
- `TIMEOUT`：子页面在 5000ms 内没有收到合法 ACK 时本地生成的终态。父页面通常不需要主动发送该状态；如果发送，也必须满足同样的来源和 `actionId` 校验。

子页面只接受同时满足以下条件的回执：

```js
event.origin === portalOrigin &&
event.source === window.parent &&
event.data?.type === 'IGIX_AI_ACTION_ACK' &&
event.data?.actionId === actionId
```

不满足条件的消息必须忽略，不能改变卡片状态。子页面在最终状态后会清理 `message` listener 和 5000ms 定时器。

## 6. 两阶段握手与审计边界

前端骨架按以下顺序工作：

1. 生成 UUID `actionId`，清洗 payload，并尝试向 Gateway 写入 `result=ATTEMPT` 的预审计。
2. 使用明确的 `portalOrigin` 发出动作；收到合法 ACK 后记账 `SUCCESS` 或 `FAILED`，无合法 ACK 则在 5000ms 记账 `TIMEOUT`。

审计请求是防御性 best-effort：Gateway 返回 404、500、网络错误或同 `actionId` 唯一键冲突时只记录 `console.warn`，不阻断动作通信，不让页面抛错崩溃。审计写入成功也不等于业务动作成功，三者必须分开理解：消息已发出、门户已确认、审计已落库。

当前 `workflow_audit_api=PARTIAL`，因此 ATTEMPT 和最终结果在现有后端上可能因唯一键冲突无法完成同一行更新。该限制必须由 Gateway 幂等更新能力和真实数据库验证关闭后，才能把审计标记为完成。

## 7. 验收清单

- [ ] 生产构建配置了正确的 `VITE_IGIX_PORTAL_ORIGIN`，子页面和父页面双向 Origin 白名单一致。
- [ ] 父页面严格校验 `event.origin`、`event.source`、消息类型和两个位置的 `actionId`。
- [ ] 同一 `actionId` 重复投递不会重复打开表单。
- [ ] 有权限且执行成功时返回 `SUCCESS`，失败时返回 `FAILED`，不返回虚假的成功。
- [ ] 不回 ACK 时子页面 5000ms 后进入 `TIMEOUT`，页面无未捕获异常。
- [ ] ACK 前卡片只出现已发出、已请求打开、等待门户处理等中间态。
- [ ] Gateway 能以同一 `actionId` 幂等更新 ATTEMPT 和最终结果。
- [ ] 在真实 iGIX iframe 中完成一次成功、一次无权限失败、一次超时和一次重复消息验收。
