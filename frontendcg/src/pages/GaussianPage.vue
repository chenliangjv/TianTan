<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import IconMark from '../components/IconMark.vue'

const navigation = [
  ['首页', '/'], ['模型展示', '/model'], ['点云展示', '/pointcloud'],
  ['高斯渲染', '/gaussian'], ['设计图预览', '/design'], ['数据管理', '/manage'], ['信息统计', '/stats'],
]
const API_BASE = (import.meta.env.VITE_API_BASE_URL || '').replace(/\/$/, '')
const iframeUrl = `${import.meta.env.BASE_URL}gaussian-viewer/index.html`
const route = useRoute()
const router = useRouter()

const scenes = ref([])
const selectedId = ref(null)
const query = ref('')
const loading = ref(false)
const error = ref('')
const renderError = ref('')
const skyError = ref('')
const frame = ref(null)
const viewport = ref(null)
const generation = ref(0)
const status = ref('empty')
const sky = ref(false)
const skyStatus = ref('off')
const settings = ref(false)
const quality = ref('standard')
const speed = ref(1)
const background = ref('#cfd9cd')
const fullscreen = ref(false)
const stats = ref({ fps: 0, splats: 0, pages: 0 })
let controller
let timeout
let disposed = false

const filteredScenes = computed(() => {
  const text = query.value.trim().toLowerCase()
  if (!text) return scenes.value
  return scenes.value.filter(item => `${item.name} ${item.status}`.toLowerCase().includes(text))
})
const active = computed(() => scenes.value.find(item => String(item.id) === String(selectedId.value)) || null)
const statusText = computed(() => ({
  empty: '等待选择场景',
  booting: '正在准备渲染窗口',
  loading: '正在读取场景',
  indexed: '正在加载首屏细节',
  ready: '实时渲染',
  error: '场景加载失败',
}[status.value] || '等待选择场景'))
const activeIndex = computed(() => Math.max(1, scenes.value.findIndex(item => item.id === active.value?.id) + 1))
const formattedSplats = computed(() => Number(active.value?.sourceSplatCount || 0).toLocaleString('zh-CN'))
const formattedSize = computed(() => {
  const bytes = Number(active.value?.totalSize || 0)
  if (!bytes) return '—'
  if (bytes >= 1024 ** 3) return `${(bytes / 1024 ** 3).toFixed(1)} GB`
  if (bytes >= 1024 ** 2) return `${(bytes / 1024 ** 2).toFixed(1)} MB`
  return `${Math.max(1, Math.round(bytes / 1024))} KB`
})
const dateText = computed(() => {
  if (!active.value?.createdAt) return '—'
  const date = new Date(active.value.createdAt)
  return Number.isNaN(date.getTime()) ? String(active.value.createdAt).replace('T', ' ').slice(0, 16) : new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit',
  }).format(date)
})

