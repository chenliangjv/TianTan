<template>
  <section class="model-info-panel" aria-label="模型实时信息">
    <header class="info-title"><div><span>RUNTIME TELEMETRY</span><strong>模型实时信息</strong></div><i></i></header>
    <div class="metrics"><div class="metric"><span class="label">当前可视节点数</span><strong class="value" ref="visiblenodesRef">0</strong><small>NODES</small></div><div class="metric"><span class="label">三角面片数</span><strong class="value" ref="trianglesRef">0</strong><small>TRIANGLES</small></div></div>
    <footer class="panel-status"><i></i><span>LIVE DATA STREAM</span></footer>
  </section>
</template>

<script setup>
import { ref } from 'vue'
const visiblenodesRef = ref(null); const trianglesRef = ref(null)
defineExpose({ visiblenodesRef, trianglesRef })
</script>

<style scoped>
.model-info-panel { width: 100%; height: 100%; box-sizing: border-box; display: flex; flex-direction: column; overflow: hidden; border: 1px solid rgba(85,214,230,.3); border-radius: 0; background: rgba(6,20,31,.96); box-shadow: -18px 0 42px rgba(0,0,0,.28), 0 0 24px rgba(85,214,230,.08); backdrop-filter: blur(16px); }.info-title { display: flex; align-items: center; justify-content: space-between; min-height: 64px; padding: 0 16px; border-bottom: 1px solid var(--ui-border); background: linear-gradient(90deg, rgba(85,214,230,.09), transparent 62%); }.info-title span { display: block; color: var(--ui-cyan); font: 9px Consolas, monospace; letter-spacing: 1.1px; }.info-title strong { display: block; margin-top: 4px; color: #e5f4f6; font-size: 14px; font-weight: 600; }.info-title i { width: 7px; height: 7px; border-radius: 50%; background: var(--ui-success); box-shadow: 0 0 10px var(--ui-success); }.metrics { display: grid; flex: 1; min-height: 0; grid-template-columns: 1fr; grid-template-rows: repeat(2, minmax(128px, 1fr)); align-content: center; padding: 12px; gap: 8px; }.metric { display: flex; min-width: 0; flex-direction: column; justify-content: center; padding: 18px 14px; border: 1px solid rgba(143,190,210,.14); background: linear-gradient(135deg, rgba(85,214,230,.075), rgba(143,190,210,.025)); }.metric .label { display: block; min-height: 28px; color: #8ca6b0; font-size: 11px; line-height: 1.35; }.metric .value { display: block; overflow: hidden; margin-top: 7px; color: var(--ui-cyan); font: 600 24px Consolas, monospace; text-overflow: ellipsis; white-space: nowrap; }.metric small { display: block; margin-top: 8px; color: #597682; font: 8px Consolas, monospace; letter-spacing: .8px; }.panel-status { display: flex; align-items: center; gap: 7px; min-height: 34px; padding: 0 15px; border-top: 1px solid rgba(143,190,210,.14); color: #66828e; font: 9px Consolas, monospace; letter-spacing: .85px; }.panel-status i { width: 5px; height: 5px; border-radius: 50%; background: var(--ui-success); box-shadow: 0 0 7px rgba(86,214,154,.8); }
@media (max-width: 560px) { .model-info-panel { width: 100%; } }
</style>
