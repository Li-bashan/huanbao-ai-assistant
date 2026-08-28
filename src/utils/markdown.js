import DOMPurify from 'dompurify'
import MarkdownIt from 'markdown-it'

const md = new MarkdownIt({
  html: false,
  breaks: true,
  linkify: true,
  typographer: false,
})

function hideThinkContent(content = '') {
  return String(content || '')
    .replace(/<think>[\s\S]*?<\/think>/gi, '')
    .replace(/<think>[\s\S]*$/gi, '')
    .replace(/<\/think>/gi, '')
}

export function renderMarkdown(content = '') {
  const html = md.render(hideThinkContent(content))
  return DOMPurify.sanitize(html)
}
