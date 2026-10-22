/**
 * 回答导出 PDF：仅包含模型回答正文与图表。
 * 思考过程、执行 SQL、结果明细等都不参与导出。
 * html2pdf.js 体积较大且只在点击导出时使用，因此采用动态 import 按需加载。
 */

const IMAGE_LOAD_TIMEOUT = 8000

function pad(value) {
  return String(value).padStart(2, '0')
}

function buildFilename(now = new Date()) {
  const date = `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}`
  const time = `${pad(now.getHours())}${pad(now.getMinutes())}${pad(now.getSeconds())}`
  return `RinBI-回答-${date}-${time}.pdf`
}

function loadImage(source) {
  return new Promise((resolve) => {
    if (!source) {
      resolve(false)
      return
    }
    const image = new Image()
    let settled = false
    let timer = null
    const finish = (loaded) => {
      if (settled) {
        return
      }
      settled = true
      if (timer) {
        window.clearTimeout(timer)
      }
      resolve(loaded)
    }
    timer = window.setTimeout(() => finish(false), IMAGE_LOAD_TIMEOUT)
    image.onload = () => finish(true)
    image.onerror = () => finish(false)
    image.src = source
  })
}

function buildChartCard(chart) {
  const card = document.createElement('article')
  card.className = 'chart-card'

  const head = document.createElement('header')
  head.className = 'chart-head'

  const title = document.createElement('div')
  title.className = 'chart-title'
  const dot = document.createElement('span')
  dot.className = 'chart-dot'
  const titleText = document.createElement('span')
  titleText.textContent = chart.title || '图表'
  title.append(dot, titleText)

  head.append(title)
  if (chart.typeLabel) {
    const type = document.createElement('span')
    type.className = 'pdf-chart-type'
    type.textContent = chart.typeLabel
    head.append(type)
  }

  const image = document.createElement('img')
  image.className = 'pdf-chart-image'
  image.alt = chart.title || '图表'
  // 写入宽高属性，浏览器可在图片解码前按比例占位，html2pdf 分页计算才准确。
  if (Number.isFinite(chart.width) && chart.width > 0) {
    image.width = Math.round(chart.width)
  }
  if (Number.isFinite(chart.height) && chart.height > 0) {
    image.height = Math.round(chart.height)
  }
  image.src = chart.dataUrl

  card.append(head, image)
  return card
}

async function buildExportDocument(answerHtml, charts) {
  const root = document.createElement('div')
  root.className = 'pdf-export-document'

  if (answerHtml.trim()) {
    const answer = document.createElement('div')
    answer.className = 'markdown-body'
    // answerHtml 已由 renderMarkdown 做过 DOMPurify 清洗。
    answer.innerHTML = answerHtml
    root.appendChild(answer)
  }

  // 图片加载完成后再交给 html2pdf 克隆，保证分页计算能拿到真实高度。
  const loaded = await Promise.all(charts.map((chart) => loadImage(chart.dataUrl)))
  const available = charts.filter((chart, index) => loaded[index])

  if (available.length) {
    const block = document.createElement('section')
    block.className = 'chart-block'
    available.forEach((chart) => block.appendChild(buildChartCard(chart)))
    root.appendChild(block)
  }

  return root
}

/**
 * 导出回答正文和图表为 PDF。
 *
 * @param {Object} payload
 * @param {string} payload.answerHtml 已渲染并清洗过的回答 HTML
 * @param {Array<{title: string, typeLabel: string, dataUrl: string, width: number, height: number}>} payload.charts
 *        由 ChartPanel 提供的图表截图（含原始画布尺寸）
 * @returns {Promise<void>}
 */
export async function exportAnswerPdf({ answerHtml = '', charts = [] } = {}) {
  const html = String(answerHtml || '')
  const chartList = (Array.isArray(charts) ? charts : []).filter(
    (chart) => chart && typeof chart.dataUrl === 'string' && chart.dataUrl
  )
  if (!html.trim() && !chartList.length) {
    throw new Error('没有可导出的回答内容')
  }

  const html2pdfModule = await import('html2pdf.js')
  const html2pdf = html2pdfModule.default || html2pdfModule
  if (typeof html2pdf !== 'function') {
    throw new Error('PDF 导出组件加载失败')
  }

  const source = await buildExportDocument(html, chartList)

  await html2pdf()
    .set({
      margin: [10, 10, 12, 10],
      filename: buildFilename(),
      image: { type: 'jpeg', quality: 0.98 },
      html2canvas: {
        scale: 2,
        useCORS: true,
        backgroundColor: '#ffffff',
        logging: false,
        scrollX: 0,
        scrollY: 0
      },
      jsPDF: { unit: 'mm', format: 'a4', orientation: 'portrait' },
      pagebreak: {
        mode: ['css', 'legacy'],
        avoid: [
          '.chart-card',
          '.markdown-body pre',
          '.markdown-body table',
          '.markdown-body blockquote'
        ]
      }
    })
    .from(source)
    .save()
}
