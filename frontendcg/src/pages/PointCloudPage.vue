<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { Color, PerspectiveCamera, Scene, SRGBColorSpace, Vector3, WebGLRenderer } from 'three'
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js'
import { PointShape, PointSizeType, Potree, PotreeRenderer } from 'potree-core'
import { attachPointCloudColor, DEFAULT_POINT_CLOUD_COLOR, normalizePointCloudColor } from '../utils/pointCloudColor.js'

const API_BASE = (import.meta.env.VITE_API_BASE_URL || '').replace(/\/$/, '')
const navigation = [
  ['首页', '/'], ['模型展示', '/model'], ['点云展示', '/pointcloud'],
  ['高斯渲染', '/gaussian'], ['设计图预览', '/design'], ['数据管理', '/manage'], ['信息统计', '/stats'],
]

const canvas = ref(null)
const stage = ref(null)
const datasets = ref([])
const selectedId = ref('')
const dataset = ref(null)
const search = ref('')
const listLoading = ref(false)
const loading = ref(false)
const error = ref('')
const renderError = ref('')
const background = ref('#21312d')
const pointBudget = ref(1000000)
const pointSize = ref(1.1)
const edl = ref(false)
const boundingBox = ref(false)
const colorMode = ref('rgb')
const solidColor = ref('#ffffff')
const colorControls = ref({
  brightness: 0,
  contrast: 0,
  gamma: 1,
})
const visiblePoints = ref(0)
const progress = ref(0)
const fullscreen = ref(false)
const showControls = ref(true)
const showStats = ref(true)
const showBackground = ref(true)

let renderer
let scene
let camera
let controls
let potree
let potreeRenderer
let cloud
let resizeObserver
let raf
let requestId = 0
let abortController
let colorController
let colorUpdateFrame

const filteredDatasets = computed(() => {
  const q = search.value.trim().toLowerCase()
  return q ? datasets.value.filter(item => String(item.name || '').toLowerCase().includes(q)) : datasets.value
})
const selected = computed(() => datasets.value.find(item => String(item.id) === String(selectedId.value)) || null)
const getPointCount = item => Number(item?.pointCount ?? item?.points ?? 0)
const pointCount = computed(() => getPointCount(dataset.value) || getPointCount(selected.value))
const statusText = computed(() => loading.value ? '正在建立点云索引' : cloud ? '实时渲染' : '等待加载点云')
const formatNumber = value => Number(value || 0).toLocaleString('zh-CN')
const apiUrl = path => /^https?:\/\//i.test(path) ? path : `${API_BASE}${path}`

