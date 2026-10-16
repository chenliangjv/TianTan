<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowRight, Refresh, Box, Picture, Connection, Files, Coin, Warning } from '@element-plus/icons-vue'
import config from '@/config/index.js'
import StatisticsChart from '@/components/StatisticsChart.vue'
import { ASSET_TYPES, TASK_STATES, formatBytes, formatNumber, trendOption, distributionOption, capacityOption, tasksOption } from '@/utils/statistics.js'

const router = useRouter()
const statistics = ref(null)
const loading = ref(false)
const error = ref('')
const days = ref(30)
const activeCategory = ref('MODEL')
let controller, requestId = 0, lastRefresh = 0
const icons = [Box, Picture, Connection, Files]

async function loadStatistics() {
  controller?.abort()
  controller = new AbortController()
  const signal = controller.signal
  const id = ++requestId
  const requestedDays = days.value
  loading.value = true
  error.value = ''
  const timeout = setTimeout(() => controller?.signal === signal && controller.abort(), 20000)
  try {
    const response = await fetch(`${config.API_BASE_URL}/api/data/statistics?days=${requestedDays}`, { signal, cache: 'no-store' })
    if (!response.ok) throw new Error(`统计服务请求失败（HTTP ${response.status}）`)
    const data = await response.json()
    if (!data.gaussians || !data.designs || !Array.isArray(data.categories) || !Array.isArray(data.trend?.items)) {
      throw new Error('统计接口尚未更新，请重启数据服务后重试')
    }
    if (id !== requestId) return
    statistics.value = data
    lastRefresh = Date.now()
  } catch (e) {
    if (id !== requestId) return
    error.value = e.name === 'AbortError' ? '统计请求超时，请检查数据服务后重试' : e.message
    // A failed period change must not label stale data with the requested period.
    if (statistics.value) days.value = statistics.value.trend.days
  } finally {
    clearTimeout(timeout)
    if (id === requestId) loading.value = false
  }
}
function onFocus() {
  if (!loading.value && Date.now() - lastRefresh > 30000) loadStatistics()
}
onMounted(() => { loadStatistics(); window.addEventListener('focus', onFocus) })
onBeforeUnmount(() => { requestId++; controller?.abort(); window.removeEventListener('focus', onFocus) })

const updatedTime = computed(() => statistics.value ? new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
}).format(new Date(statistics.value.updatedAt)) : '')
const metrics = computed(() => {
  const s = statistics.value
  if (!s) return []
  return [
    ...ASSET_TYPES.map((type, index) => ({ ...type, icon: icons[index], value: formatNumber(s.categories.find(c => c.key === type.key)?.count), unit: '项' })),
    { key: 'bytes', label: '已归档数据容量', color: '#55d6e6', icon: Coin, value: formatBytes(s.totals.totalBytesAll), hint: '四类资产文件合计' },
    { key: 'disease', label: '有效病害', color: '#ef9296', icon: Warning, value: formatNumber(s.diseases.active), unit: '处', hint: `全部标注 ${formatNumber(s.diseases.total)} 处` },
  ]
})
const trend = computed(() => statistics.value && trendOption(statistics.value.trend))
const distribution = computed(() => statistics.value && distributionOption(statistics.value.categories, statistics.value.totals.totalAssets))
const capacity = computed(() => statistics.value && capacityOption(statistics.value.categories))
const tasks = computed(() => statistics.value && tasksOption(statistics.value.ingestionTasks))
const periodCount = computed(() => statistics.value?.trend.items.reduce((sum, day) => sum + ASSET_TYPES.reduce((n, type) => n + day[type.key], 0), 0) || 0)
const selectedType = computed(() => ASSET_TYPES.find(type => type.key === activeCategory.value))
const detailRows = computed(() => {
  const s = statistics.value
  if (!s) return []
  switch (activeCategory.value) {
    case 'MODEL': return [['八叉树', formatNumber(s.models.octreeInfoCount)], ['节点数量', formatNumber(s.models.octreeNodes)], ['模型文件', formatNumber(s.models.nodeFiles)], ['归档容量', formatBytes(s.models.fileSizeBytes)]]
    case 'POINT_CLOUD': return [['数据集', formatNumber(s.pointClouds.datasets)], ['总点数', formatNumber(s.pointClouds.totalPoints)], ['数据格式', s.pointClouds.format], ['归档容量', formatBytes(s.pointClouds.fileSizeBytes)]]
    case 'GAUSSIAN': return [['场景数量', formatNumber(s.gaussians.totalScenes)], ['原始高斯数（已知）', formatNumber(s.gaussians.sourceSplatCount)], ['高斯数（含 LOD）', formatNumber(s.gaussians.totalSplatCount)], ['RAD 分块', formatNumber(s.gaussians.chunkCount)], ['含天空场景', formatNumber(s.gaussians.scenesWithSky)], ['主体 RAD 容量', formatBytes(s.gaussians.mainBytes)], ['天空 PLY 容量', formatBytes(s.gaussians.skyBytes)], ['归档容量', formatBytes(s.gaussians.fileSizeBytes)]]
    default: return [['图像数量', formatNumber(s.designs.totalImages)], ['PNG 图像', formatNumber(s.designs.formats.PNG)], ['JPEG 图像', formatNumber(s.designs.formats.JPEG)], ['原图容量', formatBytes(s.designs.fileSizeBytes)]]
  }
})
const detailNote = computed(() => activeCategory.value === 'GAUSSIAN'
  ? `原始高斯数与含 LOD 高斯数分别统计，不相加；天空不增加场景数量。${statistics.value.gaussians.sourceCountKnownScenes} 个场景具有原始高斯数记录。`
  : activeCategory.value === 'DESIGN' ? '设计图按已入库原图统计；同步上传，不纳入异步任务统计。' : '统计当前库中已归档的数据，删除资产后重新获取即可更新。')
