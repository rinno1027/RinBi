import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'

const markdown = new MarkdownIt({
  html: true,
  linkify: true,
  breaks: true,
  typographer: false
})

// 统一处理 Markdown 与原始 HTML 中的链接，避免 target 被清洗掉或缺少 rel
DOMPurify.addHook('afterSanitizeAttributes', (node) => {
  if (node.tagName === 'A' && node.hasAttribute('href')) {
    node.setAttribute('target', '_blank')
    node.setAttribute('rel', 'noopener noreferrer')
  }
})

// chart 代码块是给前端图表组件的结构化配置，不属于正文。
// 流式阶段代码块可能尚未闭合，因此这里同时处理“已闭合”和“直到文本末尾”两种情况。
const CHART_BLOCK_PATTERN = /```chart(?![\w-])[ \t]*\r?\n?[\s\S]*?(?:```|$)/gi

export function stripChartBlocks(source) {
  if (!source) {
    return ''
  }
  return String(source).replace(CHART_BLOCK_PATTERN, '')
}

/**
 * 将后端返回的 Markdown 渲染为可安全插入 DOM 的 HTML。
 * 后端 fallbackMarkdown 会在单元格中使用 <br>，因此开启 html 后必须经过 DOMPurify。
 * chart 代码块会被剥离，图表由 ChartPanel 根据 ChatResponse.charts 渲染。
 */
export function renderMarkdown(source) {
  const text = stripChartBlocks(source)
  if (!text.trim()) {
    return ''
  }
  return DOMPurify.sanitize(markdown.render(text), {
    USE_PROFILES: { html: true }
  })
}