function responseError(response) {
  return response.text().then(text => text || `请求失败：${response.status}`)
}
function clearCloud() {
  if (colorUpdateFrame) cancelAnimationFrame(colorUpdateFrame)
  colorUpdateFrame = undefined
  colorController?.dispose()
  colorController = null
  if (cloud) {
    scene?.remove(cloud)
    cloud.dispose?.()
  }
  cloud = null
  visiblePoints.value = 0
  progress.value = 0
}
function applySettings() {
  if (!cloud || !potree) return
  potree.pointBudget = pointBudget.value
  cloud.material.pointSizeType = PointSizeType.ADAPTIVE
  cloud.material.shape = PointShape.CIRCLE
  cloud.material.size = pointSize.value
  cloud.material.minSize = 0.65
  cloud.material.maxSize = Math.max(3, pointSize.value * 4)
  cloud.showBoundingBox = boundingBox.value
  potreeRenderer?.setEDL({ enabled: edl.value, pointCloudLayer: 1, strength: 0.24, radius: 1.15, opacity: 1 })
  applyColorSettings()
}
function applyColorSettings() {
  if (!colorController) return
  colorController.update(normalizePointCloudColor({
    mode: colorMode.value,
    solid: solidColor.value,
    brightness: colorControls.value.brightness,
    contrast: colorControls.value.contrast,
    gamma: colorControls.value.gamma,
  }))
}
function updateBackground() {
  renderer?.setClearColor(new Color(background.value), 1)
}
function fitCamera() {
  if (!cloud || !camera || !controls) return
  const sphere = cloud.boundingSphere
  const radius = Math.max(sphere.radius, 1)
  const direction = new Vector3(1, 0.72, 1).normalize()
  camera.position.copy(sphere.center).add(direction.multiplyScalar(radius / Math.sin((camera.fov * Math.PI) / 360) * 1.15))
  camera.near = Math.max(0.01, radius / 10000)
  camera.far = Math.max(10000, radius * 40)
  camera.updateProjectionMatrix()
  controls.target.copy(sphere.center)
  controls.update()
}
function zoom(factor) {
  if (!cloud || !controls) return
  const offset = camera.position.clone().sub(controls.target)
  camera.position.copy(controls.target).add(offset.setLength(Math.max(1, offset.length() * factor)))
  controls.update()
}
async function toggleFullscreen() {
  try {
    if (document.fullscreenElement) await document.exitFullscreen()
    else await stage.value?.requestFullscreen()
  } catch {
    renderError.value = '浏览器未允许全屏，请检查页面权限。'
  }
}
function fullscreenChanged() {
  fullscreen.value = document.fullscreenElement === stage.value
}
async function loadList() {
  listLoading.value = true
  error.value = ''
  try {
    const response = await fetch(apiUrl('/api/data/point-clouds/list'), { cache: 'no-store' })
    if (!response.ok) throw new Error(await responseError(response))
    const body = await response.json()
    datasets.value = Array.isArray(body) ? body : body?.data || body?.items || []
  } catch (cause) {
    error.value = cause.message || '无法获取点云数据集'
  } finally {
    listLoading.value = false
  }
}
async function loadDataset(item) {
  const id = String(item.id)
  const current = ++requestId
  abortController?.abort()
  abortController = new AbortController()
  clearCloud()
  selectedId.value = id
  dataset.value = null
  loading.value = true
  error.value = ''
  renderError.value = ''
  try {
    const response = await fetch(apiUrl(`/api/data/point-clouds/${encodeURIComponent(id)}/dataset`), { signal: abortController.signal })
    if (!response.ok) throw new Error(await responseError(response))
    const detail = await response.json()
    if (detail.format !== 'POTREE_2') throw new Error('当前数据集不是 Potree 2.0 格式')
    if (!detail.metadataUrl) throw new Error('数据集缺少 metadata.json 地址')
    if (current !== requestId) return
    dataset.value = detail
    const manager = {
      getUrl: async resource => apiUrl(resource),
      fetch: async (resource, options = {}) => {
        const headers = new Headers(options.headers)
        if (headers.get('content-type')?.toLowerCase().startsWith('multipart/byteranges')) {
          headers.delete('content-type')
        }
        const response = await window.fetch(
          typeof resource === 'string' ? apiUrl(resource) : resource,
          { ...options, headers, signal: abortController.signal },
        )
        if (!response.ok) throw new Error(await responseError(response))
        return response
      },
    }
    const nextCloud = await potree.loadPointCloud(apiUrl(detail.metadataUrl), manager)
    if (current !== requestId) {
      nextCloud.dispose?.()
      return
    }
    cloud = nextCloud
    cloud.name = detail.name || item.name || '点云数据集'
    scene.add(cloud)
    colorController = attachPointCloudColor(cloud.material, {
      onError: cause => {
        renderError.value = cause.message || '点云颜色控制初始化失败'
        console.error('[PointCloud] 颜色控制不可用:', cause)
      },
    })
    colorController.update(normalizePointCloudColor({
      mode: colorMode.value,
      solid: solidColor.value,
      brightness: colorControls.value.brightness,
      contrast: colorControls.value.contrast,
      gamma: colorControls.value.gamma,
    }))
    applySettings()
    fitCamera()
  } catch (cause) {
    if (cause.name !== 'AbortError' && current === requestId) {
      error.value = cause.message || '点云数据加载失败'
      renderError.value = error.value
      selectedId.value = ''
      dataset.value = null
    }
  } finally {
    if (current === requestId) loading.value = false
  }
}
function unload() {
  requestId += 1
  abortController?.abort()
  clearCloud()
  selectedId.value = ''
  dataset.value = null
  loading.value = false
  error.value = ''
  renderError.value = ''
}
function resize() {
  if (!canvas.value || !renderer || !camera) return
  const width = canvas.value.clientWidth
  const height = canvas.value.clientHeight
  if (!width || !height) return
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 1.5))
  renderer.setSize(width, height, false)
  camera.aspect = width / height
  camera.updateProjectionMatrix()
}
function renderLoop() {
  raf = requestAnimationFrame(renderLoop)
  controls?.update()
  if (potree && cloud) {
    const result = potree.updatePointClouds([cloud], camera, renderer)
    if (edl.value) potreeRenderer.render({ renderer, scene, camera, pointClouds: [cloud] })
    else renderer.render(scene, camera)
    visiblePoints.value = result.numVisiblePoints || 0
    progress.value = cloud.progress || 0
  } else renderer?.render(scene, camera)
}
async function init() {
  await nextTick()
  scene = new Scene()
  camera = new PerspectiveCamera(60, 1, 0.01, 100000)
  renderer = new WebGLRenderer({ antialias: true, logarithmicDepthBuffer: true, powerPreference: 'high-performance' })
  renderer.outputColorSpace = SRGBColorSpace
  canvas.value.appendChild(renderer.domElement)
  updateBackground()
  controls = new OrbitControls(camera, renderer.domElement)
  controls.enableDamping = true
  controls.screenSpacePanning = true
  potree = new Potree()
  potree.pointBudget = pointBudget.value
  potree.maxNumNodesLoading = 4
  potreeRenderer = new PotreeRenderer({ edl: { enabled: false, pointCloudLayer: 1 } })
  resizeObserver = new ResizeObserver(resize)
  resizeObserver.observe(canvas.value)
  resize()
  renderLoop()
  await loadList()
}
watch([pointBudget, pointSize, edl, boundingBox], applySettings)
watch([colorMode, solidColor, colorControls], () => {
  if (colorUpdateFrame) cancelAnimationFrame(colorUpdateFrame)
  colorUpdateFrame = requestAnimationFrame(() => {
    colorUpdateFrame = undefined
    applyColorSettings()
  })
}, { deep: true })
watch(background, updateBackground)
onMounted(() => {
  document.addEventListener('fullscreenchange', fullscreenChanged)
  init()
})
onBeforeUnmount(() => {
  requestId += 1
  abortController?.abort()
  cancelAnimationFrame(raf)
  resizeObserver?.disconnect()
  clearCloud()
  controls?.dispose()
  potreeRenderer?.dispose()
  renderer?.dispose()
  renderer?.domElement?.remove()
  document.removeEventListener('fullscreenchange', fullscreenChanged)
})
</script>