function openCategory(key) {
  if (ASSET_TYPES.some(type => type.key === key)) router.push({ path: '/data-import', query: { category: key } })
}
</script>

<template>
  <main class="data-stats-page" :aria-busy="loading">
    <header class="page-heading">
      <div><p class="section-kicker"><span /> ASSET INTELLIGENCE</p><h1>信息统计 <span>/ 数字资产概览</span></h1><p class="heading-description">从三维实景到二维图像，一览建筑遗产数字成果。</p></div>
      <div class="heading-actions"><span v-if="statistics" class="updated-at"><i :class="{ stale: error }" />{{ error ? '数据待更新' : '更新于' }} {{ updatedTime }} <small>北京时间</small></span><el-button :icon="Refresh" :loading="loading" @click="loadStatistics">刷新数据</el-button></div>
    </header>

    <div v-if="error" class="error-banner" role="alert"><el-icon><Warning /></el-icon><span>{{ error }}<template v-if="statistics">。当前保留上次成功获取的数据。</template></span><el-button text :disabled="loading" @click="loadStatistics">重试</el-button></div>
    <section v-if="!statistics" class="initial-state"><template v-if="loading"><div class="loading-orbit" /><h2>正在汇总数字资产</h2><p>读取资产、容量与入库任务统计…</p></template><template v-else><el-icon><Warning /></el-icon><h2>暂时无法获取统计数据</h2><p>请确认数据服务已启动，再点击重试。</p></template></section>

    <template v-else>
      <section class="summary-grid" aria-label="资产关键指标">
        <article v-for="metric in metrics" :key="metric.key" class="metric-card" :style="{ '--metric-color': metric.color }">
          <div class="metric-title"><span>{{ metric.label }}</span><el-icon><component :is="metric.icon" /></el-icon></div>
          <div class="metric-value"><strong>{{ metric.value }}</strong><small v-if="metric.unit">{{ metric.unit }}</small></div><p>{{ metric.hint }}</p>
        </article>
      </section>

      <section class="chart-grid">
        <article class="panel trend-panel">
          <header class="panel-heading"><div><p class="panel-eyebrow">ARCHIVE TIMELINE</p><h2>现存资产入库时间分布</h2></div><div class="period-control" aria-label="时间范围"><button v-for="period in [7, 30, 90]" :key="period" type="button" :aria-pressed="days === period" :disabled="loading" @click="days = period; loadStatistics()">{{ period }} 天</button></div></header>
          <div class="trend-summary"><strong>{{ formatNumber(periodCount) }}</strong><span>项现存资产在此期间入库</span><small>{{ statistics.trend.startDate }} — {{ statistics.trend.endDate }}</small></div>
          <div class="trend-chart chart-wrap"><StatisticsChart :option="trend" label="四类现存资产每日入库数量堆叠柱状图" /><span v-if="!periodCount" class="chart-empty">此期间暂无现存资产入库记录</span></div>
          <p class="panel-footnote">时间筛选仅影响本图 · 删除的资产不再显示于分布中</p>
        </article>

        <article class="panel distribution-panel">
          <header class="panel-heading"><div><p class="panel-eyebrow">ASSET COMPOSITION</p><h2>资产数量构成</h2></div><span class="panel-tag">全部资产</span></header>
          <div class="distribution-chart"><StatisticsChart :option="distribution" label="四类资产数量占比，点击分类查看数据" @select="openCategory($event.data?.category)" /></div>
          <div class="distribution-legend"><button v-for="type in ASSET_TYPES" :key="type.key" type="button" @click="openCategory(type.key)"><i :style="{ background: type.color }" /><span>{{ type.label }}</span><strong>{{ formatNumber(statistics.categories.find(c => c.key === type.key)?.count) }}</strong><el-icon><ArrowRight /></el-icon></button></div>
        </article>

        <article class="panel capacity-panel">
          <header class="panel-heading"><div><p class="panel-eyebrow">STORAGE DISTRIBUTION</p><h2>分类归档容量</h2></div><strong class="panel-total">{{ formatBytes(statistics.totals.totalBytesAll) }}</strong></header>
          <div class="capacity-chart"><StatisticsChart :option="capacity" label="模型、点云、高斯和设计图归档容量横向条形图" /></div>
          <p class="panel-footnote">仅计资产文件，不含临时文件、缩略图及日志，不代表磁盘占用。</p>
        </article>

        <article class="panel tasks-panel">
          <header class="panel-heading"><div><p class="panel-eyebrow">INGESTION PIPELINE</p><h2>异步入库任务</h2></div><span class="panel-tag">{{ formatNumber(statistics.ingestionTasks.total) }} 个任务</span></header>
          <div class="tasks-chart chart-wrap"><StatisticsChart :option="tasks" label="排队、运行、成功和失败的异步任务数量" /><span v-if="!statistics.ingestionTasks.total" class="chart-empty">暂无异步入库任务</span></div>
          <div class="task-metrics"><div v-for="state in TASK_STATES" :key="state.key"><span><i :style="{ background: state.color }" />{{ state.label }}</span><strong :style="{ color: state.color }">{{ formatNumber(statistics.ingestionTasks[state.key]) }}</strong></div></div>
          <p class="panel-footnote">统计模型、点云与高斯任务；设计图为同步入库，不计入此处。</p>
        </article>
      </section>

      <section class="panel asset-details">
        <header class="detail-heading"><div><p class="panel-eyebrow">DATA INVENTORY</p><h2>资产明细指标</h2></div><div class="detail-links"><RouterLink :to="selectedType.route">打开{{ selectedType.label }} <el-icon><ArrowRight /></el-icon></RouterLink><RouterLink :to="{ path: '/data-import', query: { category: activeCategory } }">查看数据 <el-icon><ArrowRight /></el-icon></RouterLink></div></header>
        <div class="category-tabs" aria-label="资产分类"><button v-for="type in ASSET_TYPES" :key="type.key" :aria-pressed="activeCategory === type.key" :style="{ '--tab-color': type.color }" type="button" @click="activeCategory = type.key">{{ type.label }}</button></div>
        <dl class="detail-metrics"><div v-for="[label, value] in detailRows" :key="label"><dt>{{ label }}</dt><dd>{{ value }}</dd></div></dl>
        <p class="panel-footnote">{{ detailNote }}</p>
      </section>
      <footer class="stats-footer"><span><i /> 当前库快照 · 四类资产统一统计</span><span>病害标注 {{ formatNumber(statistics.diseases.total) }} 处 / 有效 {{ formatNumber(statistics.diseases.active) }} 处</span></footer>
    </template>
  </main>
