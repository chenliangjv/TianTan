<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { init, use } from 'echarts/core'
import { BarChart, PieChart } from 'echarts/charts'
import { AriaComponent, GridComponent, LegendComponent, TitleComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

use([BarChart, PieChart, AriaComponent, GridComponent, LegendComponent, TitleComponent, TooltipComponent, CanvasRenderer])
const props = defineProps({ option: { type: Object, required: true }, label: { type: String, required: true } })
const emit = defineEmits(['select'])
const element = ref(null)
let chart, observer, frame
const resize = () => {
  cancelAnimationFrame(frame)
  frame = requestAnimationFrame(() => chart?.resize())
}
onMounted(() => {
  chart = init(element.value, null, { renderer: 'canvas' })
  chart.setOption(props.option)
  chart.on('click', event => emit('select', event))
  observer = new ResizeObserver(resize)
  observer.observe(element.value)
})
watch(() => props.option, option => chart?.setOption(option, { notMerge: true }))
onBeforeUnmount(() => {
  observer?.disconnect()
  cancelAnimationFrame(frame)
  chart?.dispose()
  chart = null
})
</script>

<template><div ref="element" class="statistics-chart" role="img" :aria-label="label" /></template>
<style scoped>.statistics-chart { width: 100%; min-width: 0; height: 100%; min-height: 0; }</style>