<template>
  <div class="heritage-shell pointcloud-shell">
    <aside class="heritage-spine" aria-label="天坛数字遗产">
      <RouterLink class="seal" to="/" aria-label="返回首页">天<br />坛</RouterLink>
      <p>让遗产在数字中延续</p>
      <span>TIANTAN<br />1530 — 2026</span>
    </aside>
    <div class="heritage-body">
      <header class="global-top">
        <RouterLink class="brand" to="/"><strong>天坛 · 数字遗产</strong><small>TEMPLE OF HEAVEN</small></RouterLink>
        <nav aria-label="主导航"><RouterLink v-for="item in navigation" :key="item[1]" :to="item[1]" class="nav-link" :class="{ active: item[1] === '/pointcloud' }">{{ item[0] }}</RouterLink></nav>
        <button class="help-button" type="button" title="点云数据来自后端服务" aria-label="数据来源说明">?</button>
      </header>
      <main class="main-content pointcloud-content">
        <section class="pointcloud-intro">
          <div><p class="pointcloud-kicker">POINTCLOUD / EXPLORER</p><h1>从每一个点，发现建筑。</h1><p>以点云重建记录建筑的真实尺度，让空间在数据中再次显现。</p></div>
          <span class="intro-index">03 / 07</span>
        </section>
        <div class="pointcloud-workspace-toolbar">
          <span class="workspace-label">点云工作空间</span>
          <div class="workspace-actions">
            <button type="button" :class="{ active: showControls }" @click="showControls = !showControls">☷ <span>场景控制</span></button>
            <button type="button" :class="{ active: showStats }" @click="showStats = !showStats">⌁ <span>实时信息</span></button>
            <button type="button" :class="{ active: showBackground }" @click="showBackground = !showBackground">◌ <span>背景色</span></button>
          </div>
          <span class="workspace-status">{{ cloud ? '已加载 1 项' : '未加载资源' }}</span>
        </div>
        <div v-if="error && !renderError" class="pointcloud-error">{{ error }} <button type="button" @click="loadList">重试</button></div>
        <section class="pointcloud-layout">
          <aside class="dataset-panel">
            <header><div><p>COLLECTION</p><h2>点云数据集</h2></div><strong>{{ String(datasets.length).padStart(2, '0') }}</strong></header>
            <label class="resource-search"><span>⌕</span><input v-model="search" type="search" placeholder="搜索名称或编号" /></label>
            <div v-if="listLoading" class="resource-empty">正在读取数据目录…</div>
            <div v-else-if="!filteredDatasets.length" class="resource-empty"><b>+</b><span>{{ datasets.length ? '没有匹配的数据集' : '暂无点云资源' }}</span><small>请在数据管理中导入 Potree 2.0 数据</small></div>
            <div v-else class="resource-list">
              <article v-for="(item, index) in filteredDatasets" :key="item.id" :class="{ selected: String(item.id) === String(selectedId) }">
                <button class="resource-main" type="button" @click="loadDataset(item)"><span><strong>{{ item.name || '未命名点云' }}</strong><small>POTREE · {{ getPointCount(item) >= 100000000 ? (getPointCount(item) / 100000000).toFixed(2) : formatNumber(getPointCount(item)) }} 亿点</small></span></button>
                <button class="resource-action" type="button" :disabled="loading && String(item.id) !== String(selectedId)" @click="String(item.id) === String(selectedId) ? unload() : loadDataset(item)">{{ loading && String(item.id) === String(selectedId) ? '加载中' : String(item.id) === String(selectedId) && cloud ? '卸载' : '加载' }}</button>
              </article>
            </div>
            <RouterLink class="resource-manage" to="/manage">管理数据资源 <span>→</span></RouterLink>
          </aside>
          <section ref="stage" class="pointcloud-stage" :class="{ fullscreen }">
            <div ref="canvas" class="render-canvas"></div>
            <div class="stage-title"><i :class="{ ready: cloud }"></i><span><strong>{{ selected?.name || '等待选择点云' }}</strong><small>POINT CLOUD / {{ cloud ? 'LIVE' : '--' }}</small></span></div>
            <div class="stage-actions"><button type="button" :disabled="!cloud" @click="fitCamera" title="适配视角">⌂</button><button type="button" :disabled="!cloud" @click="zoom(1.2)" title="缩小">−</button><button type="button" :disabled="!cloud" @click="zoom(.8)" title="放大">＋</button><button type="button" @click="toggleFullscreen" title="全屏">{{ fullscreen ? '×' : '⛶' }}</button></div>
            <div v-if="loading" class="stage-message"><i></i><strong>正在建立点云索引</strong><span>根节点完成后将按视角流式加载。</span></div>
            <div v-if="renderError" class="stage-error"><strong>点云加载失败</strong><span>{{ renderError }}</span><button type="button" @click="selected && loadDataset(selected)">重新加载</button></div>
            <div class="stage-toolbar" aria-label="视图工具">
              <button type="button" :disabled="!cloud" @click="fitCamera" title="适配视角">◇</button>
              <button type="button" :disabled="!cloud" @click="zoom(1.2)" title="缩小">−</button>
              <button type="button" :disabled="!cloud" @click="zoom(.8)" title="放大">＋</button>
              <i></i>
              <button type="button" :disabled="!cloud" @click="fitCamera" title="重置视角">⊙</button>
              <i></i>
              <button type="button" @click="showControls = !showControls" title="场景控制">☷</button>
            </div>
            <footer class="stage-status"><span><i :class="{ ready: cloud }"></i>{{ statusText }}</span><span v-if="cloud">{{ formatNumber(visiblePoints) }} 可见点</span><span v-if="cloud">{{ Math.round(progress * 100) }}%</span><small>拖动旋转 · 右键平移 · 滚轮缩放</small></footer>
          </section>
          <aside class="settings-panel" v-show="showControls">
            <header><div><p>SCENE SETTINGS</p><h2>场景控制</h2></div><span class="settings-mark">☷</span></header>
            <section class="settings-section budget-section">
              <div class="section-title"><strong>调度预算</strong></div>
              <label class="range-row compact"><span>点预算 <b>{{ (pointBudget / 1000000).toFixed(1) }} M</b></span><input v-model.number="pointBudget" type="range" min="500000" max="12000000" step="500000" /></label>
              <div class="range-limits"><span>50 万</span><span>1,200 万</span></div>
            </section>
            <section class="settings-section color-section">
              <div class="section-title"><strong>点云颜色</strong></div>
              <div class="segmented"><button type="button" :class="{ active: colorMode === 'rgb' }" @click="colorMode = 'rgb'">原始 RGB</button><button type="button" :class="{ active: colorMode === 'solid' }" @click="colorMode = 'solid'">单色显示</button></div>
              <div class="swatch-row"><span>显示颜色</span><div><button v-for="color in ['#8bd7bd', '#d7c39d', '#84b8e3', '#d89572']" :key="color" type="button" class="color-swatch" :style="{ backgroundColor: color }" :class="{ selected: solidColor === color }" @click="solidColor = color; colorMode = 'solid'"></button></div></div>
              <label v-if="colorMode === 'solid'" class="color-row"><span>基础颜色</span><input v-model="solidColor" type="color" /></label>
              <label v-for="control in [['brightness','亮度',-1,1,.01],['contrast','对比度',-.9,.9,.01],['gamma','Gamma',.2,3,.01]]" :key="control[0]" class="range-row compact"><span>{{ control[1] }} <b>{{ control[0] === 'gamma' ? Math.round((colorControls[control[0]] - .2) / 2.8 * 100) : Math.round((colorControls[control[0]] - control[2]) / (control[3] - control[2]) * 100) }}</b></span><input v-model.number="colorControls[control[0]]" type="range" :min="control[2]" :max="control[3]" :step="control[4]" /></label>
            </section>
            <label class="range-row compact point-size-row"><span>点大小 <b>{{ Math.round((pointSize - .4) / 2.4 * 100) }}</b></span><input v-model.number="pointSize" type="range" min=".4" max="2.8" step=".1" /></label>
            <label class="switch-row"><span>节点包围盒</span><input v-model="boundingBox" type="checkbox" /><i class="switch-control" aria-hidden="true"></i></label>
            <label class="switch-row"><span>增强轮廓 · EDL</span><input v-model="edl" type="checkbox" /><i class="switch-control" aria-hidden="true"></i></label>
            <section v-show="showBackground" class="settings-section background-section">
              <div class="section-title"><strong>背景色</strong><small>视窗背景</small></div>
              <div class="background-value"><span>背景颜色</span><code>{{ background.toUpperCase() }}</code><input v-model="background" type="color" /></div>
              <div class="swatch-row background-swatches"><span></span><div><button v-for="color in ['#f4f0e7', '#21312d', '#1c2122', '#c9d7df']" :key="color" type="button" class="color-swatch" :style="{ backgroundColor: color }" :class="{ selected: background === color }" @click="background = color"></button></div></div>
            </section>
            <button class="reset-settings" type="button" @click="pointBudget = 2000000; pointSize = 1.25; edl = true; boundingBox = false; colorMode = 'rgb'; solidColor = '#ffffff'; colorControls.brightness = 0; colorControls.contrast = 0; colorControls.gamma = 1; background = '#21312d'">恢复默认设置 <span>←</span></button>
          </aside>
          <aside v-show="showStats" class="telemetry-panel">
            <header><div><h2>实时信息 <small>示例状态</small></h2></div><span>{{ cloud ? '场景已就绪' : '等待加载资源' }}</span></header>
            <dl>
              <div><dt>数据总点数</dt><dd>{{ formatNumber(pointCount) }}</dd></div>
              <div><dt>可见点</dt><dd>{{ cloud ? formatNumber(visiblePoints) : '—' }}</dd></div>
              <div><dt>节点载入</dt><dd>{{ cloud ? `${Math.round(progress * 100)}%` : '—' }}</dd></div>
              <div><dt>点预算</dt><dd>{{ (pointBudget / 1000000).toFixed(1) }} M</dd></div>
            </dl>
          </aside>
        </section>
        <footer class="pointcloud-footer"><span>01 / 点云工作空间</span><em>“古制象天，取法自然。” 在当代的数字记录中，延续古老建筑的秩序。</em><small>TIANTAN DIGITAL ARCHIVE</small></footer>
      </main>
    </div>
  </div>
