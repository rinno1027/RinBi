<template>
  <section v-if="views.length" class="chart-block">
    <article
      v-for="(view, index) in views"
      :key="`${view.title}-${index}`"
      class="chart-card"
    >
      <header class="chart-head">
        <div class="chart-title">
          <span class="chart-dot"></span>
          <span>{{ view.title }}</span>
        </div>
        <el-tag size="small" effect="plain" type="info">{{ view.typeLabel }}</el-tag>
      </header>
      <ChartCanvas :ref="(el) => setCanvasRef(el, index)" :option="view.option" />
    </article>
  </section>
</template>

<script setup>
import { computed } from 'vue'
import ChartCanvas from './ChartCanvas.vue'
import { formatCell } from '../utils/format'

const props = defineProps({
  charts: {
    type: Array,
    default: () => []
  },
  table: {
    type: Object,
    default: null
  }
})

const TYPE_LABELS = {
  bar: '柱状图',
  line: '折线图',
  pie: '饼图',
  scatter: '散点图'
}
const PALETTE = ['#2f6fed', '#1f9f8f', '#f0a23a', '#8b6ff0', '#e05d7a']
// 图表只取前 200 行，避免极端结果集把浏览器画卡。
const MAX_POINTS = 200

const views = computed(() => {
  const table = props.table
  if (!table || !Array.isArray(table.columns) || !Array.isArray(table.rows)) {
    return []
  }
  const rows = table.rows.slice(0, MAX_POINTS).filter((row) => Array.isArray(row))
  if (rows.length < 2) {
    return []
  }
  return props.charts
    .map((chart) => buildView(chart, table.columns, rows))
    .filter(Boolean)
})

const canvasRefs = []

function setCanvasRef(component, index) {
  if (component) {
    canvasRefs[index] = component
  } else {
    delete canvasRefs[index]
  }
}

// PDF 导出时按当前渲染结果导出图表位图，保证与页面看到的图表一致。
function getChartImages() {
  return views.value
    .map((view, index) => {
      const canvas = canvasRefs[index]
      const image =
        canvas && typeof canvas.getChartImage === 'function'
          ? canvas.getChartImage()
          : null
      if (!image || !image.dataUrl) {
        return null
      }
      return {
        title: view.title,
        typeLabel: view.typeLabel,
        dataUrl: image.dataUrl,
        width: image.width,
        height: image.height
      }
    })
    .filter(Boolean)
}

defineExpose({ getChartImages })

function buildView(chart, columns, rows) {
  if (!chart || typeof chart !== 'object') {
    return null
  }
  const type = String(chart.type || '').toLowerCase()
  if (!TYPE_LABELS[type]) {
    return null
  }
  const xIndex = columnIndex(columns, chart.xField)
  const yFields = Array.isArray(chart.yFields) ? chart.yFields : []
  const series = yFields
    .map((field) => ({ field, index: columnIndex(columns, field) }))
    .filter((item) => item.index >= 0)
  if (xIndex < 0 || !series.length) {
    return null
  }
  // 饼图只取第一个数值列，后端也已做同样约束。
  const activeSeries = type === 'pie' ? series.slice(0, 1) : series
  const categories = rows.map((row) => {
    const value = formatCell(row[xIndex])
    return value === '—' ? '（空）' : value
  })
  const option =
    type === 'pie'
      ? buildPieOption(categories, rows, activeSeries, chart.unit)
      : buildAxisOption(type, categories, rows, activeSeries, chart.unit)

  return {
    title: chart.title || `${chart.xField || '数据'} 图表`,
    typeLabel: TYPE_LABELS[type],
    option
  }
}

function columnIndex(columns, field) {
  const target = String(field || '').trim().toLowerCase()
  if (!target) {
    return -1
  }
  return columns.findIndex(
    (column) => String(column === null || column === undefined ? '' : column)
      .trim()
      .toLowerCase() === target
  )
}

function toNumber(value) {
  if (value === null || value === undefined) {
    return null
  }
  let text = String(value).trim()
  if (!text || text === '-' || text === '—') {
    return null
  }
  text = text.replace(/[,，\s]/g, '')
  const number = Number(text)
  return Number.isFinite(number) ? number : null
}