function assetUrl(path) {
  if (!path) return ''
  return /^https?:\/\//i.test(path) ? path : `${API_BASE}${path}`
}
function normalize(item) {
  return {
    ...item,
    name: item.name || '未命名高斯场景',
    totalSize: Number(item.totalSize || 0),
    chunkCount: Number(item.chunkCount || 0),
    sourceSplatCount: Number(item.sourceSplatCount || item.totalSplatCount || 0),
    hasSky: Boolean(item.hasSky || item.skyUrl),
    skyDefaultEnabled: item.skyDefaultEnabled !== false,
    mainUrl: assetUrl(item.mainUrl || `/api/data/gaussians/${item.id}/files/scene.rad`),
    skyUrl: assetUrl(item.skyUrl || (item.hasSky ? `/api/data/gaussians/${item.id}/files/environment.ply` : '')),
  }
}
function send(type, payload = {}) {
  const message = JSON.parse(JSON.stringify({ channel: 'tiantan-gaussian', type, ...payload }))
  frame.value?.contentWindow?.postMessage(message, window.location.origin)
}
function unload() {
  generation.value += 1
  clearTimeout(timeout)
  send('dispose')
  selectedId.value = null
  renderError.value = ''
  skyError.value = ''
  status.value = 'empty'
  stats.value = { fps: 0, splats: 0, pages: 0 }
}
async function loadScene(scene) {
  unload()
  selectedId.value = scene.id
  sky.value = scene.skyDefaultEnabled
  skyStatus.value = scene.hasSky ? 'loading' : 'off'
  status.value = 'booting'
  router.replace({ query: { ...route.query, scene: scene.id } })
  const current = generation.value
  await nextTick()
  if (disposed || current !== generation.value) return
  timeout = window.setTimeout(() => {
    if (status.value === 'booting') {
      status.value = 'error'
      renderError.value = '渲染窗口未能启动，请刷新后重试。'
    }
  }, 30000)
}
function receive(event) {
  if (event.origin !== window.location.origin || event.source !== frame.value?.contentWindow || event.data?.channel !== 'tiantan-gaussian' || !active.value) return
  const data = event.data
  if (data.type === 'ready') {
    clearTimeout(timeout)
    status.value = 'loading'
    send('load', { model: active.value, sky: sky.value, quality: quality.value })
  }
  if (data.type === 'indexed') {
    status.value = 'indexed'
    send('background', { color: background.value })
    send('speed', { value: speed.value })
  }
  if (data.type === 'loaded') status.value = 'ready'
  if (data.type === 'error') {
    renderError.value = data.message || '场景渲染失败'
    status.value = 'error'
  }
  if (data.type === 'sky') {
    skyStatus.value = data.status
    skyError.value = data.status === 'error' ? data.message : ''
  }
  if (data.type === 'stats') stats.value = { fps: data.fps || 0, splats: data.splats || 0, pages: data.pages || 0 }
}
async function refresh(first = false) {
  controller?.abort()
  const request = new AbortController()
  controller = request
  loading.value = true
  error.value = ''
  try {
    const response = await fetch(`${API_BASE}/api/data/gaussians/list`, { signal: request.signal, cache: 'no-store' })
    const body = await response.json().catch(() => null)
    if (!response.ok) throw new Error(body?.message || `高斯场景服务返回 ${response.status}`)
    const values = Array.isArray(body) ? body : body?.data || body?.items
    if (!Array.isArray(values)) throw new Error('高斯场景列表响应格式不正确')
    scenes.value = values.map(normalize)
    if (first && route.query.scene) {
      const target = scenes.value.find(item => String(item.id) === String(route.query.scene))
      if (target) loadScene(target)
    }
  } catch (cause) {
    if (cause.name !== 'AbortError') error.value = cause.message || '高斯场景读取失败'
  } finally {
    if (controller === request) loading.value = false
  }
}
function retry() {
  if (active.value) loadScene(active.value)
  else refresh(false)
}
function toggleSky() {
  if (!active.value?.hasSky) return
  sky.value = !sky.value
  send('sky', { enabled: sky.value })
}
async function toggleFullscreen() {
  try {
    if (document.fullscreenElement) await document.exitFullscreen()
    else await viewport.value?.requestFullscreen()
  } catch {
    renderError.value = '浏览器未允许全屏，请检查页面权限。'
  }
}
function fullscreenChanged() {
  fullscreen.value = document.fullscreenElement === viewport.value
}
function changeQuality() {
  if (active.value) loadScene(active.value)
}
onMounted(() => {
  window.addEventListener('message', receive)
  document.addEventListener('fullscreenchange', fullscreenChanged)
  refresh(true)
})
onBeforeUnmount(() => {
  disposed = true
  unload()
  controller?.abort()
  window.removeEventListener('message', receive)
  document.removeEventListener('fullscreenchange', fullscreenChanged)
})
</script>