</template>

<style scoped>
.pointcloud-content{width:100%;max-width:2275px;padding:22px 46px 30px;overflow-x:hidden}
.pointcloud-intro{display:flex;justify-content:space-between;gap:20px;padding-bottom:22px;border-bottom:1px solid var(--line)}
.pointcloud-kicker,.dataset-panel header p,.settings-panel header p,.telemetry-panel header p{color:var(--muted);font:8px/1.5 Consolas,monospace;letter-spacing:2px}
.pointcloud-intro h1{margin-top:15px;font:400 32px/1.4 var(--display);letter-spacing:3px}
.pointcloud-intro>div>p:last-child{margin-top:8px;color:var(--muted);font:11px/1.7 "Microsoft YaHei",sans-serif}
.intro-index{color:var(--muted);font:9px Consolas,monospace}
.pointcloud-workspace-toolbar{display:flex;align-items:center;justify-content:space-between;gap:16px;margin:0 0 20px;padding:0 0 16px;border-bottom:1px solid var(--line);flex-wrap:wrap}
.workspace-label{flex:0 0 auto;font:600 14px "Microsoft YaHei",sans-serif;color:var(--ink)}
.workspace-actions{display:flex;justify-content:center;gap:8px;flex:1;min-width:260px}
.workspace-actions button{display:inline-flex;align-items:center;gap:7px;min-height:34px;padding:6px 12px;border:1px solid var(--line);background:transparent;color:var(--muted);cursor:pointer;font:12px "Microsoft YaHei",sans-serif}
.workspace-actions button.active,.workspace-actions button:hover{border-color:#c9ab9b;color:var(--red);background:#eee5da}
.workspace-status{flex:0 0 auto;color:#516c5e;font:11px "Microsoft YaHei",sans-serif}
.pointcloud-layout{display:grid;grid-template-columns:224px minmax(0,1fr) 278px;grid-template-rows:minmax(500px,1fr) auto;gap:0 18px;align-items:stretch;min-width:0;min-height:645px;overflow:visible}
.dataset-panel{grid-column:1;grid-row:1 / 3;display:flex;flex-direction:column;min-width:0;padding-right:17px;border-right:1px solid var(--line)}
.dataset-panel header,.settings-panel header,.telemetry-panel header{display:flex;justify-content:space-between;gap:10px}
.dataset-panel h2,.settings-panel h2,.telemetry-panel h2{margin-top:8px;font:400 21px/1.4 var(--display);letter-spacing:1px}
.dataset-panel header>strong{padding:4px 6px;background:#eee5da;color:var(--red);font:9px Consolas,monospace}
.resource-search{display:flex;align-items:center;gap:7px;min-height:44px;margin:18px 0 12px;padding:0 11px;border:1px solid var(--line);color:var(--muted)}
.resource-search input{min-width:0;width:100%;border:0;outline:0;background:transparent;font:11px "Microsoft YaHei",sans-serif}
.resource-list{overflow:auto}
.resource-list article{display:flex;align-items:center;gap:9px;min-height:106px;margin:0;padding:14px 0;border-bottom:1px solid var(--line)}
.resource-list article:hover,.resource-list article.selected{background:transparent}
.resource-main{display:flex;align-items:center;min-width:0;flex:1;gap:12px;padding:0;border:0;background:transparent;text-align:left;cursor:pointer}
.resource-main span{min-width:0}.resource-main strong,.resource-main small{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.resource-main strong{font:11px/1.5 "Microsoft YaHei",sans-serif}.resource-main small{margin-top:6px;color:var(--muted);font:8px Consolas,monospace}
.resource-main strong{color:var(--red)}.resource-action{align-self:center;min-width:58px;padding:7px 9px;border:1px solid #eadfd0;background:#f0e4d8;color:var(--red);cursor:pointer;font:9px "Microsoft YaHei",sans-serif}.resource-action:hover{border-color:var(--red)}.resource-action:disabled{opacity:.45}
.resource-empty{display:flex;flex:1;min-height:160px;flex-direction:column;align-items:center;justify-content:center;gap:7px;color:var(--muted);text-align:center;font:10px/1.6 "Microsoft YaHei",sans-serif}.resource-empty b{font:24px Georgia,serif;font-weight:400;color:var(--red)}.resource-empty small{font-size:8px}
.resource-manage{display:flex;justify-content:space-between;padding-top:18px;color:var(--red);font:10px "Microsoft YaHei",sans-serif}
.pointcloud-stage{position:relative;grid-column:2;grid-row:1;width:100%;height:auto;min-height:500px;min-width:0;align-self:stretch;overflow:hidden;border:0;background:#21312d}
.pointcloud-stage:fullscreen{width:100vw;height:100vh;border:0}
.render-canvas{position:absolute;inset:0}.render-canvas :deep(canvas){display:block;width:100%;height:100%}
.stage-title{position:absolute;top:22px;left:20px;display:flex;align-items:center;gap:8px;padding:9px 12px;background:rgba(29,38,34,.72);border:1px solid rgba(247,244,236,.65);color:#f7f4ec;pointer-events:none}.stage-title i,.stage-status i{width:5px;height:5px;border-radius:50%;background:var(--muted)}.stage-title i.ready,.stage-status i.ready{background:#d77e69}.stage-title span{display:flex;flex-direction:column;gap:4px}.stage-title strong{max-width:240px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font:11px "Microsoft YaHei",sans-serif}.stage-title small{color:#9ca99d;font:8px Consolas,monospace}
.stage-actions{position:absolute;top:22px;right:20px;display:flex;gap:7px}.stage-actions button,.stage-toolbar button{width:36px;height:36px;border:1px solid var(--line);background:rgba(247,244,236,.95);color:var(--ink);cursor:pointer}.stage-actions button:hover,.stage-toolbar button:hover{border-color:var(--red);color:var(--red)}.stage-actions button:disabled,.stage-toolbar button:disabled{opacity:.4;cursor:default}
.stage-toolbar{position:absolute;left:50%;bottom:51px;display:flex;align-items:center;gap:2px;padding:7px 9px;transform:translateX(-50%);background:rgba(247,244,236,.96);border:1px solid var(--line)}.stage-toolbar button{width:30px;height:28px;border:0;background:transparent}.stage-toolbar i{height:20px;border-left:1px solid var(--line)}
.stage-message,.stage-error{position:absolute;top:50%;left:50%;display:flex;flex-direction:column;align-items:center;gap:9px;transform:translate(-50%,-50%);text-align:center}.stage-message{color:#d8e1d5}.stage-message i{width:18px;height:18px;border:2px solid rgba(255,255,255,.35);border-top-color:#fff;border-radius:50%;animation:spin 1s linear infinite}.stage-message strong{font:12px "Microsoft YaHei",sans-serif}.stage-message span,.stage-error span{font:9px "Microsoft YaHei",sans-serif}.stage-error{max-width:80%;padding:18px;background:rgba(247,244,236,.96);border:1px solid #c6816b;color:var(--red)}.stage-error button,.pointcloud-error button{border:0;background:transparent;color:var(--red);text-decoration:underline;cursor:pointer}
.stage-status{position:absolute;right:0;bottom:0;left:0;display:flex;align-items:center;gap:14px;min-height:40px;padding:0 15px;background:rgba(28,36,32,.94);border-top:1px solid rgba(247,244,236,.18);color:#b6c0b5;font:9px Consolas,monospace}.stage-status span:first-child{display:flex;align-items:center;gap:6px;color:#f2efe7}.stage-status small{margin-left:auto;font:9px "Microsoft YaHei",sans-serif}
.settings-panel{grid-column:3;grid-row:1 / 3;min-width:0;min-height:0;height:auto;align-self:stretch;overflow-y:auto;overflow-x:hidden;padding:0 0 0 17px;border-left:1px solid var(--line)}.settings-panel>header{align-items:flex-start;padding-bottom:16px;border-bottom:1px solid var(--line)}.settings-panel>header h2{font-size:23px}.settings-mark{padding-top:12px;color:var(--muted);font-size:18px}.settings-section{padding:18px 0;border-bottom:1px solid var(--line)}.section-title{display:flex;align-items:baseline;justify-content:space-between;gap:8px}.section-title strong{font-size:13px}.section-title small{color:var(--muted);font-size:9px}.range-row{display:block;color:var(--muted);font:10px "Microsoft YaHei",sans-serif}.range-row.compact{margin-top:13px}.range-row span{display:flex;justify-content:space-between}.range-row b{color:var(--ink);font:10px Consolas,monospace}.range-row input[type=range]{width:100%;height:18px;margin-top:7px;accent-color:var(--red)}.range-limits{display:flex;justify-content:space-between;color:var(--muted);font:9px "Microsoft YaHei",sans-serif}
.switch-row{position:relative;display:flex;justify-content:space-between;align-items:center;margin-top:0;padding:12px 0;border-bottom:1px solid var(--line);color:var(--muted);font:10px "Microsoft YaHei",sans-serif;cursor:pointer}.switch-row input{position:absolute;width:1px;height:1px;opacity:0}.switch-control{position:relative;display:block;width:30px;height:16px;border:1px solid #b8b2a8;border-radius:9px;background:#ddd8cf;transition:.2s}.switch-control::after{content:"";position:absolute;top:2px;left:2px;width:10px;height:10px;border-radius:50%;background:#fff;box-shadow:0 1px 2px rgba(0,0,0,.18);transition:.2s}.switch-row input:checked+.switch-control{border-color:var(--red);background:var(--red)}.switch-row input:checked+.switch-control::after{transform:translateX(14px)}.switch-row input:focus-visible+.switch-control{outline:2px solid #c9ab9b;outline-offset:2px}.color-section{margin-top:0;padding-top:15px}.segmented{display:flex;margin-top:10px}.segmented button{flex:1;padding:7px 3px;border:1px solid var(--line);background:transparent;color:var(--muted);font:9px "Microsoft YaHei",sans-serif;cursor:pointer}.segmented button+button{border-left:0}.segmented button.active{background:#eee5da;color:var(--red);border-color:#c9ab9b}.swatch-row,.background-value{display:flex;align-items:center;justify-content:space-between;gap:10px;margin-top:13px;color:var(--muted);font:10px "Microsoft YaHei",sans-serif}.swatch-row>div{display:flex;gap:9px}.color-swatch{width:18px;height:18px;padding:0;border:2px solid var(--paper-soft);border-radius:50%;outline:1px solid var(--line);cursor:pointer}.color-swatch.selected{outline:2px solid var(--red);outline-offset:1px}.color-row{display:flex;align-items:center;justify-content:space-between;margin-top:10px;color:var(--muted);font:10px "Microsoft YaHei",sans-serif}.color-row input{width:32px;height:21px;padding:0;border:0;background:none}.point-size-row{padding:14px 0 0}.background-section{padding-bottom:14px}.background-value{margin-top:12px}.background-value code{margin-left:auto;color:var(--muted);font:9px Consolas,monospace}.background-value input{width:32px;height:24px;padding:0;border:1px solid var(--line);background:transparent}.background-swatches{margin-top:10px}.reset-settings{display:flex;justify-content:space-between;width:100%;padding:12px 0;border:0;background:transparent;color:var(--red);text-align:left;font:10px "Microsoft YaHei",sans-serif;cursor:pointer}
.telemetry-panel{position:relative;grid-column:2;grid-row:2;min-width:0;min-height:0;margin:0;padding:14px 18px;border:1px solid var(--line);border-top:0;background:var(--paper-soft)}.telemetry-panel header{display:flex;align-items:baseline;justify-content:space-between;margin-bottom:12px}.telemetry-panel header h2{margin:0;font-family:"Microsoft YaHei",sans-serif;font-size:16px;font-weight:600}.telemetry-panel header h2 small{margin-left:9px;color:var(--muted);font-size:10px;font-weight:400}.telemetry-panel header>span{color:var(--muted);font:9px "Microsoft YaHei",sans-serif}.telemetry-panel dl{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px;margin:0}.telemetry-panel dl div{padding:0 20px;border-top:0;border-right:1px solid var(--line)}.telemetry-panel dl div:first-child{padding-left:0}.telemetry-panel dl div:last-child{border-right:0}.telemetry-panel dt{color:var(--muted);font:9px "Microsoft YaHei",sans-serif}.telemetry-panel dd{margin:0;font:17px/1.5 Consolas,monospace}
.pointcloud-footer{display:grid;grid-template-columns:180px 1fr auto;gap:20px;margin-top:28px;padding:22px 0;border-top:1px solid var(--line);color:var(--muted);font:9px "Microsoft YaHei",sans-serif}.pointcloud-footer em{font-style:normal}.pointcloud-footer small{font:8px Consolas,monospace;letter-spacing:1px}
@keyframes spin{to{transform:translate(-50%,-50%) rotate(360deg)}}
@media(max-width:1350px){.pointcloud-content{padding-right:28px;padding-left:28px}.pointcloud-layout{grid-template-columns:188px minmax(0,1fr) 252px;gap:0 14px}.dataset-panel{padding-right:12px}.settings-panel{padding-left:12px}.pointcloud-stage{height:auto;min-height:500px}}
@media(max-width:850px){.pointcloud-content{padding:20px 22px 30px}.pointcloud-workspace-toolbar{align-items:flex-start}.workspace-actions{order:3;flex-basis:100%;justify-content:flex-start}.pointcloud-layout{grid-template-columns:150px minmax(0,1fr);grid-template-rows:auto auto auto;gap:0 11px}.dataset-panel{grid-column:1;grid-row:1 / 3;padding-right:8px}.pointcloud-stage{grid-column:2;grid-row:1;height:520px}.telemetry-panel{grid-column:2;grid-row:2;padding:13px}.settings-panel{grid-column:1 / 3;grid-row:3;height:auto;padding:17px 0 0;border-top:1px solid var(--line);border-left:0}.pointcloud-footer{grid-template-columns:1fr auto}.pointcloud-footer em{grid-column:1/-1;grid-row:2}.pointcloud-intro h1{font-size:25px}}
@media(max-width:600px){.pointcloud-intro{display:block}.pointcloud-intro h1{font-size:23px;letter-spacing:1px}.intro-index{display:block;margin-top:12px}.pointcloud-layout{grid-template-columns:102px minmax(0,1fr)}.resource-main{gap:5px}.resource-main strong{font-size:9px;white-space:normal}.resource-main small{font-size:7px}.resource-action{min-width:35px;padding:6px 3px;font-size:8px}.pointcloud-stage{min-height:500px}.stage-title{top:8px;left:7px}.stage-actions{top:8px;right:7px}.stage-actions button{width:27px;height:27px}.stage-toolbar{bottom:48px}.telemetry-panel{padding:15px 10px}.telemetry-panel dl{grid-template-columns:1fr 1fr;gap:14px 0}.telemetry-panel dl div:nth-child(2){border-right:0}.telemetry-panel dd{font-size:15px}.pointcloud-footer{display:block}.pointcloud-footer em,.pointcloud-footer small{display:block;margin-top:10px}}
</style>