</template>

<style scoped>
.data-stats-page { height: 100%; overflow: auto; padding: 28px clamp(18px, 2.5vw, 48px) 20px; background: radial-gradient(ellipse at 80% 0, rgba(36, 87, 112, .12), transparent 50%), var(--ui-bg); color: var(--ui-text); }
.page-heading { display: flex; justify-content: space-between; align-items: flex-end; gap: 20px; margin-bottom: 24px; }
.section-kicker { display: flex; align-items: center; gap: 9px; margin: 0 0 10px; color: var(--ui-cyan); font: 10px Consolas, monospace; letter-spacing: 2px; }.section-kicker span { width: 20px; height: 1px; background: var(--ui-cyan); }
h1, h2, p { margin: 0; }h1 { font-size: 26px; font-weight: 600; letter-spacing: 1px; }h1 span { margin-left: 8px; color: #7998a7; font-size: 13px; font-weight: 400; letter-spacing: .5px; }.heading-description { margin-top: 10px; font-size: 12px; color: var(--ui-muted); }
.heading-actions { display: flex; align-items: flex-end; gap: 12px; flex-direction: column; }.updated-at { color: #7e9aaa; font: 10px Consolas, monospace; }.updated-at small { margin-left: 5px; font-size: 10px; }.updated-at i, .stats-footer i { display: inline-block; width: 5px; height: 5px; margin-right: 6px; border-radius: 50%; background: var(--ui-success); }.updated-at i.stale { background: var(--ui-amber); }
.summary-grid { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); gap: 12px; margin-bottom: 18px; }.metric-card { position: relative; min-width: 0; padding: 17px 16px 14px; border: 1px solid var(--ui-border); border-radius: 6px; background: linear-gradient(145deg, rgba(20, 43, 60, .7), rgba(11, 26, 39, .9)); }.metric-card::before { position: absolute; content: ''; top: 0; left: 16px; width: 26px; height: 2px; background: var(--metric-color); }.metric-title { display: flex; align-items: center; justify-content: space-between; gap: 6px; color: #a1b6c1; font-size: 12px; }.metric-title .el-icon { color: var(--metric-color); font-size: 17px; }.metric-value { display: flex; align-items: baseline; flex-wrap: wrap; gap: 6px; margin: 17px 0 10px; }.metric-value strong { font: 600 clamp(20px, 1.6vw, 29px) Consolas, monospace; letter-spacing: -.5px; }.metric-value small { color: #678492; font-size: 10px; }.metric-card p { color: #678492; font-size: 10px; line-height: 1.5; }
.chart-grid { display: grid; grid-template-columns: minmax(0, 1.65fr) minmax(310px, 1fr); gap: 18px; }.panel { min-width: 0; padding: 20px; border: 1px solid var(--ui-border); border-radius: 6px; background: rgba(13, 31, 45, .82); }.panel-heading, .detail-heading { display: flex; justify-content: space-between; align-items: center; gap: 12px; }.panel-eyebrow { margin: 0 0 7px; color: #627f90; font: 9px Consolas, monospace; letter-spacing: 1.4px; }h2 { font-size: 14px; font-weight: 550; letter-spacing: .3px; }.panel-tag { padding: 4px 7px; color: #8aa6b5; background: rgba(143, 190, 210, .06); border: 1px solid rgba(143, 190, 210, .09); border-radius: 3px; font-size: 10px; white-space: nowrap; }.period-control { display: flex; padding: 3px; gap: 3px; background: #081823; border: 1px solid var(--ui-border); border-radius: 4px; }.period-control button { border: none; padding: 5px 9px; border-radius: 2px; background: transparent; color: #829dac; font-size: 11px; cursor: pointer; white-space: nowrap; }.period-control button[aria-pressed=true] { background: rgba(85,214,230,.13); color: var(--ui-cyan); }.period-control button:disabled { cursor: wait; }
.trend-summary { display: flex; align-items: baseline; gap: 8px; flex-wrap: wrap; margin-top: 22px; }.trend-summary strong { font: 25px Consolas, monospace; }.trend-summary span { font-size: 11px; color: var(--ui-muted); }.trend-summary small { margin-left: auto; color: #688695; font: 10px Consolas, monospace; }.trend-chart { height: 255px; margin-top: 12px; }.chart-wrap { position: relative; }.chart-empty { position: absolute; pointer-events: none; left: 50%; top: 45%; transform: translate(-50%, -50%); color: #7994a3; padding: 7px; background: rgba(13,31,45,.85); font-size: 11px; white-space: nowrap; }.panel-footnote { margin-top: 14px; padding-top: 12px; border-top: 1px solid rgba(143,190,210,.08); font-size: 10px; line-height: 1.7; color: #698795; }
.distribution-chart { height: 228px; }.distribution-legend { display: grid; grid-template-columns: 1fr 1fr; gap: 8px 16px; margin-top: 4px; }.distribution-legend button { display: flex; align-items: center; gap: 8px; padding: 9px 0; min-width: 0; border: 0; border-bottom: 1px solid rgba(143,190,210,.09); background: transparent; text-align: left; color: #a7bdc9; font-size: 11px; cursor: pointer; }.distribution-legend button:hover { color: var(--ui-cyan); }.distribution-legend i, .task-metrics i { width: 6px; height: 6px; flex-shrink: 0; border-radius: 2px; }.distribution-legend strong { margin-left: auto; font: 14px Consolas, monospace; color: #d2e2e9; }.distribution-legend .el-icon { font-size: 10px; color: #4c697b; }
.panel-total { color: #afcad7; font: 15px Consolas, monospace; }.capacity-chart { height: 170px; margin-top: 18px; }.tasks-chart { height: 58px; margin-top: 16px; }.task-metrics { display: grid; grid-template-columns: repeat(4, 1fr); margin: 16px 0 22px; }.task-metrics > div { padding: 0 10px; border-right: 1px solid var(--ui-border); }.task-metrics > div:first-child { padding-left: 0; }.task-metrics > div:last-child { border-right: none; }.task-metrics span { display: flex; align-items: center; gap: 6px; font-size: 11px; color: var(--ui-muted); white-space: nowrap; }.task-metrics strong { display: block; margin-top: 12px; font: 23px Consolas, monospace; }
.asset-details { margin-top: 18px; }.detail-links { display: flex; gap: 18px; }.detail-links a { display: flex; align-items: center; gap: 5px; color: #85bdcd; font-size: 11px; text-decoration: none; }.detail-links a:hover { color: var(--ui-cyan); }.category-tabs { display: flex; gap: 22px; margin-top: 18px; border-bottom: 1px solid var(--ui-border); }.category-tabs button { padding: 8px 2px 10px; background: transparent; border: none; border-bottom: 2px solid transparent; color: #819ba9; font-size: 12px; cursor: pointer; }.category-tabs button[aria-pressed=true] { color: var(--tab-color); border-bottom-color: var(--tab-color); }.detail-metrics { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 22px 16px; margin: 22px 0 0; }.detail-metrics div { border-left: 1px solid var(--ui-border); padding-left: 16px; }.detail-metrics dt { color: #809ba9; font-size: 11px; }.detail-metrics dd { margin: 9px 0 0; font: 19px Consolas, monospace; overflow-wrap: anywhere; }.stats-footer { display: flex; justify-content: space-between; gap: 12px; padding-top: 17px; color: #567687; font-size: 10px; }
.error-banner { display: flex; align-items: center; gap: 10px; padding: 8px 14px; margin-bottom: 18px; border: 1px solid rgba(239,118,122,.3); background: rgba(239,118,122,.07); border-radius: 4px; color: #efb0ae; font-size: 12px; }.error-banner .el-button { margin-left: auto; }.initial-state { display: flex; min-height: 400px; flex-direction: column; align-items: center; justify-content: center; gap: 18px; border: 1px dashed var(--ui-border); border-radius: 6px; }.initial-state p { font-size: 12px; color: var(--ui-muted); }.initial-state .el-icon { font-size: 30px; color: var(--ui-amber); }.loading-orbit { width: 32px; height: 32px; border: 2px solid var(--ui-border); border-top-color: var(--ui-cyan); border-radius: 50%; animation: orbit 1s linear infinite; }@keyframes orbit { to { transform: rotate(360deg); } }
@media (prefers-reduced-motion: reduce) { .loading-orbit { animation: none; } }
@media (max-width: 1250px) { .summary-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); }.chart-grid { grid-template-columns: minmax(0, 1.3fr) minmax(285px, 1fr); }.page-heading h1 span { display: none; }.trend-summary small { width: 100%; margin: 0; }.panel { padding: 16px; } }
@media (max-width: 1000px) { .chart-grid { grid-template-columns: minmax(0, 1fr); }.distribution-panel { display: grid; grid-template-columns: 1fr 1fr; align-items: center; }.distribution-panel .panel-heading { grid-column: 1 / -1; }.distribution-legend { grid-template-columns: 1fr; }.distribution-chart { height: 240px; }.detail-metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); }.heading-actions { flex-shrink: 0; }.updated-at small { display: none; } }
@media (max-width: 600px) { .data-stats-page { padding: 20px 12px; }.page-heading { flex-direction: column; align-items: stretch; }.heading-actions { flex-direction: row; justify-content: space-between; align-items: center; }.summary-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; }.metric-card { padding: 15px 12px; }.metric-value strong { font-size: 23px; }.metric-title { font-size: 11px; }.panel-heading { align-items: flex-start; flex-wrap: wrap; }.distribution-panel { display: block; }.distribution-legend { grid-template-columns: 1fr 1fr; }.trend-summary small { font-size: 9px; }.detail-heading { flex-wrap: wrap; }.detail-links { margin-top: 8px; }.category-tabs { gap: 16px; }.task-metrics > div { padding: 0 6px; }.task-metrics span { font-size: 10px; }.stats-footer { flex-direction: column; } }
</style>