<template>
  <div class="heritage-shell gaussian-shell">
    <aside class="heritage-spine" aria-label="天坛数字遗产">
      <RouterLink class="seal" to="/" aria-label="返回首页">天<br />坛</RouterLink>
      <p>让遗产在数字中延续</p>
      <span>TIANTAN<br />1530 — 2026</span>
    </aside>
    <div class="heritage-body">
      <header class="global-top">
        <RouterLink class="brand" to="/"><strong>天坛 · 数字遗产</strong><small>TEMPLE OF HEAVEN</small></RouterLink>
        <nav aria-label="主导航">
          <RouterLink v-for="item in navigation" :key="item[1]" :to="item[1]" class="nav-link" :class="{ active: item[1] === '/gaussian' }">{{ item[0] }}</RouterLink>
        </nav>
        <button class="help-button" type="button" title="高斯场景来自数据服务" aria-label="数据来源说明"><IconMark name="help" :size="16" /></button>
      </header>

      <main class="main-content gaussian-content">
        <section class="gaussian-intro">
          <div>
            <p class="gaussian-kicker">GAUSSIAN FIELD / 003</p>
            <h1>在光影中，重返现场。</h1>
            <p>以高斯实景重建建筑空间，让每一束光线、每一处细节都保留现场的真实质感。</p>
          </div>
          <RouterLink class="gaussian-action" to="/manage">管理场景 <span aria-hidden="true">→</span></RouterLink>
        </section>

        <div v-if="error" class="gaussian-error" role="alert">场景数据加载失败：{{ error }} <button type="button" @click="refresh(false)">重试</button></div>
        <div v-if="loading && !scenes.length" class="gaussian-loading">正在读取高斯场景档案…</div>

        <section v-else class="gaussian-layout">
          <aside class="gaussian-catalog">
            <div class="catalog-heading">
              <div><p>SCENE INDEX</p><h2>高斯场景</h2></div>
              <span>{{ String(scenes.length).padStart(2, '0') }}</span>
            </div>
            <label class="scene-search"><span>⌕</span><input v-model="query" type="search" placeholder="搜索场景" /></label>
            <div v-if="!filteredScenes.length" class="scene-empty">{{ scenes.length ? '没有匹配的场景' : '暂无高斯场景' }}</div>
            <div v-else class="scene-list">
              <article v-for="(scene, index) in filteredScenes" :key="scene.id" class="scene-card" :class="{ active: String(scene.id) === String(selectedId) }">
                <div class="scene-card-head">
                  <button class="scene-name" type="button" @click="loadScene(scene)">
                    <small>{{ String(index + 1).padStart(2, '0') }}</small>
                    <strong>{{ scene.name }}</strong>
                  </button>
                  <button class="load-button" type="button" @click="String(scene.id) === String(selectedId) ? unload() : loadScene(scene)">{{ String(scene.id) === String(selectedId) ? '卸载场景' : '加载场景' }} <span>{{ String(scene.id) === String(selectedId) ? '×' : '→' }}</span></button>
                </div>
                <div class="scene-meta">
                  <span>{{ scene.hasSky ? '含天空' : '不含天空' }}</span>
                  <span>{{ formattedSizeFor(scene.totalSize) }}</span>
                  <span>{{ scene.chunkCount || '—' }} 块</span>
                </div>
              </article>
            </div>
            <RouterLink class="catalog-manage" to="/manage">管理场景档案 <span aria-hidden="true">→</span></RouterLink>
          </aside>

          <section ref="viewport" class="gaussian-viewport" :class="{ 'sky-off': active && !sky }">
            <iframe v-if="active" :key="generation" ref="frame" :src="iframeUrl" title="高斯场景实时渲染" allow="fullscreen" />
            <div v-else class="empty-stage"><span class="scene-emblem"><IconMark name="gaussian" :size="38" /></span><p>SCENE EXPLORER</p><h2>从一个场景开始</h2><span>选择左侧场景，进入可自由浏览的实景空间。</span></div>
            <div v-if="active" class="viewport-title"><strong>{{ active.name }}</strong><small>{{ formattedSplats }} 个原始高斯 · {{ statusText }}</small></div>
            <div class="viewport-toolbar">
              <button type="button" :class="{ selected: sky }" :disabled="!active?.hasSky" title="天空显示" @click="toggleSky">天空 {{ sky ? '开' : '关' }}</button>
              <button type="button" :disabled="!active" title="视角复位" @click="send('reset')">适配</button>
              <button type="button" :class="{ selected: settings }" title="显示设置" @click="settings = !settings">设置</button>
              <button type="button" title="全屏" @click="toggleFullscreen">{{ fullscreen ? '退出' : '全屏' }}</button>
            </div>
            <div v-if="settings" class="settings-panel">
              <h3>显示设置</h3>
              <label>渲染质量<select v-model="quality" @change="changeQuality"><option value="standard">标准 · 100 万点</option><option value="low">流畅 · 50 万点</option></select></label>
              <label>漫游速度 <span>{{ speed }}×</span><input v-model.number="speed" type="range" min="0.2" max="3" step="0.2" @input="send('speed', { value: speed })" /></label>
              <label>背景颜色 <input v-model="background" type="color" @input="send('background', { color: background })" /></label>
            </div>
            <div v-if="active && ['booting', 'loading', 'indexed'].includes(status)" class="render-loading"><i></i>{{ statusText }}</div>
            <div v-if="renderError" class="render-error" role="alert"><strong>{{ renderError }}</strong><button type="button" @click="retry">重新加载</button></div>
            <div v-if="skyError" class="sky-error">天空暂不可用：{{ skyError }} <button type="button" @click="send('sky', { enabled: true })">重试</button></div>
            <footer class="status-bar"><span><i :class="{ ready: status === 'ready' }"></i>{{ statusText }}</span><span v-if="active">{{ stats.fps || '—' }} FPS</span><span v-if="active">{{ stats.splats.toLocaleString('zh-CN') }} 点</span><span v-if="active">{{ stats.pages }} 页</span><small>拖动旋转 · 右键平移 · 滚轮缩放</small></footer>
          </section>

          <aside class="gaussian-info">
            <div class="info-heading"><p>SCENE RECORD</p><h2>场景档案</h2></div>
            <dl v-if="active" class="scene-metadata">
              <div><dt>场景名称</dt><dd>{{ active.name }}</dd></div>
              <div><dt>档案编号</dt><dd>GAUSSIAN-{{ String(active.id).padStart(3, '0') }}</dd></div>
              <div><dt>场景序号</dt><dd>{{ String(activeIndex).padStart(2, '0') }} / {{ String(scenes.length).padStart(2, '0') }}</dd></div>
              <div><dt>原始高斯</dt><dd>{{ formattedSplats }}</dd></div>
              <div><dt>数据规模</dt><dd>{{ formattedSize }}</dd></div>
              <div><dt>数据分块</dt><dd>{{ active.chunkCount || '—' }} 块</dd></div>
              <div><dt>天空环境</dt><dd>{{ active.hasSky ? (skyStatus === 'error' ? '加载失败' : sky ? '已开启' : '已关闭') : '未提供' }}</dd></div>
              <div><dt>入库日期</dt><dd>{{ dateText }}</dd></div>
            </dl>
            <p v-if="active" class="gaussian-note">让空间重新获得时间，让光影回到建筑之中。</p>
            <p v-else class="gaussian-caption">选择一个高斯场景，开始阅读建筑的空间记忆。</p>
          </aside>
        </section>
      </main>
    </div>
  </div>
