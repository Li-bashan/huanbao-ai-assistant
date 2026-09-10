function formatDateTime(date = new Date()) {
  const pad = (value) => String(value).padStart(2, '0')
  return {
    display: `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(
      date.getHours(),
    )}:${pad(date.getMinutes())}`,
    file: `${date.getFullYear()}${pad(date.getMonth() + 1)}${pad(date.getDate())}_${pad(
      date.getHours(),
    )}${pad(date.getMinutes())}`,
  }
}

function sanitizeFileName(value = '') {
  return String(value || '新对话')
    .replace(/[\\/:*?"<>|]/g, '_')
    .replace(/\s+/g, '')
    .slice(0, 28)
}

function escapeHtml(value = '') {
  return String(value || '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

function buildWorkflowText(workflowCard) {
  if (!workflowCard) return ''

  const lines = [
    '流程动作：',
    `已识别事项：${workflowCard.workflowName || ''}`,
    workflowCard.description ? `说明：${workflowCard.description}` : '',
  ].filter(Boolean)

  if (workflowCard.actions?.length) {
    lines.push('可执行操作：')
    workflowCard.actions.forEach((action, index) => {
      lines.push(`${index + 1}. ${action}`)
    })
  }

  if (workflowCard.requiredFields?.length) {
    lines.push('需要准备：')
    workflowCard.requiredFields.forEach((field) => {
      lines.push(`- ${field}`)
    })
  }

  return lines.join('\n')
}

function buildPolicyEvidenceText(policyEvidence = []) {
  if (!policyEvidence.length) return ''

  return [
    '制度原文：',
    ...policyEvidence.map((source, index) => {
      const title = String(index + 1) + '. ' + (source.documentName || '制度知识库')
      return title + '\n' + (source.content || '')
    }),
  ].join('\n\n')
}

function buildSourcesText(sources = []) {
  if (!sources.length) return ''

  return [
    '引用来源：',
    ...sources.map((source, index) => {
      const dataset = source.datasetName ? `（${source.datasetName}）` : ''
      return `${index + 1}. ${source.documentName || '未命名文档'}${dataset}`
    }),
  ].join('\n')
}

function downloadBlob(content, fileName, type) {
  const blob = new Blob([content], { type })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = fileName
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(url)
}

export function getPlainMessageText(message, modeLabel = '') {
  const parts = [message.content || '']
  const workflowText = buildWorkflowText(message.workflowCard)
  const sourcesText = buildSourcesText(message.sources || [])
  const policyEvidenceText = buildPolicyEvidenceText(message.policyEvidence || [])

  if (workflowText) parts.push(workflowText)
  if (sourcesText) parts.push(sourcesText)
  if (policyEvidenceText) parts.push(policyEvidenceText)
  if (modeLabel) parts.unshift(`模式：${modeLabel}`)

  return parts.filter(Boolean).join('\n\n')
}

export async function copyText(text) {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
      return
    }
  } catch {
    // Embedded portal frames may expose the API but reject its permission request.
  }

  const textarea = document.createElement('textarea')
  textarea.value = text
  textarea.setAttribute('readonly', '')
  textarea.style.position = 'fixed'
  textarea.style.opacity = '0'
  document.body.appendChild(textarea)
  textarea.select()
  try {
    if (!document.execCommand('copy')) throw new Error('copy-failed')
  } finally {
    textarea.remove()
  }
}

export function exportMarkdown(message, modeLabel, conversationTitle) {
  const now = formatDateTime()
  const sourcesText = buildSourcesText(message.sources || [])
  const policyEvidenceText = buildPolicyEvidenceText(message.policyEvidence || [])
  const workflowText = buildWorkflowText(message.workflowCard)
  const content = [
    '# 环宝助手回复',
    '',
    `模式：${modeLabel}`,
    `时间：${now.display}`,
    '',
    '## 回复内容',
    '',
    message.content || '',
    policyEvidenceText ? '\n## 制度原文\n\n' + policyEvidenceText.replace('制度原文：\n', '') : '',
    sourcesText ? `\n## 引用来源\n\n${sourcesText.replace('引用来源：\n', '')}` : '',
    workflowText ? `\n## 流程动作\n\n${workflowText.replace('流程动作：\n', '')}` : '',
  ]
    .filter((item) => item !== '')
    .join('\n')

  downloadBlob(
    content,
    `环宝助手_${sanitizeFileName(modeLabel)}_${sanitizeFileName(conversationTitle)}_${now.file}.md`,
    'text/markdown;charset=utf-8',
  )
}

export function exportWordHtml(message, modeLabel, conversationTitle) {
  const now = formatDateTime()
  const sources = message.sources || []
  const policyEvidence = message.policyEvidence || []
  const workflowCard = message.workflowCard
  const bodyContent = escapeHtml(message.content || '').replace(/\n/g, '<br />')

  const sourcesHtml = sources.length
    ? `<h2>引用来源</h2><ul>${sources
        .map(
          (source) =>
            `<li>${escapeHtml(source.documentName || '未命名文档')}${
              source.datasetName ? `（${escapeHtml(source.datasetName)}）` : ''
            }</li>`,
        )
        .join('')}</ul>`
    : ''

  const policyEvidenceHtml = policyEvidence.length
    ? '<h2>制度原文</h2>' + policyEvidence
        .map(
          (source, index) =>
            '<h3>' +
            String(index + 1) +
            '. ' +
            escapeHtml(source.documentName || '制度知识库') +
            '</h3><pre>' +
            escapeHtml(source.content || '') +
            '</pre>',
        )
        .join('')
    : ''

  const workflowHtml = workflowCard
    ? `<h2>流程动作</h2>
      <p><strong>已识别事项：</strong>${escapeHtml(workflowCard.workflowName || '')}</p>
      <p><strong>说明：</strong>${escapeHtml(workflowCard.description || '')}</p>
      ${
        workflowCard.actions?.length
          ? `<p><strong>可执行操作：</strong></p><ul>${workflowCard.actions
              .map((action) => `<li>${escapeHtml(action)}</li>`)
              .join('')}</ul>`
          : ''
      }
      ${
        workflowCard.requiredFields?.length
          ? `<p><strong>需要准备：</strong></p><ul>${workflowCard.requiredFields
              .map((field) => `<li>${escapeHtml(field)}</li>`)
              .join('')}</ul>`
          : ''
      }`
    : ''

  const html = `<!doctype html>
<html>
<head>
  <meta charset="utf-8" />
  <title>环宝助手回复</title>
  <style>
    body { font-family: "Microsoft YaHei", SimSun, sans-serif; line-height: 1.7; color: #1f2937; }
    h1 { font-size: 22px; }
    h2 { margin-top: 22px; font-size: 16px; }
    .meta { color: #64748b; font-size: 13px; }
  </style>
</head>
<body>
  <h1>环宝助手回复</h1>
  <p class="meta">模式：${escapeHtml(modeLabel)}<br />时间：${escapeHtml(now.display)}</p>
  <h2>回复内容</h2>
  <p>${bodyContent}</p>
  ${sourcesHtml}
  ${policyEvidenceHtml}
  ${workflowHtml}
</body>
</html>`

  downloadBlob(
    html,
    `环宝助手_${sanitizeFileName(modeLabel)}_${sanitizeFileName(conversationTitle)}_${now.file}.doc`,
    'application/msword;charset=utf-8',
  )
}
