<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import IconMark from '../components/IconMark.vue'

const navigation = [
  ['首页', '/'], ['模型展示', '/model'], ['点云展示', '/pointcloud'],
  ['高斯渲染', '/gaussian'], ['设计图预览', '/design'], ['数据管理', '/manage'], ['信息统计', '/stats'],
]
const types = [
  { key: 'MODEL', label: '三维模型', color: '#934a3e', route: '/model', icon: 'model', english: 'MODEL' },
  { key: 'POINT_CLOUD', label: '点云', color: '#a78755', route: '/pointcloud', icon: 'points', english: 'POINT CLOUD' },
  { key: 'GAUSSIAN', label: '高斯场景', color: '#47645c', route: '/gaussian', icon: 'gaussian', english: 'GAUSSIAN' },
  { key: 'DESIGN', label: '设计图', color: '#b7a798', route: '/design', icon: 'design', english: 'DRAWINGS' },
]
const taskTypes = [
  { key: 'pending', label: '排队中', color: '#9fb0bd' },
  { key: 'running', label: '运行中', color: '#75a5ad' },
  { key: 'success', label: '已完成', color: '#934a3e' },
  { key: 'failed', label: '失败', color: '#c6816b' },
]
const API_BASE = (import.meta.env.VITE_API_BASE_URL || '').replace(/\/$/, '')
const stats = ref(null)
const days = ref(30)
const selectedType = ref('GAUSSIAN')
const loading = ref(false)
const error = ref('')
let controller
let requestId = 0

