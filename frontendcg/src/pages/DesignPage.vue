<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import IconMark from '../components/IconMark.vue'

const navigation = [
  ['首页', '/'], ['模型展示', '/model'], ['点云展示', '/pointcloud'],
  ['高斯渲染', '/gaussian'], ['设计图预览', '/design'], ['数据管理', '/manage'], ['信息统计', '/stats'],
]
const API_BASE = (import.meta.env.VITE_API_BASE_URL || '').replace(/\/$/, '')
const route = useRoute()
const router = useRouter()
const images = ref([])
const selectedId = ref(null)
const query = ref('')
const loading = ref(false)
const error = ref('')
const uploadInput = ref(null)
const uploadMessage = ref('')
const stage = ref(null)
const zoom = ref(1)
const pan = ref({ x: 0, y: 0 })
const dragging = ref(false)
const dragStart = ref({ x: 0, y: 0 })
let controller

function assetUrl(path) {
  if (!path) return ''
  return /^https?:\/\//i.test(path) ? path : `${API_BASE}${path}`
}

function normalize(item) {
  return {
    ...item,
    id: item.id,
    name: item.name || item.originalName || '未命名图纸',
    originalName: item.originalName || item.name || '未命名文件',
    format: String(item.format || 'IMAGE').toUpperCase(),
    width: Number(item.width || 0),
    height: Number(item.height || 0),
    fileSize: Number(item.fileSize || 0),
    createTime: item.createTime || item.createdAt || '',
    imageUrl: assetUrl(item.imageUrl || `/api/data/design-images/${item.id}/content`),
    thumbnailUrl: assetUrl(item.thumbnailUrl || `/api/data/design-images/${item.id}/thumbnail`),
  }
}