function formatAxisValue(value) {
  const number = Number(value)
  if (!Number.isFinite(number)) {
    return ''
  }
  const abs = Math.abs(number)
  if (abs >= 100000000) {
    return `${trimZero((number / 100000000).toFixed(abs >= 1000000000 ? 0 : 1))}亿`
  }
  if (abs >= 10000) {
    return `${trimZero((number / 10000).toFixed(abs >= 100000 ? 0 : 1))}万`
  }
  return String(number)
}

function trimZero(value) {
  return value.replace(/\.0$/, '')
}

function formatTooltipValue(value, unit) {
  const number = Number(value)
  if (!Number.isFinite(number)) {
    return '—'
  }
  const text = number.toLocaleString('zh-CN', { maximumFractionDigits: 2 })
  return unit ? `${text} ${unit}` : text
}

function buildAxisOption(type, categories, rows, series, unit) {
  const isScatter = type === 'scatter'
  return {
    color: PALETTE,
    animationDuration: 420,
    grid: {
      left: 10,
      right: 18,
      top: series.length > 1 ? 38 : 16,
      bottom: 6,
      containLabel: true
    },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: isScatter ? 'cross' : 'shadow' },
      valueFormatter: (value) => formatTooltipValue(value, unit)
    },
    ...(series.length > 1
      ? {
          legend: {
            top: 0,
            right: 0,
            itemWidth: 10,
            itemHeight: 10,
            textStyle: { color: '#64748b', fontSize: 11 }
          }
        }
      : {}),
    xAxis: {
      type: 'category',
      data: categories,
      boundaryGap: !isScatter,
      axisLabel: { color: '#7b8aa3', fontSize: 11, hideOverlap: true },
      axisLine: { lineStyle: { color: '#dbe3ee' } },
      axisTick: { show: false }
    },
    yAxis: {
      type: 'value',
      name: unit ? `单位：${unit}` : '',
      nameTextStyle: { color: '#96a5ba', fontSize: 11, padding: [0, 0, 6, 0] },
      axisLabel: { color: '#7b8aa3', fontSize: 11, formatter: formatAxisValue },
      splitLine: { lineStyle: { color: '#eef2f8' } }
    },
    series: series.map((item) => {
      const values = rows.map((row) => toNumber(row[item.index]))
      if (type === 'bar') {
        return {
          name: item.field,
          type: 'bar',
          barMaxWidth: 40,
          itemStyle: { borderRadius: [5, 5, 0, 0] },
          data: values
        }
      }
      if (type === 'line') {
        return {
          name: item.field,
          type: 'line',
          smooth: true,
          symbol: 'circle',
          symbolSize: 5,
          connectNulls: true,
          lineStyle: { width: 2.2 },
          ...(series.length === 1 ? { areaStyle: { opacity: 0.08 } } : {}),
          data: values
        }
      }
      return {
        name: item.field,
        type: 'scatter',
        symbolSize: 8,
        data: values
      }
    })
  }
}

function buildPieOption(categories, rows, series, unit) {
  const item = series[0]
  const data = rows
    .map((row, index) => ({
      name: categories[index],
      value: toNumber(row[item.index])
    }))
    .filter((entry) => entry.value !== null)
  return {
    color: PALETTE,
    animationDuration: 420,
    tooltip: {
      trigger: 'item',
      valueFormatter: (value) => formatTooltipValue(value, unit)
    },
    legend: {
      type: 'scroll',
      bottom: 0,
      itemWidth: 10,
      itemHeight: 10,
      textStyle: { color: '#64748b', fontSize: 11 }
    },
    series: [
      {
        name: item.field,
        type: 'pie',
        radius: ['38%', '64%'],
        center: ['50%', '45%'],
        minAngle: 2,
        avoidLabelOverlap: true,
        itemStyle: { borderColor: '#fff', borderWidth: 2 },
        label: { color: '#4b5f7d', fontSize: 11, formatter: '{b}\n{d}%' },
        labelLine: { length: 10, length2: 8 },
        data
      }
    ]
  }
}

</script>
