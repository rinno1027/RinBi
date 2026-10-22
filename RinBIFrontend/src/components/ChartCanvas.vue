<template>
  <div ref="canvasEl" class="chart-canvas"></div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import { BarChart, LineChart, PieChart, ScatterChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

// 按需注册，避免把整个 ECharts 打进首屏包。
echarts.use([
  BarChart,
  LineChart,
  PieChart,
  ScatterChart,
  GridComponent,
  LegendComponent,
  TooltipComponent,
  CanvasRenderer
])

const props = defineProps({
  option: {
    type: Object,
    required: true
  }
})

const canvasEl = ref(null)

let chart = null
let resizeObserver = null
let resizeFrame = null

function render() {
  if (!canvasEl.value || !props.option) {
    return
  }
  if (!chart) {
    chart = echarts.init(canvasEl.value)
  }
  // notMerge=true：切换图表类型或数据时丢弃旧配置，避免残留 series。
  chart.setOption(props.option, true)
}

function scheduleResize() {
  if (resizeFrame !== null) {
    return
  }
  resizeFrame = window.requestAnimationFrame(() => {
    resizeFrame = null
    if (chart) {
      chart.resize()
    }
  })
}

// 供 PDF 导出使用：导出高分辨率位图并附带原始尺寸，避免截取 DOM 时图表被裁切，
// 同时让 PDF 分页计算在图片加载前就能拿到正确高度。
function getChartImage() {
  if (!chart) {
    return null
  }
  // 去掉悬浮提示，避免导出时把 tooltip 一起画进图片。
  chart.dispatchAction({ type: 'hideTip' })
  return {
    dataUrl: chart.getDataURL({
      type: 'png',
      pixelRatio: 2,
      backgroundColor: '#ffffff'
    }),
    width: chart.getWidth(),
    height: chart.getHeight()
  }
}

onMounted(() => {
  render()
  if (typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(scheduleResize)
    resizeObserver.observe(canvasEl.value)
  } else {
    window.addEventListener('resize', scheduleResize)
  }
})

watch(() => props.option, render, { deep: true })

defineExpose({ getChartImage })

onBeforeUnmount(() => {
  if (resizeObserver) {
    resizeObserver.disconnect()
    resizeObserver = null
  } else {
    window.removeEventListener('resize', scheduleResize)
  }
  if (resizeFrame !== null) {
    window.cancelAnimationFrame(resizeFrame)
    resizeFrame = null
  }
  if (chart) {
    chart.dispose()
    chart = null
  }
})
</script>