</template>

<script>
export default {
  methods: {
    formattedSizeFor(bytes) {
      const value = Number(bytes || 0)
      if (!value) return '—'
      if (value >= 1024 ** 3) return `${(value / 1024 ** 3).toFixed(1)} GB`
      if (value >= 1024 ** 2) return `${(value / 1024 ** 2).toFixed(1)} MB`
      return `${Math.max(1, Math.round(value / 1024))} KB`
    },
  },
}
</script>

<style scoped>
.gaussian-content{max-width:1900px;padding-top:30px;padding-bottom:40px}
.gaussian-intro{display:flex;align-items:flex-end;justify-content:space-between;gap:25px;padding:0 0 25px;border-bottom:1px solid var(--line)}
.gaussian-kicker,.catalog-heading p,.info-heading p{color:var(--muted);font:8px/1.5 Consolas,monospace;letter-spacing:2.2px}
.gaussian-intro h1{margin-top:16px;font:400 31px/1.45 var(--display);letter-spacing:3px}
.gaussian-intro>div>p:last-child{margin-top:9px;color:var(--muted);font:11px/1.7 Microsoft YaHei,sans-serif}
.gaussian-action{min-height:38px;display:inline-flex;align-items:center;gap:12px;padding:0 15px;background:var(--red);color:#fff;font:11px Microsoft YaHei,sans-serif}
.gaussian-action:hover{background:var(--red-dark)}
.gaussian-error{display:flex;align-items:center;gap:12px;margin-top:18px;padding:11px 14px;border:1px solid #c6816b;color:var(--red);font:11px Microsoft YaHei,sans-serif}
.gaussian-error button,.render-error button,.sky-error button{border:0;background:transparent;color:var(--red);text-decoration:underline;cursor:pointer}
.gaussian-loading{padding:130px 0;text-align:center;color:var(--muted);font:12px Microsoft YaHei,sans-serif}
.gaussian-layout{display:grid;grid-template-columns:224px minmax(0,1fr) 210px;min-height:700px;gap:20px;margin-top:24px}
.gaussian-catalog{display:flex;flex-direction:column;min-width:0;padding-right:17px;border-right:1px solid var(--line)}
.catalog-heading,.info-heading{display:flex;justify-content:space-between;align-items:flex-start;gap:12px}
.catalog-heading h2,.info-heading h2{margin-top:8px;font:400 18px/1.4 var(--display);letter-spacing:1px}
.catalog-heading>span{padding:4px 6px;color:var(--red);background:#eee5da;font:9px Consolas,monospace}
.scene-search{display:flex;align-items:center;gap:7px;min-height:34px;margin:17px 0 10px;padding:0 8px;border:1px solid var(--line);color:var(--muted)}
.scene-search input{width:100%;min-width:0;border:0;outline:0;background:transparent;color:var(--ink);font:10px Microsoft YaHei,sans-serif}
.scene-list{display:flex;flex-direction:column;gap:11px}
.scene-card{padding:8px;border:1px solid transparent;background:transparent}
.scene-card:hover,.scene-card.active{border-color:#c9ab9b;background:#f8f3ea}
.scene-card-head{display:flex;align-items:center;justify-content:space-between;gap:8px;min-width:0}
.scene-name{display:flex;align-items:center;gap:7px;min-width:0;padding:0;border:0;background:transparent;text-align:left;cursor:pointer}
.scene-name small{flex:0 0 auto;color:var(--muted);font:9px Consolas,monospace}
.scene-name strong{display:block;min-width:0;margin:0;overflow:hidden;font:11px/1.5 Microsoft YaHei,sans-serif;text-overflow:ellipsis;white-space:nowrap}
.scene-meta{display:flex;align-items:center;gap:8px;margin-top:5px;color:var(--muted);font:8px/1.4 Consolas,"Microsoft YaHei",sans-serif}
.scene-meta span+span:before{content:"·";margin-right:8px;color:#b7a798}
.load-button{display:flex;align-items:center;justify-content:space-between;flex:0 0 auto;width:auto;min-height:27px;margin:0;padding:0 8px;border:1px solid var(--line);background:transparent;color:var(--red);cursor:pointer;font:10px Microsoft YaHei,sans-serif;white-space:nowrap}
.load-button:hover{border-color:var(--red);background:#eee5da}
.scene-empty{padding:28px 0;color:var(--muted);font:10px/1.6 Microsoft YaHei,sans-serif}
.catalog-manage{display:flex;gap:9px;align-items:center;margin-top:auto;padding-top:20px;color:var(--red);font:10px Microsoft YaHei,sans-serif}
.gaussian-viewport{position:relative;min-width:0;min-height:700px;overflow:hidden;background:#cfd9cd;border:1px solid var(--line)}
.gaussian-viewport.sky-off{background:#e8e0d1}
.gaussian-viewport:fullscreen{width:100vw;height:100vh;border:0}
.gaussian-viewport iframe{position:absolute;inset:0;width:100%;height:100%;border:0}
.empty-stage{position:absolute;inset:0;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:14px;background:linear-gradient(90deg,rgba(255,255,255,.18) 1px,transparent 1px),linear-gradient(rgba(255,255,255,.18) 1px,transparent 1px);background-size:42px 42px;color:var(--muted)}
.scene-emblem{display:grid;place-items:center;width:86px;height:86px;border:1px solid #c9ab9b;background:#eee8dc;color:var(--red)}
.empty-stage p{font:9px Consolas,monospace;letter-spacing:3px}
.empty-stage h2{color:var(--ink);font:400 22px var(--display);letter-spacing:2px}
.empty-stage>span:last-child{font:11px Microsoft YaHei,sans-serif}
.viewport-title{position:absolute;top:18px;left:16px;display:flex;flex-direction:column;gap:5px;max-width:calc(100% - 260px);padding:7px 10px;background:#f7f4ece6;border:1px solid var(--line);pointer-events:none}
.viewport-title strong{overflow:hidden;font:11px Microsoft YaHei,sans-serif;text-overflow:ellipsis;white-space:nowrap}
.viewport-title small{color:var(--muted);font:8px Consolas,monospace}
.viewport-toolbar{position:absolute;top:18px;right:14px;display:flex;gap:7px;z-index:3}
.viewport-toolbar button{min-height:30px;padding:0 9px;border:1px solid var(--line);background:#f7f4eceb;color:var(--ink);cursor:pointer;font:10px Microsoft YaHei,sans-serif}
.viewport-toolbar button:hover,.viewport-toolbar button.selected{border-color:var(--red);color:var(--red)}
.viewport-toolbar button:disabled{opacity:.45;cursor:default}
.settings-panel{position:absolute;top:58px;right:14px;z-index:4;width:210px;padding:15px;border:1px solid var(--line);background:#f7f4ecfa;box-shadow:0 8px 24px #4635261f;font:10px Microsoft YaHei,sans-serif}
.settings-panel h3{margin-bottom:15px;font:400 16px var(--display)}
.settings-panel label{display:flex;justify-content:space-between;flex-wrap:wrap;gap:8px;margin-bottom:15px;color:var(--muted)}
.settings-panel select{width:100%;padding:6px;border:1px solid var(--line);background:var(--paper);color:var(--ink);font:10px inherit}
.settings-panel input[type=range]{width:100%;accent-color:var(--red)}
.settings-panel input[type=color]{width:36px;height:22px;padding:0;border:0;background:none}
.render-loading{position:absolute;left:50%;top:50%;display:flex;align-items:center;gap:9px;transform:translate(-50%,-50%);color:var(--ink);font:11px Microsoft YaHei,sans-serif}
.render-loading i{width:15px;height:15px;border:2px solid #c9ab9b;border-top-color:var(--red);border-radius:50%;animation:spin-gaussian 1s linear infinite}
.render-error{position:absolute;top:38%;left:10%;right:10%;display:flex;flex-direction:column;align-items:center;gap:12px;padding:18px;text-align:center;background:#f7f4ecf5;border:1px solid #c6816b;color:var(--red);font:11px/1.6 Microsoft YaHei,sans-serif}
.sky-error{position:absolute;right:14px;bottom:50px;padding:8px 10px;background:#f7f4ecf2;color:var(--red);font:10px Microsoft YaHei,sans-serif}
.status-bar{position:absolute;right:0;bottom:0;left:0;display:flex;align-items:center;gap:15px;min-height:36px;padding:0 12px;border-top:1px solid var(--line);background:#f7f4ecf0;color:var(--muted);font:9px Consolas,monospace;pointer-events:none}
.status-bar span:first-child{display:flex;align-items:center;gap:6px;color:var(--ink)}
.status-bar i{width:5px;height:5px;border-radius:50%;background:var(--muted)}
.status-bar i.ready{background:#668b72;box-shadow:0 0 6px #668b72}
.status-bar small{margin-left:auto;font:9px Microsoft YaHei,sans-serif}
.gaussian-info{min-width:0;padding-left:17px;border-left:1px solid var(--line)}
.scene-metadata{margin:16px 0 0}
.scene-metadata>div{padding:12px 0;border-top:1px solid var(--line)}
.scene-metadata dt{color:var(--muted);font:9px Microsoft YaHei,sans-serif}
.scene-metadata dd{margin:6px 0 0;overflow-wrap:anywhere;font:11px/1.5 Microsoft YaHei,sans-serif}
.gaussian-note{margin-top:27px;color:var(--red);font:18px/1.7 var(--display)}
.gaussian-caption{margin-top:22px;padding-top:15px;border-top:1px solid var(--line);color:var(--muted);font:9px/1.7 Microsoft YaHei,sans-serif}
@keyframes spin-gaussian{to{transform:translate(-50%,-50%) rotate(360deg)}}
@media(max-width:1300px){
  .gaussian-content{padding-left:28px;padding-right:28px}
  .gaussian-layout{grid-template-columns:190px minmax(0,1fr) 175px;gap:15px}
  .gaussian-info{padding-left:13px}
}
@media(max-width:1050px){
  .gaussian-layout{grid-template-columns:190px minmax(0,1fr);grid-template-rows:minmax(640px,auto) auto}
  .gaussian-info{grid-column:2;grid-row:2;padding:20px 0 0;border-left:0;border-top:1px solid var(--line)}
  .scene-metadata{display:grid;grid-template-columns:1fr 1fr;gap:0 15px}
}
@media(max-width:700px){
  .gaussian-intro{display:block}
  .gaussian-intro h1{font-size:24px;letter-spacing:1px}
  .gaussian-action{margin-top:17px}
  .gaussian-layout{grid-template-columns:98px minmax(0,1fr);gap:10px;min-height:540px}
  .gaussian-catalog{padding-right:8px}
  .catalog-heading h2{font-size:15px}
  .scene-search{padding:0 5px}
  .scene-search span{display:none}
  .scene-search input{font-size:9px}
  .scene-card{padding:6px 4px}
  .scene-card-head{gap:4px}
  .scene-name{gap:4px}
  .scene-name strong{font-size:8px}
  .scene-name small{font-size:7px}
  .load-button{min-height:25px;flex:0 0 auto;padding:0 4px;font-size:7px}
  .scene-meta{gap:2px 5px;font-size:6px}
  .scene-meta span+span:before{margin-right:5px}
  .gaussian-viewport{grid-column:2;grid-row:1;min-height:540px}
  .viewport-toolbar{top:8px;right:7px;gap:3px}
  .viewport-toolbar button{min-height:27px;padding:0 5px;font-size:8px}
  .viewport-title{top:50px;left:7px;max-width:calc(100% - 14px)}
  .status-bar{gap:7px;padding:0 7px}
  .status-bar small{display:none}
  .gaussian-info{grid-column:1/-1;grid-row:2;padding:16px 0 0}
  .scene-metadata{grid-template-columns:1fr 1fr}
  .gaussian-note{display:none}
}
</style>