const number = value => Number(value || 0).toLocaleString('zh-CN')
function bytes(value) {
  const n = Number(value || 0)
  if (n <= 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  const i = Math.min(Math.floor(Math.log(n) / Math.log(1024)), units.length - 1)
  return `${(n / 1024 ** i).toFixed(i ? 2 : 0)} ${units[i]}`
}
const category = key => stats.value?.categories?.find(item => item.key === key)
const categories = computed(() => types.map(type => ({
  ...type,
  count: Number(category(type.key)?.count || 0),
  size: Number(category(type.key)?.fileSizeBytes || 0),
})))
const total = computed(() => Number(stats.value?.totals?.totalAssets || 0))
const totalBytes = computed(() => Number(stats.value?.totals?.totalBytesAll || 0))
const tasks = computed(() => stats.value?.ingestionTasks || {})
const active = computed(() => categories.value.find(item => item.key === selectedType.value))
const trend = computed(() => stats.value?.trend?.items || [])
const periodTotal = computed(() => trend.value.reduce((sum, item) =>
  sum + types.reduce((day, type) => day + Number(item[type.key] || 0), 0), 0))
const chartMax = computed(() => {
  const max = Math.max(0, ...trend.value.map(item => types.reduce((sum, type) => sum + Number(item[type.key] || 0), 0)))
  return Math.max(4, Math.ceil(max / 4) * 4)
})
const chartTicks = computed(() => Array.from({ length: 5 }, (_, i) => Math.round(chartMax.value * (4 - i) / 4)))
const chartLabels = computed(() => {
  const interval = Math.max(1, Math.ceil(trend.value.length / 6))
  return trend.value.map((item, index) => index % interval === 0 || index === trend.value.length - 1 ? item.date.slice(5).replace('-', '.') : '')
})
const donut = computed(() => {
  if (!total.value) return 'conic-gradient(#e4ddd1 0 100%)'
  let offset = 0
  const stops = []
  for (const item of categories.value) {
    if (!item.count) continue
    const next = offset + item.count / total.value * 100
    stops.push(`${item.color} ${offset}% ${next}%`)
    offset = next
  }
  return `conic-gradient(${stops.join(', ')})`
})
const detail = computed(() => {
  const data = stats.value || {}
  const fields = {
    MODEL: [
      ['八叉树数量', number(data.models?.octreeInfoCount)],
      ['八叉树节点', number(data.models?.octreeNodes)],
      ['节点文件', number(data.models?.nodeFiles)],
    ],
    POINT_CLOUD: [
      ['点云总点数', number(data.pointClouds?.totalPoints)],
      ['数据集数量', number(data.pointClouds?.datasets)],
      ['数据格式', data.pointClouds?.format || '—'],
    ],
    GAUSSIAN: [
      ['原始高斯数', number(data.gaussians?.sourceSplatCount)],
      ['高斯数（含 LOD）', number(data.gaussians?.totalSplatCount)],
      ['含天空场景', number(data.gaussians?.scenesWithSky)],
    ],
    DESIGN: [
      ['设计图数量', number(data.designs?.totalImages)],
      ['文件格式', Object.keys(data.designs?.formats || {}).join(' · ') || '—'],
      ['格式种类', number(Object.keys(data.designs?.formats || {}).length)],
    ],
  }
  return [...fields[selectedType.value].map(([label, value]) => ({ label, value })),
    { label: '归档容量', value: bytes(active.value?.size) }]
})
const updatedTime = computed(() => {
  if (!stats.value?.updatedAt) return '—'
  const date = new Date(stats.value.updatedAt)
  return Number.isNaN(date.getTime()) ? '—' : new Intl.DateTimeFormat('zh-CN', {
    timeZone: stats.value.timezone || 'Asia/Shanghai', hour: '2-digit', minute: '2-digit', hour12: false,
  }).format(date)
})

async function load(period = days.value) {
  const previousDays = days.value
  days.value = period
  controller?.abort()
  controller = new AbortController()
  const current = ++requestId
  loading.value = true
  error.value = ''
  try {
    const response = await fetch(`${API_BASE}/api/data/statistics?days=${period}`, {
      signal: controller.signal, cache: 'no-store',
    })
    if (!response.ok) throw new Error(`统计服务返回 ${response.status}`)
    const result = await response.json()
    if (!result.totals || !Array.isArray(result.categories) || !Array.isArray(result.trend?.items)) {
      throw new Error('统计数据格式不正确')
    }
    if (current === requestId) stats.value = result
  } catch (cause) {
    if (cause.name !== 'AbortError' && current === requestId) {
      error.value = cause.message
      if (stats.value) days.value = previousDays
    }
  } finally {
    if (current === requestId) loading.value = false
  }
}
onMounted(() => load())
onBeforeUnmount(() => controller?.abort())
</script>

<template>
  <div class="heritage-shell stats-shell">
    <aside class="heritage-spine" aria-label="天坛数字遗产">
      <RouterLink class="seal" to="/" aria-label="返回首页">天<br />坛</RouterLink>
      <p>让遗产在数字中延续</p>
      <span>TIANTAN<br />1530 — 2026</span>
    </aside>
    <div class="heritage-body">
      <header class="global-top">
        <RouterLink class="brand" to="/"><strong>天坛 · 数字遗产</strong><small>TEMPLE OF HEAVEN</small></RouterLink>
        <nav aria-label="主导航">
          <RouterLink v-for="item in navigation" :key="item[1]" :to="item[1]" class="nav-link" :class="{ active: item[1] === '/stats' }">{{ item[0] }}</RouterLink>
        </nav>
        <button class="help-button" type="button" title="统计口径：按已归档资产统计，数据来自统计服务" aria-label="统计口径说明"><IconMark name="help" :size="16" /></button>
      </header>
      <main class="main-content stats-content">
        <div class="stats-intro">
          <div>
            <p class="stats-kicker">ASSET INTELLIGENCE / OVERVIEW</p>
            <h1>数字，勾勒保护的轮廓。</h1>
            <p class="stats-subtitle">从资产规模到入库动态，掌握数字成果的全貌。</p>
          </div>
          <div class="stats-update"><span>更新于 {{ updatedTime }} · {{ loading ? '更新中' : '快照' }}</span><button type="button" :disabled="loading" @click="load()"><span aria-hidden="true">◷</span> 刷新统计</button></div>
        </div>

        <div v-if="error" class="stats-error" role="alert">统计数据加载失败：{{ error }} <button type="button" @click="load()">重试</button></div>
        <div v-if="loading && !stats" class="stats-loading" role="status">正在读取统计数据…</div>
        <template v-if="stats">
          <section class="stats-overview" aria-label="资产概况">
            <div v-for="item in [
              { label: '三维模型', value: stats.totals.total3DModels, unit: 'MODEL', icon: 'model', route: '/model' },
              { label: '点云数据集', value: stats.totals.totalPointCloudModels, unit: 'POINT CLOUD', icon: 'points', route: '/pointcloud' },
              { label: '高斯场景', value: stats.totals.totalGaussianScenes, unit: 'GAUSSIAN', icon: 'gaussian', route: '/gaussian' },
              { label: '设计图', value: stats.totals.totalDesignImages, unit: 'DRAWINGS', icon: 'design', route: '/design' },
              { label: '已归档容量', value: bytes(totalBytes).split(' ')[0], unit: `${bytes(totalBytes).split(' ')[1]} / ARCHIVED`, icon: 'manage', route: '/manage' },
              { label: '有效病害', value: stats.diseases?.active, unit: 'ACTIVE RECORDS', icon: 'stats', route: '/model' },
            ]" :key="item.label" class="stats-metric">
              <RouterLink :to="item.route" :aria-label="`查看${item.label}`">
                <span class="stats-metric-label">{{ item.label }}<IconMark :name="item.icon" :size="14" /></span>
                <strong>{{ typeof item.value === 'number' ? number(item.value) : item.value }}</strong>
                <small>{{ item.unit }}</small>
              </RouterLink>
            </div>
          </section>

          <div class="stats-layout">
            <section class="stats-panel stats-composition">
              <div class="stats-panel-head"><div><p>ASSET COMPOSITION</p><h2>资产数量构成</h2></div><span class="stats-panel-aside">• 全量</span></div>
              <div class="stats-donut" :style="{ background: donut }" role="img" :aria-label="`共 ${total} 项资产`"><div><strong>{{ number(total) }}</strong><span>归档资产</span></div></div>
              <div class="stats-legend">
                <RouterLink v-for="item in categories" :key="item.key" :to="item.route" class="stats-legend-row">
                  <span><i :style="{ background: item.color }"></i>{{ item.label }}</span><b>{{ number(item.count) }}</b><small>{{ total ? Math.round(item.count / total * 100) : 0 }}%</small>
                </RouterLink>
              </div>
            </section>

            <section class="stats-panel stats-timeline">
              <div class="stats-panel-head"><div><p>ARCHIVE TIMELINE</p><h2>现存资产入库时间分布</h2></div>
                <div class="stats-period" role="group" aria-label="统计时段"><button v-for="period in [7, 30, 90]" :key="period" type="button" :class="{ selected: days === period }" :aria-pressed="days === period" :disabled="loading" @click="load(period)">{{ period }} 天</button></div>
              </div>
              <div class="stats-timeline-meta"><span><strong>{{ number(periodTotal) }}</strong> 项 <small>当前展示近 {{ days }} 天</small></span><small>时间筛选仅影响本图</small></div>
              <div class="stats-chart" role="img" :aria-label="`近 ${days} 天入库资产 ${periodTotal} 项`">
                <div class="stats-y-axis"><span v-for="(tick, index) in chartTicks" :key="index">{{ tick }}</span></div>
                <div class="stats-plot">
                  <div class="stats-gridlines"><i v-for="tick in chartTicks" :key="tick"></i></div>
                  <div class="stats-bars">
                    <div v-for="(item, index) in trend" :key="item.date" class="stats-day" :title="`${item.date}：${types.map(type => `${type.label} ${item[type.key] || 0}`).join('、')}`">
                      <div class="stats-stack"><div v-for="type in types" :key="type.key" :style="{ height: `${Number(item[type.key] || 0) / chartMax * 100}%`, background: type.color }"></div></div>
                      <time>{{ chartLabels[index] }}</time>
                    </div>
                  </div>
                  <span v-if="!periodTotal" class="stats-chart-empty">所选时段暂无入库记录</span>
                </div>
              </div>
              <div class="stats-chart-key"><span v-for="item in types" :key="item.key"><i :style="{ background: item.color }"></i>{{ item.label }}</span></div>
            </section>

            <section class="stats-panel stats-tasks">
              <div class="stats-panel-head"><div><p>PROCESSING STATUS</p><h2>异步入库任务</h2></div><span class="stats-panel-aside muted">{{ number(tasks.total) }} 个任务</span></div>
              <div class="stats-task-bar" role="img" :aria-label="`入库任务共 ${tasks.total || 0} 个`"><span v-for="item in taskTypes" :key="item.key" :style="{ flexGrow: Number(tasks[item.key] || 0), background: item.color }" :title="`${item.label} ${tasks[item.key] || 0}`"></span></div>
              <div class="stats-task-counts"><div v-for="item in taskTypes" :key="item.key"><strong>{{ String(tasks[item.key] || 0).padStart(2, '0') }}</strong><span>{{ item.label }}</span></div></div>
              <p class="stats-panel-note">统计模型、点云与高斯任务；设计图同步入库。</p>
            </section>

            <section class="stats-panel stats-storage">
              <div class="stats-panel-head"><div><p>ARCHIVED STORAGE</p><h2>分类归档容量</h2></div><span class="stats-panel-aside muted">{{ bytes(totalBytes) }}</span></div>
              <div class="stats-storage-list"><RouterLink v-for="item in categories" :key="item.key" :to="item.route" class="stats-storage-row"><span>{{ item.label }}</span><div class="stats-storage-track"><i :style="{ width: `${totalBytes ? item.size / Math.max(...categories.map(c => c.size), 1) * 100 : 0}%`, background: item.color }"></i></div><b>{{ bytes(item.size) }}</b></RouterLink></div>
              <p class="stats-panel-note">资产文件合计，不含缩略图、临时文件及日志。</p>
            </section>

            <section class="stats-panel stats-inventory">
              <div class="stats-panel-head"><div><p>DATA INVENTORY</p><h2>资产明细指标</h2></div><RouterLink :to="active.route" class="stats-detail-link">查看数据 <IconMark name="arrow" :size="14" /></RouterLink></div>
              <div class="stats-tabs" role="tablist" aria-label="资产分类"><button v-for="item in types" :key="item.key" type="button" role="tab" :aria-selected="selectedType === item.key" :class="{ selected: selectedType === item.key }" @click="selectedType = item.key">{{ item.label }}</button></div>
              <div class="stats-detail-grid" role="tabpanel"><div v-for="item in detail" :key="item.label"><span>{{ item.label }}</span><strong>{{ item.value }}</strong></div></div>
              <p class="stats-detail-note">{{ selectedType === 'GAUSSIAN' ? '天空贴图累计容量，不重复计入场景数；原始高斯数与含 LOD 数量分别统计。' : `${active.label}指标来自当前归档数据。` }}</p>
            </section>
          </div>
          <footer class="stats-footer">当前库快照 · {{ stats.timezone || 'Asia/Shanghai' }} 时间</footer>
        </template>
      </main>
    </div>
  </div>
