<template>
  <section class="pointcloud-panel status-panel" aria-label="点云实时信息">
    <header class="panel-header">
      <div>
        <span class="panel-eyebrow">RUNTIME TELEMETRY</span>
        <strong>{{ selectedLabel }}</strong>
      </div>
      <i class="panel-live-dot" :class="{ 'is-error': loadError }"></i>
    </header>

    <div class="metrics">
      <div class="metric"><span class="label">数据格式</span><strong class="value">POTREE 2.0</strong><small>FORMAT</small></div>
      <div class="metric"><span class="label">点数量</span><strong class="value">{{ formatPoints(datasetPointCount) }}</strong><small>POINTS</small></div>
      <div class="metric"><span class="label">可见点</span><strong class="value">{{ formatPoints(visiblePointCount) }}</strong><small>VISIBLE</small></div>
      <div class="metric"><span class="label">节点载入</span><strong class="value">{{ pointCloud ? formatProgress(loadingProgress) : '-' }}</strong><small>STREAMING</small></div>
    </div>

    <footer class="panel-status"><i :class="{ 'is-error': loadError }"></i><span>{{ loadError ? 'POINT CLOUD LOAD ERROR' : 'LIVE DATA STREAM' }}</span></footer>
  </section>
</template>

<script setup>
defineProps({
  selectedLabel: { type: String, default: '未选择数据集' },
  loadError: { type: String, default: '' },
  datasetPointCount: { type: Number, default: 0 },
  visiblePointCount: { type: Number, default: 0 },
  loadingProgress: { type: Number, default: 0 },
  pointCloud: { type: Object, default: null },
});

const formatPoints = value => Number(value || 0).toLocaleString('zh-CN');
const formatProgress = value => `${Math.min(100, Math.max(0, Math.round(Number(value || 0) * 100)))}%`;
</script>

<style scoped>
.pointcloud-panel { width: 100%; height: 100%; box-sizing: border-box; display: flex; flex-direction: column; overflow: hidden; color: var(--ui-text); border: 1px solid rgba(85,214,230,.3); background: rgba(6,20,31,.96); box-shadow: -18px 0 42px rgba(0,0,0,.28), 0 0 24px rgba(85,214,230,.08); backdrop-filter: blur(16px); }.panel-header { display: flex; align-items: center; justify-content: space-between; min-height: 64px; padding: 0 14px 0 16px; border-bottom: 1px solid var(--ui-border); background: linear-gradient(90deg, rgba(85,214,230,.09), transparent 62%); }.panel-eyebrow { display: block; margin-bottom: 4px; color: var(--ui-cyan); font: 9px Consolas, monospace; letter-spacing: 1.2px; }.panel-header strong { display: block; max-width: 220px; overflow: hidden; color: #e5f4f6; font-size: 14px; font-weight: 600; text-overflow: ellipsis; white-space: nowrap; }.panel-live-dot { width: 7px; height: 7px; border-radius: 50%; background: var(--ui-success); box-shadow: 0 0 10px var(--ui-success); }.panel-live-dot.is-error { background: var(--ui-danger); box-shadow: 0 0 10px var(--ui-danger); }
.metrics { display: grid; flex: 1; min-height: 0; grid-template-columns: repeat(2, minmax(0, 1fr)); align-content: center; padding: 12px; gap: 8px; }.metric { display: flex; min-width: 0; min-height: 118px; flex-direction: column; justify-content: center; padding: 14px 12px; border: 1px solid rgba(143,190,210,.14); background: linear-gradient(135deg, rgba(85,214,230,.075), rgba(143,190,210,.025)); }.metric .label { display: block; min-height: 28px; color: #8ca6b0; font-size: 10px; line-height: 1.35; }.metric .value { display: block; overflow: hidden; margin-top: 7px; color: var(--ui-cyan); font: 600 17px Consolas, monospace; text-overflow: ellipsis; white-space: nowrap; }.metric small { display: block; margin-top: 8px; color: #597682; font: 8px Consolas, monospace; letter-spacing: .8px; }
.panel-status { display: flex; align-items: center; gap: 7px; min-height: 34px; padding: 0 15px; border-top: 1px solid rgba(143,190,210,.14); color: #66828e; font: 9px Consolas, monospace; letter-spacing: .8px; }.panel-status i { width: 5px; height: 5px; border-radius: 50%; background: var(--ui-success); box-shadow: 0 0 7px rgba(86,214,154,.8); }.panel-status i.is-error { background: var(--ui-danger); box-shadow: 0 0 7px rgba(239,118,122,.8); }
</style>
