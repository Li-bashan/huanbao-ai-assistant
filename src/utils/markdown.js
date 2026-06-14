import DOMPurify from 'dompurify'
import MarkdownIt from 'markdown-it'

const md = new MarkdownIt({
  html: false,
  breaks: true,
  linkify: true,
})

export function renderMarkdown(content = '') {
  const html = md.render(content || '')
  return DOMPurify.sanitize(html)
}