</template>

<style scoped>
.stats-shell{--stats-surface:#fbf8f1;--stats-tint:#f5efe5}
.stats-content{max-width:1900px;padding-top:29px;padding-bottom:34px}
.stats-intro{display:flex;align-items:flex-end;justify-content:space-between;gap:20px;padding:1px 0 26px}
.stats-kicker,.stats-panel-head p{color:var(--red);font:700 8px/1.5 Consolas,monospace;letter-spacing:2.6px}
.stats-intro h1{margin-top:15px;font:400 31px/1.5 var(--display);letter-spacing:3px}
.stats-subtitle{margin-top:9px;color:var(--muted);font:11px/1.8 "Microsoft YaHei",sans-serif}
.stats-update{display:flex;align-items:center;gap:16px;margin-bottom:2px;white-space:nowrap;color:var(--muted);font:9px "Microsoft YaHei",sans-serif}
.stats-update button{min-height:37px;padding:0 15px;border:1px solid var(--line);background:transparent;cursor:pointer;font-size:11px;color:var(--ink)}
.stats-update button span{margin-right:7px;font-size:17px;vertical-align:-1px}
.stats-update button:hover:not(:disabled){border-color:var(--red);color:var(--red)}
.stats-update button:disabled,.stats-period button:disabled{opacity:.55;cursor:wait}
.stats-error{display:flex;align-items:center;gap:12px;padding:11px 15px;margin:0 0 14px;border:1px solid #c6816b;color:var(--red);font:11px "Microsoft YaHei",sans-serif}
.stats-error button{border:0;background:none;color:var(--red);text-decoration:underline;cursor:pointer}
.stats-loading{padding:70px 0;text-align:center;color:var(--muted);font:12px "Microsoft YaHei",sans-serif}
.stats-overview{display:grid;grid-template-columns:repeat(6,minmax(0,1fr));padding:31px 0 27px;border-top:1px solid var(--line);border-bottom:1px solid var(--line)}
.stats-metric{min-width:0;padding:4px 24px;border-right:1px solid var(--line)}
.stats-metric:first-child{padding-left:0}.stats-metric:last-child{padding-right:0;border-right:0}
.stats-metric a{display:block}.stats-metric a:hover .stats-metric-label{color:var(--red)}
.stats-metric-label{display:flex;align-items:center;justify-content:space-between;gap:5px;color:var(--muted);font:12px "Microsoft YaHei",sans-serif}
.stats-metric-label svg{flex:0 0 auto;color:var(--clay)}
.stats-metric strong{display:block;margin:17px 0 4px;color:var(--ink);font:400 34px/1.15 var(--display);white-space:nowrap}
.stats-metric small{display:block;color:var(--muted);font:7px/1.4 Consolas,monospace;letter-spacing:1.8px}
.stats-layout{display:grid;grid-template-columns:minmax(270px,340px) minmax(0,1fr);gap:22px;margin-top:26px}
.stats-panel{min-width:0;border:1px solid var(--line);padding:24px;background:var(--stats-surface)}
.stats-composition{background:var(--stats-tint);min-height:406px}
.stats-timeline{min-height:406px}
.stats-panel-head{display:flex;align-items:center;justify-content:space-between;gap:15px}
.stats-panel-head p{color:var(--muted);font-size:7px;letter-spacing:2.3px}
.stats-panel-head h2{margin-top:9px;font:400 17px/1.4 var(--display);letter-spacing:1px}
.stats-panel-aside{color:var(--red);font:10px "Microsoft YaHei",sans-serif;white-space:nowrap}
.stats-panel-aside.muted{color:var(--muted)}
.stats-donut{width:166px;height:166px;display:grid;place-items:center;margin:49px auto 29px;border-radius:50%}
.stats-donut>div{width:128px;height:128px;display:flex;flex-direction:column;align-items:center;justify-content:center;border-radius:50%;background:var(--stats-tint)}
.stats-donut strong{font:400 32px var(--display)}.stats-donut span{margin-top:2px;color:var(--muted);font:9px "Microsoft YaHei",sans-serif}
.stats-legend{display:grid;grid-template-columns:1fr 1fr;gap:0 20px}
.stats-legend-row{display:flex;align-items:center;gap:5px;min-width:0;height:38px;border-bottom:1px solid var(--line);font:10px "Microsoft YaHei",sans-serif}
.stats-legend-row:hover{color:var(--red)}.stats-legend-row>span{display:flex;align-items:center;gap:6px;min-width:0;white-space:nowrap;color:var(--muted)}
.stats-legend-row i,.stats-chart-key i{width:5px;height:5px;display:inline-block;flex:0 0 auto;border-radius:50%}
.stats-legend-row b{margin-left:auto;font:13px var(--display)}.stats-legend-row small{color:var(--muted);font:8px Consolas,monospace}
.stats-period{display:flex;align-items:center;gap:2px;padding:3px;border:1px solid var(--line)}
.stats-period button{height:24px;padding:0 8px;border:0;background:transparent;color:var(--muted);cursor:pointer;font:9px "Microsoft YaHei",sans-serif;white-space:nowrap}
.stats-period button.selected{background:#efe5dc;color:var(--red)}
.stats-timeline-meta{display:flex;align-items:flex-end;justify-content:space-between;gap:10px;margin-top:24px;color:var(--muted);font:9px "Microsoft YaHei",sans-serif}
.stats-timeline-meta strong{color:var(--ink);font:24px var(--display)}.stats-timeline-meta small{font-size:9px}
.stats-chart{display:grid;grid-template-columns:24px minmax(0,1fr);gap:8px;height:210px;margin:20px 0 0}
.stats-y-axis{display:flex;flex-direction:column;justify-content:space-between;padding-bottom:23px;color:var(--muted);font:8px Consolas,monospace}
.stats-plot{position:relative;min-width:0;height:100%}
.stats-gridlines{position:absolute;inset:0 0 23px;display:flex;flex-direction:column;justify-content:space-between;pointer-events:none}
.stats-gridlines i{width:100%;border-top:1px dashed #e5ded2}
.stats-bars{position:absolute;inset:0;display:flex;gap:2px}
.stats-day{display:flex;flex:1 1 0;min-width:0;flex-direction:column;justify-content:flex-end}
.stats-stack{height:calc(100% - 23px);display:flex;flex-direction:column-reverse;justify-content:flex-start;align-items:stretch}
.stats-stack div{width:min(100%,11px);margin:0 auto;flex:0 0 auto}
.stats-day time{height:23px;padding-top:9px;color:var(--muted);text-align:center;white-space:nowrap;font:8px Consolas,monospace}
.stats-chart-empty{position:absolute;top:42%;left:50%;transform:translate(-50%,-50%);color:var(--muted);font:10px "Microsoft YaHei",sans-serif;white-space:nowrap}
.stats-chart-key{display:flex;justify-content:center;gap:20px;margin-top:10px;color:var(--muted);font:10px "Microsoft YaHei",sans-serif}
.stats-chart-key span{display:flex;align-items:center;gap:5px}
.stats-tasks,.stats-storage{min-height:293px}
.stats-task-bar{height:17px;display:flex;gap:4px;margin:34px 0 22px;background:#ebe6db}
.stats-task-bar span{min-width:0}
.stats-task-counts{display:grid;grid-template-columns:repeat(4,1fr)}
.stats-task-counts>div{min-width:0;padding-left:12px;border-left:1px solid var(--line)}
.stats-task-counts>div:first-child{padding-left:0;border:0}
.stats-task-counts strong{display:block;font:25px/1.3 var(--display)}
.stats-task-counts span{display:block;margin-top:5px;color:var(--muted);font:10px "Microsoft YaHei",sans-serif}
.stats-panel-note{margin-top:24px;padding-top:14px;border-top:1px solid var(--line);color:var(--muted);font:10px/1.7 "Microsoft YaHei",sans-serif}
.stats-storage-list{display:grid;gap:16px;margin-top:26px}
.stats-storage-row{display:grid;grid-template-columns:60px minmax(0,1fr) 88px;align-items:center;gap:12px;font:11px "Microsoft YaHei",sans-serif}
.stats-storage-row:hover{color:var(--red)}
.stats-storage-track{height:8px;background:#eeeadf}
.stats-storage-track i{display:block;height:100%}
.stats-storage-row b{text-align:right;white-space:nowrap;font:10px Consolas,monospace}
.stats-storage .stats-panel-note{margin-top:18px}
.stats-inventory{grid-column:1/-1;min-height:271px}
.stats-detail-link{display:flex;align-items:center;gap:10px;color:var(--red);white-space:nowrap;font:10px "Microsoft YaHei",sans-serif}
.stats-tabs{display:flex;gap:29px;margin-top:23px;border-bottom:1px solid var(--line)}
.stats-tabs button{position:relative;height:31px;padding:0 2px 10px;border:0;background:none;color:var(--muted);cursor:pointer;font:10px "Microsoft YaHei",sans-serif}
.stats-tabs button.selected{color:var(--red)}.stats-tabs button.selected:after{content:"";position:absolute;left:0;right:0;bottom:-1px;border-bottom:2px solid var(--red)}
.stats-detail-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));padding:22px 0 19px;border-bottom:1px solid var(--line)}
.stats-detail-grid>div{min-width:0;padding:0 20px;border-left:1px solid var(--line)}
.stats-detail-grid>div:first-child{padding-left:0;border:0}
.stats-detail-grid span{display:block;color:var(--muted);font:10px "Microsoft YaHei",sans-serif}
.stats-detail-grid strong{display:block;margin-top:10px;overflow-wrap:anywhere;font:400 23px/1.3 var(--display)}
.stats-detail-note{margin-top:15px;color:var(--muted);font:10px/1.6 "Microsoft YaHei",sans-serif}
.stats-footer{margin-top:20px;text-align:right;color:var(--muted);font:9px "Microsoft YaHei",sans-serif}
@media(max-width:1100px){.stats-metric{padding-right:14px;padding-left:14px}.stats-metric strong{font-size:28px}.stats-layout{grid-template-columns:minmax(240px,32%) minmax(0,1fr);gap:14px}.stats-panel{padding:19px}.stats-storage-row{grid-template-columns:55px minmax(0,1fr) 75px;gap:8px}}
@media(max-width:800px){.stats-overview{grid-template-columns:repeat(3,minmax(0,1fr));row-gap:20px}.stats-metric:nth-child(3){border-right:0}.stats-metric:nth-child(4){padding-left:0}.stats-layout{grid-template-columns:1fr 1fr}.stats-timeline,.stats-storage{grid-column:1/-1}.stats-composition,.stats-tasks{min-height:0}.stats-donut{margin-top:26px}.stats-intro h1{font-size:26px}}
@media(max-width:600px){.stats-content{padding-top:20px}.stats-intro{display:block;padding-bottom:19px}.stats-intro h1{font-size:22px;letter-spacing:1px}.stats-update{justify-content:space-between;margin-top:17px}.stats-overview{padding:20px 0;row-gap:16px}.stats-metric{padding:0 7px}.stats-metric-label{font-size:9px}.stats-metric-label svg{display:none}.stats-metric strong{font-size:23px;margin:11px 0 3px}.stats-metric small{font-size:6px;letter-spacing:0;overflow-wrap:anywhere}.stats-layout{display:flex;flex-direction:column;gap:13px;margin-top:17px}.stats-panel{padding:17px}.stats-composition,.stats-timeline,.stats-tasks,.stats-storage,.stats-inventory{min-height:0}.stats-legend{gap:0 12px}.stats-timeline-meta{margin-top:17px}.stats-chart{height:190px}.stats-chart-key{gap:8px;flex-wrap:wrap;font-size:8px}.stats-chart-key i{width:4px;height:4px}.stats-storage-row{grid-template-columns:55px minmax(0,1fr) 73px;font-size:9px}.stats-storage-row b{font-size:8px}.stats-detail-grid{grid-template-columns:repeat(2,minmax(0,1fr));gap:15px 0}.stats-detail-grid>div:nth-child(3){padding-left:0;border:0}.stats-detail-grid strong{font-size:19px}.stats-tabs{gap:15px}}
</style>