const filteredImages = computed(() => {
  const text = query.value.trim().toLowerCase()
  if (!text) return images.value
  return images.value.filter(item => `${item.name} ${item.originalName} ${item.format}`.toLowerCase().includes(text))
})
const selectedImage = computed(() => images.value.find(item => String(item.id) === String(selectedId.value)) || images.value[0] || null)
const displayName = computed(() => selectedImage.value?.name || '暂无图纸')
const imageTransform = computed(() => `translate(${pan.value.x}px, ${pan.value.y}px) scale(${zoom.value})`)
const zoomLabel = computed(() => `${Math.round(zoom.value * 100)}%`)
const formattedDate = computed(() => {
  const value = selectedImage.value?.createTime
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? String(value).replace('T', ' ').slice(0, 16) : new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit',
  }).format(date)
})
const formattedSize = computed(() => {
  const bytes = selectedImage.value?.fileSize || 0
  if (!bytes) return '—'
  if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`
  return `${Math.max(1, Math.round(bytes / 1024))} KB`
})
const dimensions = computed(() => {
  const item = selectedImage.value
  return item?.width && item?.height ? `${item.width} × ${item.height} px` : '—'
})

function selectImage(item) {
  selectedId.value = item.id
  router.replace({ query: { ...route.query, image: item.id } })
  resetView()
}
function resetView() {
  zoom.value = 1
  pan.value = { x: 0, y: 0 }
}
function changeZoom(delta) {
  zoom.value = Math.min(4, Math.max(0.2, Number((zoom.value + delta).toFixed(2))))
}
function fitImage() {
  const image = stage.value?.querySelector('.drawing-image')
  if (!image || !stage.value) return
  const width = image.naturalWidth || selectedImage.value?.width || 1
  const height = image.naturalHeight || selectedImage.value?.height || 1
  const availableWidth = Math.max(100, stage.value.clientWidth - 64)
  const availableHeight = Math.max(100, stage.value.clientHeight - 112)
  zoom.value = Math.min(1, Math.max(0.2, Math.min(availableWidth / width, availableHeight / height)))
  pan.value = { x: 0, y: 0 }
}
function originalSize() {
  zoom.value = 1
  pan.value = { x: 0, y: 0 }
}
function onWheel(event) {
  event.preventDefault()
  changeZoom(event.deltaY < 0 ? 0.1 : -0.1)
}
function beginDrag(event) {
  if (zoom.value <= 1) return
  dragging.value = true
  dragStart.value = { x: event.clientX - pan.value.x, y: event.clientY - pan.value.y }
  stage.value?.setPointerCapture?.(event.pointerId)
}
function moveDrag(event) {
  if (!dragging.value) return
  pan.value = { x: event.clientX - dragStart.value.x, y: event.clientY - dragStart.value.y }
}
function endDrag() {
  dragging.value = false
}
async function toggleFullscreen() {
  if (!stage.value) return
  if (document.fullscreenElement) await document.exitFullscreen?.()
  else await stage.value.requestFullscreen?.()
}
async function loadImages() {
  controller?.abort()
  controller = new AbortController()
  loading.value = true
  error.value = ''
  try {
    const response = await fetch(`${API_BASE}/api/data/design-images/list`, { signal: controller.signal, cache: 'no-store' })
    const body = await response.json().catch(() => null)
    if (!response.ok) throw new Error(body?.message || `图像服务返回 ${response.status}`)
    const list = Array.isArray(body) ? body : body?.data || body?.items
    if (!Array.isArray(list)) throw new Error('图纸列表响应格式不正确')
    images.value = list.map(normalize)
    const routeId = route.query.image
    selectedId.value = images.value.some(item => String(item.id) === String(routeId))
      ? routeId
      : images.value[0]?.id || null
    await nextTick()
    if (selectedImage.value) fitImage()
  } catch (cause) {
    if (cause.name !== 'AbortError') error.value = cause.message || '图纸加载失败'
  } finally {
    loading.value = false
  }
}
function openUpload() {
  uploadInput.value?.click()
}
async function uploadFiles(event) {
  const files = [...(event.target.files || [])]
  event.target.value = ''
  if (!files.length) return
  uploadMessage.value = '正在上传图像…'
  try {
    const form = new FormData()
    files.forEach(file => form.append('files', file, file.name))
    const response = await fetch(`${API_BASE}/api/data/design-images/upload`, { method: 'POST', body: form })
    const body = await response.json().catch(() => null)
    if (!response.ok) throw new Error(body?.message || `上传失败（${response.status}）`)
    uploadMessage.value = '图像已入库'
    await loadImages()
  } catch (cause) {
    uploadMessage.value = cause.message || '图像上传失败'
  } finally {
    window.setTimeout(() => { uploadMessage.value = '' }, 2800)
  }
}
function onImageLoad() {
  if (zoom.value === 1 && !pan.value.x && !pan.value.y) fitImage()
}
watch(selectedImage, () => nextTick(onImageLoad))
onMounted(loadImages)
onBeforeUnmount(() => controller?.abort())
</script>

<template>
  <div class="heritage-shell design-shell">
    <aside class="heritage-spine" aria-label="天坛数字遗产">
      <RouterLink class="seal" to="/" aria-label="返回首页">天<br />坛</RouterLink>
      <p>让遗产在数字中延续</p>
      <span>TIANTAN<br />1530 — 2026</span>
    </aside>
    <div class="heritage-body">
      <header class="global-top">
        <RouterLink class="brand" to="/"><strong>天坛 · 数字遗产</strong><small>TEMPLE OF HEAVEN</small></RouterLink>
        <nav aria-label="主导航">
          <RouterLink v-for="item in navigation" :key="item[1]" :to="item[1]" class="nav-link" :class="{ active: item[1] === '/design' }">{{ item[0] }}</RouterLink>
        </nav>
        <button class="help-button" type="button" title="设计图来自数据服务" aria-label="数据来源说明"><IconMark name="help" :size="16" /></button>
      </header>

      <main class="main-content design-content">
        <section class="design-intro">
          <div>
            <p class="design-kicker">DRAWING ARCHIVE / 004</p>
            <h1>图纸里的建筑记忆。</h1>
            <p>让图纸与建筑彼此印证，在缩放之间阅读构造细节。</p>
          </div>
          <div class="design-actions">
            <span v-if="uploadMessage" class="upload-message">{{ uploadMessage }}</span>
            <input ref="uploadInput" type="file" accept="image/*" multiple hidden @change="uploadFiles" />
            <button type="button" class="archive-action" @click="openUpload">图像入库 <span aria-hidden="true">→</span></button>
          </div>
        </section>

        <div v-if="error" class="design-error" role="alert">图纸数据加载失败：{{ error }} <button type="button" @click="loadImages">重试</button></div>
        <div v-if="loading && !images.length" class="design-loading">正在读取图像档案…</div>

        <section v-else class="design-layout">
          <aside class="drawing-catalog">
            <div class="catalog-heading">
              <div><p>DRAWING INDEX</p><h2>图纸目录</h2></div>
              <span>{{ images.length.toString().padStart(2, '0') }}</span>
            </div>
            <label class="drawing-search"><span>⌕</span><input v-model="query" type="search" placeholder="搜索图纸" /></label>
            <div v-if="!filteredImages.length" class="drawing-empty">{{ images.length ? '没有匹配的图纸' : '暂无图纸资源' }}</div>
            <div v-else class="drawing-list">
              <button v-for="item in filteredImages" :key="item.id" type="button" class="drawing-card" :class="{ selected: String(item.id) === String(selectedId) }" @click="selectImage(item)">
                <span class="drawing-thumb"><img :src="item.thumbnailUrl" :alt="item.name" /></span>
                <strong>{{ item.name }}</strong>
                <small>{{ item.format }} · {{ item.width && item.height ? `${item.width} × ${item.height}` : '图像' }}</small>
              </button>
            </div>
            <RouterLink class="catalog-manage" to="/manage">管理图像档案 <span aria-hidden="true">→</span></RouterLink>
          </aside>

          <section ref="stage" class="drawing-stage" :class="{ dragging }" @wheel="onWheel" @pointerdown="beginDrag" @pointermove="moveDrag" @pointerup="endDrag" @pointercancel="endDrag" @pointerleave="endDrag">
            <header class="drawing-stage-head">
              <div><p>DRAWING PREVIEW</p><strong>{{ displayName }}</strong></div>
              <div class="drawing-tools" aria-label="图像工具">
                <button type="button" title="适应窗口" aria-label="适应窗口" @click="fitImage">⌗</button>
                <button type="button" title="原始尺寸" aria-label="原始尺寸" @click="originalSize">1:1</button>
                <button type="button" title="全屏预览" aria-label="全屏预览" @click="toggleFullscreen">⛶</button>
              </div>
            </header>
            <div class="drawing-canvas">
              <div v-if="!selectedImage" class="canvas-empty">选择一张图纸开始预览</div>
              <img v-else class="drawing-image" :src="selectedImage.imageUrl" :alt="selectedImage.name" :style="{ transform: imageTransform }" draggable="false" @load="onImageLoad" />
            </div>
            <footer class="drawing-stage-foot">
              <div class="zoom-controls">
                <button type="button" title="缩小" aria-label="缩小" @click="changeZoom(-0.1)">−</button>
                <strong>{{ zoomLabel }}</strong>
                <button type="button" title="放大" aria-label="放大" @click="changeZoom(0.1)">+</button>
                <i></i>
                <button type="button" @click="fitImage">适应窗口</button>
                <button type="button" @click="originalSize">原始尺寸</button>
              </div>
              <span>{{ selectedImage?.format || '—' }} · {{ dimensions }}</span>
            </footer>
          </section>

          <aside class="drawing-info">
            <div class="info-heading"><p>ARCHIVE RECORD</p><h2>档案信息</h2></div>
            <dl v-if="selectedImage" class="drawing-metadata">
              <div><dt>图纸名称</dt><dd>{{ selectedImage.name }}</dd></div>
              <div><dt>档案编号</dt><dd>DRAWING-{{ String(selectedImage.id).padStart(3, '0') }}</dd></div>
              <div><dt>所属建筑</dt><dd>天坛建筑群</dd></div>
              <div><dt>图像格式</dt><dd>{{ selectedImage.format }} · {{ formattedSize }}</dd></div>
              <div><dt>图像尺寸</dt><dd>{{ dimensions }}</dd></div>
              <div><dt>入库日期</dt><dd>{{ formattedDate }}</dd></div>
            </dl>
            <p v-if="selectedImage" class="drawing-note">从平面到空间，读懂建筑的构造秩序。</p>
            <p class="drawing-caption">当前为图像预览，CAD 转换后续接入。</p>
          </aside>
        </section>
      </main>
    </div>
  </div>
</template>

<style scoped>
.design-content{max-width:1900px;padding-top:30px;padding-bottom:40px}
.design-intro{display:flex;align-items:flex-end;justify-content:space-between;gap:25px;padding:0 0 25px;border-bottom:1px solid var(--line)}
.design-kicker,.catalog-heading p,.drawing-stage-head p,.info-heading p{color:var(--muted);font:8px/1.5 Consolas,monospace;letter-spacing:2.2px}
.design-intro h1{margin-top:16px;font:400 31px/1.45 var(--display);letter-spacing:3px}
.design-intro>div>p:last-child{margin-top:9px;color:var(--muted);font:11px/1.7 "Microsoft YaHei",sans-serif}
.design-actions{display:flex;align-items:center;gap:14px;white-space:nowrap}
.archive-action{min-height:38px;padding:0 15px;border:0;background:var(--red);color:#fff;cursor:pointer;font:11px "Microsoft YaHei",sans-serif}
.archive-action span{margin-left:12px;font-size:17px}.archive-action:hover{background:var(--red-dark)}
.upload-message{color:var(--muted);font:10px "Microsoft YaHei",sans-serif}
.design-error{display:flex;gap:12px;align-items:center;margin:18px 0 0;padding:11px 14px;border:1px solid #c6816b;color:var(--red);font:11px "Microsoft YaHei",sans-serif}
.design-error button{border:0;background:transparent;color:var(--red);text-decoration:underline;cursor:pointer}
.design-loading{padding:130px 0;text-align:center;color:var(--muted);font:12px "Microsoft YaHei",sans-serif}
.design-layout{display:grid;grid-template-columns:220px minmax(0,1fr) 210px;min-height:670px;gap:20px;margin-top:24px}
.drawing-catalog{display:flex;flex-direction:column;min-width:0;padding-right:17px;border-right:1px solid var(--line)}
.catalog-heading,.info-heading{display:flex;justify-content:space-between;align-items:flex-start;gap:12px}
.catalog-heading h2,.info-heading h2{margin-top:8px;font:400 18px/1.4 var(--display);letter-spacing:1px}
.catalog-heading>span{padding:4px 6px;color:var(--red);background:#eee5da;font:9px Consolas,monospace}
.drawing-search{display:flex;align-items:center;gap:7px;min-height:34px;margin:17px 0 10px;padding:0 8px;border:1px solid var(--line);color:var(--muted)}
.drawing-search input{width:100%;min-width:0;border:0;outline:0;background:transparent;color:var(--ink);font:10px "Microsoft YaHei",sans-serif}
.drawing-list{display:flex;flex-direction:column;gap:11px}
.drawing-card{display:block;width:100%;padding:8px;border:1px solid transparent;background:transparent;text-align:left;cursor:pointer}
.drawing-card:hover,.drawing-card.selected{border-color:#c9ab9b;background:#f8f3ea}
.drawing-thumb{display:block;width:100%;height:72px;overflow:hidden;background:#e9e1d4}
.drawing-thumb img{width:100%;height:100%;object-fit:cover;filter:sepia(.16) contrast(.94)}
.drawing-card strong{display:block;margin-top:7px;overflow:hidden;color:var(--ink);font:11px/1.5 "Microsoft YaHei",sans-serif;text-overflow:ellipsis;white-space:nowrap}
.drawing-card small{display:block;margin-top:3px;color:var(--muted);font:8px/1.4 Consolas,monospace}
.drawing-empty{padding:28px 0;color:var(--muted);font:10px/1.6 "Microsoft YaHei",sans-serif}
.catalog-manage{display:flex;gap:9px;align-items:center;margin-top:auto;padding-top:20px;color:var(--red);font:10px "Microsoft YaHei",sans-serif}
.drawing-stage{display:grid;grid-template-rows:auto minmax(0,1fr) auto;min-width:0;min-height:670px;border:1px solid var(--line);background:#eee8dc}
.drawing-stage:fullscreen{width:100vw;height:100vh}
.drawing-stage-head,.drawing-stage-foot{display:flex;align-items:center;justify-content:space-between;gap:12px;min-width:0;padding:13px 14px;background:var(--paper-soft);border-bottom:1px solid var(--line)}
.drawing-stage-head strong{display:block;margin-top:5px;overflow:hidden;font:12px "Microsoft YaHei",sans-serif;text-overflow:ellipsis;white-space:nowrap}
.drawing-tools,.zoom-controls{display:flex;align-items:center;gap:4px}
.drawing-tools button,.zoom-controls button{min-width:29px;height:28px;padding:0 7px;border:1px solid var(--line);background:transparent;color:var(--muted);cursor:pointer;font:10px "Microsoft YaHei",sans-serif}
.drawing-tools button:hover,.zoom-controls button:hover{border-color:var(--red);color:var(--red)}
.drawing-canvas{position:relative;display:grid;place-items:center;min-height:0;overflow:hidden;background:#e8e0d1;cursor:grab;background-image:linear-gradient(90deg,rgba(255,255,255,.18) 1px,transparent 1px),linear-gradient(rgba(255,255,255,.18) 1px,transparent 1px);background-size:28px 28px}
.drawing-stage.dragging .drawing-canvas{cursor:grabbing}
.drawing-image{display:block;max-width:none;max-height:none;user-select:none;transform-origin:center center;box-shadow:0 3px 18px rgba(73,57,38,.13)}
.canvas-empty{color:var(--muted);font:11px "Microsoft YaHei",sans-serif}
.drawing-stage-foot{border-top:1px solid var(--line);border-bottom:0}.zoom-controls strong{min-width:42px;text-align:center;color:var(--ink);font:10px Consolas,monospace}.zoom-controls i{height:20px;margin:0 7px;border-left:1px solid var(--line)}
.drawing-stage-foot>span{color:var(--muted);font:8px Consolas,monospace;white-space:nowrap}
.drawing-info{min-width:0;padding-left:18px;border-left:1px solid var(--line)}
.drawing-metadata{margin:16px 0 0}
.drawing-metadata>div{padding:13px 0;border-top:1px solid var(--line)}
.drawing-metadata dt{color:var(--muted);font:9px "Microsoft YaHei",sans-serif}
.drawing-metadata dd{margin:6px 0 0;overflow-wrap:anywhere;font:11px/1.5 "Microsoft YaHei",sans-serif}
.drawing-note{margin-top:27px;color:var(--red);font:18px/1.7 var(--display)}
.drawing-caption{margin-top:24px;padding-top:14px;border-top:1px solid var(--line);color:var(--muted);font:9px/1.7 "Microsoft YaHei",sans-serif}
@media(max-width:1300px){.design-content{padding-left:28px;padding-right:28px}.design-layout{grid-template-columns:180px minmax(0,1fr) 170px;gap:15px}.drawing-info{padding-left:13px}}
@media(max-width:1050px){.design-layout{grid-template-columns:175px minmax(0,1fr);grid-template-rows:minmax(640px,auto) auto}.drawing-info{grid-column:2;grid-row:2;padding:20px 0 0;border-left:0;border-top:1px solid var(--line)}.drawing-metadata{display:grid;grid-template-columns:1fr 1fr;gap:0 15px}.drawing-note,.drawing-caption{display:none}}
@media(max-width:700px){.design-intro{display:block}.design-intro h1{font-size:24px;letter-spacing:1px}.design-actions{margin-top:17px;justify-content:space-between}.design-layout{grid-template-columns:98px minmax(0,1fr);gap:10px;min-height:540px}.drawing-catalog{padding-right:8px}.catalog-heading h2{font-size:15px}.catalog-heading p,.drawing-stage-head p{font-size:7px;letter-spacing:1px}.drawing-search{padding:0 5px}.drawing-search span{display:none}.drawing-search input{font-size:9px}.drawing-list{gap:8px}.drawing-card{padding:4px}.drawing-thumb{height:49px}.drawing-card strong{font-size:9px;white-space:normal}.drawing-card small{font-size:7px}.catalog-manage{font-size:9px;line-height:1.5}.drawing-stage{grid-column:2;grid-row:1;min-height:540px}.drawing-stage-head{padding:9px 7px;align-items:flex-start;flex-direction:column}.drawing-stage-head strong{font-size:10px}.drawing-tools{position:absolute;top:8px;right:7px}.drawing-tools button{min-width:26px;padding:0 5px}.drawing-stage-foot{padding:8px 6px;justify-content:flex-end}.drawing-stage-foot>span{display:none}.zoom-controls button:nth-of-type(4),.zoom-controls i{display:none}.drawing-info{grid-column:1/-1;grid-row:2;padding-top:16px}.drawing-metadata{grid-template-columns:1fr 1fr}.drawing-note{display:none}}
</style>
