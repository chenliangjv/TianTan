<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'

const API_BASE = (import.meta.env.VITE_API_BASE_URL || '').replace(/\/$/, '')
const navigation = [
  ['首页', '/'], ['模型展示', '/model'], ['点云展示', '/pointcloud'],
  ['高斯渲染', '/gaussian'], ['设计图预览', '/design'], ['数据管理', '/manage'], ['信息统计', '/stats'],
]
const categories = [
  { key: 'ALL', label: '全部资产' },
  { key: 'MODEL', label: '三维模型' },
  { key: 'POINT_CLOUD', label: '点云数据' },
  { key: 'GAUSSIAN', label: '高斯场景' },
  { key: 'DESIGN', label: '设计图' },
]
const categoryNames = Object.fromEntries(categories.map(item => [item.key, item.label]))
const endpoints = {
  MODEL: '/api/data/models/list',
  POINT_CLOUD: '/api/data/point-clouds/list',
  GAUSSIAN: '/api/data/gaussians/list',
  DESIGN: '/api/data/design-images/list',
}
const deleteEndpoints = {
  MODEL: id => `/api/data/models/${encodeURIComponent(id)}`,
  POINT_CLOUD: id => `/api/data/point-clouds/${encodeURIComponent(id)}`,
  GAUSSIAN: id => `/api/data/gaussians/${encodeURIComponent(id)}`,
  DESIGN: id => `/api/data/design-images/${encodeURIComponent(id)}`,
}
const uploadEndpoints = {
  MODEL: { folder: '/api/data/models/upload-folder', archive: '/api/data/models/upload-archive' },
  POINT_CLOUD: { folder: '/api/data/point-clouds/upload-folder', archive: '/api/data/point-clouds/upload-archive' },
  GAUSSIAN: { folder: '/api/data/gaussians/upload-folder', archive: '/api/data/gaussians/upload-archive' },
  DESIGN: { files: '/api/data/design-images/upload' },
}

const router = useRouter()
const assets = ref([])
const statistics = ref(null)
const loading = ref(false)
const error = ref('')
const query = ref('')
const selectedCategory = ref('ALL')
const viewMode = ref('list')
const importCategory = ref('MODEL')
const uploadMode = ref('archive')
const importOpen = ref(false)
const selectedAssetIds = ref(new Set())
const folderInput = ref(null)
const archiveInput = ref(null)
const imageInput = ref(null)
const selectedFiles = ref([])
const selectedArchive = ref(null)
const uploadMessage = ref('')
const uploading = ref(false)
const uploadProgress = ref(0)
const currentTask = ref(null)
const deletingId = ref(null)
let taskTimer = null

function apiUrl(path) { return `${API_BASE}${path}` }
function unwrap(body) {
  if (Array.isArray(body)) return body
  return body?.data || body?.items || body?.content || []
}
function formatBytes(value) {
  const bytes = Number(value)
  if (!Number.isFinite(bytes) || bytes <= 0) return '—'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let size = bytes
  let index = 0
  while (size >= 1024 && index < units.length - 1) { size /= 1024; index += 1 }
  return `${size >= 10 || index === 0 ? size.toFixed(0) : size.toFixed(1)} ${units[index]}`
}
function formatDate(value) {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return String(value).replace('T', ' ').slice(0, 16)
  return new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit' }).format(date).replaceAll('/', '.')
}
function normalize(item, category) {
  const sizeBytes = Number(item.totalSize ?? item.fileSize ?? item.size ?? 0)
  return {
    id: item.id,
    sourceId: item.id,
    category,
    typeLabel: categoryNames[category],
    name: item.name || item.originalName || '未命名资产',
    format: String(item.format || item.type || (category === 'DESIGN' ? 'IMAGE' : category === 'POINT_CLOUD' ? 'POTREE 2.0' : 'OCTREE')).toUpperCase(),
    size: formatBytes(sizeBytes),
    sizeBytes,
    status: item.status || (category === 'MODEL' || category === 'POINT_CLOUD' ? 'READY' : 'SUCCESS'),
    createdAt: item.createdAt || item.createTime || item.uploadTime || '',
    raw: item,
  }
}
function statusLabel(status) {
  return { READY: '可用', SUCCESS: '完成', FAILED: '失败', ERROR: '异常', PROCESSING: '处理中', QUEUED: '排队中', PENDING: '待处理' }[status] || status || '可用'
}
function statusClass(status) {
  return ['FAILED', 'ERROR'].includes(status) ? 'danger' : ['PROCESSING', 'QUEUED', 'PENDING'].includes(status) ? 'pending' : 'ready'
}
function categoryStats(key) {
  const source = statistics.value?.categories || statistics.value?.categoryStats || statistics.value?.byCategory || []
  if (Array.isArray(source)) {
    return source.find(value => String(value.key || value.type || value.category || '').toUpperCase() === key) || {}
  }
  const value = source[key] || source[key.toLowerCase()] || {}
  return typeof value === 'number' ? { count: value } : value
}
const filteredAssets = computed(() => {
  const text = query.value.trim().toLowerCase()
  return assets.value.filter(item => {
    const categoryMatch = selectedCategory.value === 'ALL' || item.category === selectedCategory.value
    const textMatch = !text || `${item.name} ${item.typeLabel} ${item.format}`.toLowerCase().includes(text)
    return categoryMatch && textMatch
  })
})
const totals = computed(() => {
  const raw = statistics.value?.totals || statistics.value || {}
  return {
    count: Number(raw.totalAssets ?? assets.value.length),
    bytes: Number(raw.totalBytesAll ?? raw.totalBytes ?? assets.value.reduce((sum, item) => sum + item.sizeBytes, 0)),
    processing: Number(raw.processing ?? raw.processingTasks ?? assets.value.filter(item => ['PROCESSING', 'QUEUED', 'PENDING'].includes(item.status)).length),
  }
})
const latestAsset = computed(() => [...assets.value].sort((a, b) => new Date(b.createdAt || 0) - new Date(a.createdAt || 0))[0])
const taskPercent = computed(() => {
  const task = currentTask.value
  if (!task) return 0
  if (task.totalFiles) return Math.min(100, Math.round((Number(task.processedFiles || 0) / Number(task.totalFiles)) * 100))
  if (task.totalNodes) return Math.min(100, Math.round((Number(task.processedNodes || 0) / Number(task.totalNodes)) * 100))
  return task.status === 'SUCCESS' ? 100 : 0
})

async function request(path, options = {}) {
  const response = await fetch(apiUrl(path), { cache: 'no-store', ...options })
  const body = await response.json().catch(() => null)
  if (!response.ok) throw new Error(body?.message || body?.error || (typeof body === 'string' ? body : `请求失败（${response.status}）`))
  return body
}
async function loadData() {
  loading.value = true
  error.value = ''
  try {
    const responses = await Promise.all(Object.entries(endpoints).map(async ([category, path]) => {
      try { return [category, unwrap(await request(path)).map(item => normalize(item, category))] }
      catch (cause) { console.warn(`[manage] ${category} list failed`, cause); return [category, []] }
    }))
    assets.value = responses.flatMap(([, list]) => list)
    try {
      statistics.value = await request('/api/data/statistics')
      const categorySizes = Object.fromEntries(
        (statistics.value?.categories || []).map(item => [String(item.key).toUpperCase(), Number(item.fileSizeBytes || 0)]),
      )
      assets.value = assets.value.map(asset => {
        if (asset.sizeBytes > 0 || !categorySizes[asset.category]) return asset
        return { ...asset, sizeBytes: categorySizes[asset.category], size: formatBytes(categorySizes[asset.category]) }
      })
    } catch (cause) { console.warn('[manage] statistics failed', cause) }
    if (!assets.value.length && !statistics.value) error.value = '暂时无法获取资产数据，请确认数据服务已启动。'
  } catch (cause) {
    error.value = cause.message || '数据加载失败'
  } finally {
    loading.value = false
  }
}
function openCategory(category) { selectedCategory.value = category }
function categoryCount(category) {
  const value = categoryStats(category)
  return Number(value.count ?? value.total ?? value.quantity ?? assets.value.filter(asset => asset.category === category).length)
}
function assetIcon(category) {
  return { MODEL: '◇', POINT_CLOUD: '••', GAUSSIAN: '✦', DESIGN: '▧' }[category] || '◇'
}
function toggleAsset(id, checked) {
  const next = new Set(selectedAssetIds.value)
  checked ? next.add(id) : next.delete(id)
  selectedAssetIds.value = next
}
function toggleAll(checked) {
  selectedAssetIds.value = checked
    ? new Set(filteredAssets.value.map(asset => `${asset.category}:${asset.id}`))
    : new Set()
}
function isSelected(asset) {
  return selectedAssetIds.value.has(`${asset.category}:${asset.id}`)
}
function routeFor(asset) {
  return { MODEL: '/model', POINT_CLOUD: '/pointcloud', GAUSSIAN: '/gaussian', DESIGN: '/design' }[asset.category]
}
function viewAsset(asset) {
  const path = routeFor(asset)
  if (path) router.push({ path, query: { [asset.category === 'MODEL' ? 'model' : asset.category === 'POINT_CLOUD' ? 'dataset' : asset.category === 'GAUSSIAN' ? 'scene' : 'image']: asset.sourceId } })
}
async function removeAsset(asset) {
  if (!window.confirm(`确定删除“${asset.name}”吗？此操作不可恢复。`)) return
  deletingId.value = `${asset.category}:${asset.id}`
  try { await request(deleteEndpoints[asset.category](asset.sourceId), { method: 'DELETE' }); assets.value = assets.value.filter(item => item !== asset); await loadData() }
  catch (cause) { uploadMessage.value = cause.message || '删除失败' }
  finally { deletingId.value = null }
}
function clearSelection() {
  selectedFiles.value = []
  selectedArchive.value = null
  uploadProgress.value = 0
  if (folderInput.value) folderInput.value.value = ''
  if (archiveInput.value) archiveInput.value.value = ''
  if (imageInput.value) imageInput.value.value = ''
}
function onFolderSelected(event) {
  selectedFiles.value = [...(event.target.files || [])]
  selectedArchive.value = null
}
function onArchiveSelected(event) {
  const file = event.target.files?.[0]
  selectedArchive.value = file?.name.toLowerCase().endsWith('.zip') ? file : null
  selectedFiles.value = []
}
function selectImportCategory(key) {
  importCategory.value = key
  clearSelection()
  uploadMode.value = key === 'DESIGN' ? 'files' : 'archive'
}
function uploadWithProgress(path, formData) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('POST', apiUrl(path))
    xhr.upload.onprogress = event => { if (event.lengthComputable) uploadProgress.value = Math.round(event.loaded / event.total * 100) }
    xhr.onload = () => {
      const body = xhr.responseText
      if (xhr.status >= 200 && xhr.status < 300) { try { resolve(JSON.parse(body)) } catch { resolve(null) } }
      else reject(new Error(body || `上传失败（${xhr.status}）`))
    }
    xhr.onerror = () => reject(new Error('网络错误，上传失败'))
    xhr.send(formData)
  })
}
async function submitUpload() {
  if (importCategory.value === 'DESIGN') return uploadDesign()
  if (uploadMode.value === 'archive' && !selectedArchive.value) return (uploadMessage.value = '请先选择 ZIP 压缩包')
  if (uploadMode.value === 'folder' && !selectedFiles.value.length) return (uploadMessage.value = '请先选择数据文件夹')
  uploading.value = true
  uploadMessage.value = '正在上传…'
  uploadProgress.value = 0
  try {
    const form = new FormData()
    let path
    if (uploadMode.value === 'archive') {
      form.append('archive', selectedArchive.value, selectedArchive.value.name)
      form.append('folderName', selectedArchive.value.name.replace(/\.zip$/i, ''))
      path = uploadEndpoints[importCategory.value].archive
    } else {
      selectedFiles.value.forEach(file => form.append('files', file, file.webkitRelativePath || file.name))
      form.append('folderName', selectedFiles.value[0]?.webkitRelativePath?.split('/')[0] || selectedFiles.value[0]?.name || 'upload')
      path = uploadEndpoints[importCategory.value].folder
    }
    currentTask.value = await uploadWithProgress(path, form)
    uploadMessage.value = currentTask.value ? '文件已上传，正在异步入库' : '文件已上传'
    clearSelection()
    if (currentTask.value?.taskId) startTaskPolling()
    else await loadData()
  } catch (cause) { uploadMessage.value = cause.message || '上传失败' }
  finally { uploading.value = false }
}
function uploadDesign() {
  const files = selectedFiles.value
  if (!files.length) return (uploadMessage.value = '请先选择设计图文件')
  uploading.value = true
  uploadMessage.value = '正在上传图像…'
  const form = new FormData()
  files.forEach(file => form.append('files', file, file.name))
  uploadWithProgress(uploadEndpoints.DESIGN.files, form).then(() => { uploadMessage.value = '设计图已入库'; clearSelection(); loadData() }).catch(cause => { uploadMessage.value = cause.message || '上传失败' }).finally(() => { uploading.value = false })
}
function startTaskPolling() {
  window.clearInterval(taskTimer)
  refreshTask()
  taskTimer = window.setInterval(refreshTask, 2000)
}
async function refreshTask() {
  if (!currentTask.value?.taskId) return
  try {
    const task = await request(`/api/data/tasks/${encodeURIComponent(currentTask.value.taskId)}`)
    currentTask.value = task
    if (['SUCCESS', 'FAILED'].includes(task.status)) {
      window.clearInterval(taskTimer)
      taskTimer = null
      if (task.status === 'SUCCESS') await loadData()
    }
  } catch (cause) { console.warn('[manage] task polling failed', cause) }
}
onMounted(loadData)
onBeforeUnmount(() => window.clearInterval(taskTimer))
</script>

<template>
  <div class="heritage-shell manage-shell">
    <aside class="heritage-spine">
      <RouterLink class="seal" to="/" aria-label="返回首页">天<br />坛</RouterLink>
      <p>让遗产在数字中延续</p>
      <span>TIANTAN<br />1530 — 2026</span>
    </aside>
    <div class="heritage-body">
      <header class="global-top">
        <RouterLink class="brand" to="/"><strong>天坛 · 数字遗产</strong><small>TEMPLE OF HEAVEN</small></RouterLink>
        <nav aria-label="主导航"><RouterLink v-for="item in navigation" :key="item[1]" :to="item[1]" class="nav-link" :class="{ active: item[1] === '/manage' }">{{ item[0] }}</RouterLink></nav>
        <button class="help-button" type="button" title="数据管理帮助">?</button>
      </header>
      <main class="main-content manage-content">
        <section class="manage-heading">
          <div><p class="manage-kicker">ASSET LIBRARY / GOVERNANCE</p><h1>每份成果，妥善珍藏。</h1><p>将每次测绘与记录，沉淀为可持续使用的数字资产。</p></div>
          <button class="refresh-button" type="button" :disabled="loading" @click="loadData">↻ <span>刷新档案</span></button>
        </section>
        <section class="manage-overview">
          <button type="button" class="overview-item" :class="{ active: selectedCategory === 'ALL' }" @click="openCategory('ALL')"><span>全部资产</span><strong>{{ totals.count }}<small>项</small></strong></button>
          <div class="overview-item overview-static"><span>已归档容量</span><strong>{{ formatBytes(totals.bytes).replace(' ', '') }}<small>总量</small></strong></div>
          <div class="overview-item overview-static"><span>正在处理</span><strong>{{ totals.processing.toString().padStart(2, '0') }}<small>个任务</small></strong></div>
          <div class="overview-item overview-static"><span>最新入库</span><strong>{{ latestAsset ? formatDate(latestAsset.createdAt).slice(5).replace('.', '.') : '—' }}<small>{{ latestAsset ? formatDate(latestAsset.createdAt).slice(0, 4) : '' }}</small></strong></div>
        </section>
        <section class="manage-layout">
          <section class="asset-table panel" :class="{ 'cards-mode': viewMode === 'grid' }">
            <div class="table-heading">
              <div><h2>数字资产目录</h2><span class="result-count">显示 {{ filteredAssets.length }} 条资产</span></div>
              <label class="search-field"><span>⌕</span><input v-model="query" type="search" placeholder="搜索资产名称…" /></label>
              <div class="view-switch"><button type="button" :class="{ active: viewMode === 'list' }" title="列表视图" @click="viewMode = 'list'">☷</button><button type="button" :class="{ active: viewMode === 'grid' }" title="卡片视图" @click="viewMode = 'grid'">▦</button></div>
            </div>
            <div class="asset-filters">
              <button v-for="item in categories" :key="item.key" type="button" :class="{ active: selectedCategory === item.key }" @click="openCategory(item.key)">{{ item.label }}<span>{{ item.key === 'ALL' ? totals.count : categoryCount(item.key) }}</span></button>
            </div>
            <div v-if="error" class="manage-error">{{ error }} <button type="button" @click="loadData">重试</button></div>
            <div v-else-if="loading && !assets.length" class="manage-empty">正在读取资产档案…</div>
            <div v-else-if="!filteredAssets.length" class="no-results"><strong>{{ assets.length ? '没有找到匹配的资产' : '暂无资产' }}</strong><span>完成导入后，资产会出现在这里。</span></div>
            <div v-else class="table-scroll">
              <table>
                <thead><tr><th><input type="checkbox" aria-label="选择全部" :checked="selectedAssetIds.size === filteredAssets.length && filteredAssets.length > 0" @change="toggleAll($event.target.checked)" /></th><th>资产名称</th><th>类型</th><th>格式</th><th>文件容量</th><th>状态</th><th>入库时间</th><th>操作</th></tr></thead>
                <tbody>
                  <tr v-for="asset in filteredAssets" :key="`${asset.category}-${asset.id}`">
                    <td><input type="checkbox" :checked="isSelected(asset)" :aria-label="`选择${asset.name}`" @change="toggleAsset(`${asset.category}:${asset.id}`, $event.target.checked)" /></td>
                    <td><div class="asset-name"><span class="asset-symbol" :class="`type-${asset.category}`">{{ assetIcon(asset.category) }}</span><div><strong :title="asset.name">{{ asset.name }}</strong><small>{{ asset.sourceId || '—' }}</small></div></div></td>
                    <td>{{ asset.typeLabel }}</td><td><span class="format-label">{{ asset.format }}</span></td><td>{{ asset.size }}</td><td><span class="badge" :class="statusClass(asset.status)">{{ statusLabel(asset.status) }}</span></td><td>{{ formatDate(asset.createdAt) }}</td>
                    <td><button class="table-action" type="button" @click="viewAsset(asset)">查看</button><button class="table-action danger" type="button" :disabled="deletingId === `${asset.category}:${asset.id}`" @click="removeAsset(asset)">删除</button></td>
                  </tr>
                </tbody>
              </table>
            </div>
            <footer><span>{{ selectedAssetIds.size ? `已选择 ${selectedAssetIds.size} 项资产` : '统一管理四类数字成果' }}</span><span>共 {{ filteredAssets.length }} 项</span></footer>
          </section>
          <section class="import-panel panel">
            <span class="upload-mark">↑</span><p class="eyebrow">EXPAND YOUR ARCHIVE</p><h2>让新成果，<br />加入数字档案。</h2><p>模型、点云、高斯与设计图<br />分类归档，集中管理。</p>
            <button type="button" class="button primary import-trigger" @click="importOpen = !importOpen">{{ importOpen ? '收起上传' : '上传数据' }} <span>{{ importOpen ? '−' : '+' }}</span></button>
            <div class="supported-formats"><span>GLB</span><span>POTREE</span><span>RAD</span><span>PNG/JPG</span></div><small>支持文件夹与 ZIP 压缩包<br />设计图支持多张图像上传</small>
            <div v-if="importOpen" class="import-form">
              <div class="import-tabs"><button v-for="item in categories.slice(1)" :key="item.key" type="button" :class="{ active: importCategory === item.key }" @click="selectImportCategory(item.key)">{{ item.label }}</button></div>
              <div v-if="importCategory !== 'DESIGN'" class="import-mode"><button type="button" :class="{ active: uploadMode === 'archive' }" @click="uploadMode = 'archive'">ZIP</button><button type="button" :class="{ active: uploadMode === 'folder' }" @click="uploadMode = 'folder'">文件夹</button></div>
              <input ref="folderInput" type="file" webkitdirectory directory multiple hidden @change="onFolderSelected" />
              <input ref="archiveInput" type="file" accept=".zip,application/zip" hidden @change="onArchiveSelected" />
              <input ref="imageInput" type="file" accept="image/*" multiple hidden @change="onFolderSelected" />
              <button type="button" class="outline-button" @click="importCategory === 'DESIGN' ? imageInput?.click() : uploadMode === 'archive' ? archiveInput?.click() : folderInput?.click()">选择{{ importCategory === 'DESIGN' ? '图像' : uploadMode === 'archive' ? '压缩包' : '文件夹' }}</button>
              <div class="import-selection">{{ selectedArchive?.name || (selectedFiles.length ? `已选择 ${selectedFiles.length} 个文件` : '尚未选择文件') }}<span v-if="uploadMessage">{{ uploadMessage }}</span></div>
              <div class="import-form-actions"><button type="button" class="clear-button" :disabled="uploading" @click="clearSelection">清空</button><button type="button" class="primary-button" :disabled="uploading" @click="submitUpload">{{ uploading ? '上传中…' : '开始入库' }}</button></div>
              <div v-if="uploading && uploadProgress" class="upload-progress"><i :style="{ width: `${uploadProgress}%` }"></i></div>
            </div>
          </section>
          <section v-if="currentTask" class="recent-task panel"><div class="task-symbol">≋</div><div><strong>{{ currentTask.modelName || currentTask.name || '最近入库任务' }}</strong><p>{{ currentTask.message || '正在建立数据集索引' }} <span class="task-percent">{{ taskPercent }}%</span></p><div class="progress"><i :style="{ width: `${taskPercent}%` }"></i></div></div><span class="badge pending">{{ statusLabel(currentTask.status) }}</span><small>任务状态</small></section>
        </section>
        <footer class="manage-footer"><span><i></i> 当前库快照 · 四类资产统一管理</span><span>最新入库 {{ latestAsset ? formatDate(latestAsset.createdAt) : '—' }}</span></footer>
      </main>
    </div>
  </div>
</template>

<style scoped>
.manage-content{max-width:1900px;padding-top:31px}.manage-heading{display:flex;align-items:flex-end;justify-content:space-between;gap:20px;padding-bottom:21px;border-bottom:1px solid var(--line)}.manage-kicker,.import-title p,.asset-heading p,.recent-task p{color:var(--muted);font:8px/1.5 Consolas,monospace;letter-spacing:2px}.manage-heading h1{margin-top:15px;font:400 34px/1.35 var(--display);letter-spacing:3px}.manage-heading>div>p:last-child{margin-top:10px;color:var(--muted);font:11px/1.7 "Microsoft YaHei",sans-serif}.refresh-button{padding:8px 10px;border:1px solid var(--line);background:transparent;color:var(--muted);cursor:pointer;font:10px "Microsoft YaHei",sans-serif}.refresh-button:hover{color:var(--red);border-color:var(--red)}.manage-overview{display:grid;grid-template-columns:repeat(4,1fr);margin-top:24px;border-top:1px solid var(--line);border-bottom:1px solid var(--line)}.overview-item{min-height:116px;padding:17px 20px;text-align:left;border:0;border-right:1px solid var(--line);background:transparent;cursor:pointer}.overview-item:last-child{border-right:0}.overview-item:hover,.overview-item.active{background:#f8f5ed}.overview-item span,.overview-item small{display:block;color:var(--muted);font:10px "Microsoft YaHei",sans-serif}.overview-item strong{display:block;margin:11px 0 5px;color:var(--ink);font:400 28px var(--display)}.overview-item.active strong{color:var(--red)}.overview-item small{font-size:8px}.import-panel{display:grid;grid-template-columns:185px minmax(0,1fr) auto 235px;align-items:center;gap:18px;margin-top:24px;padding:18px 20px;border:1px solid var(--line);background:var(--paper-soft)}.import-title h2,.asset-heading h2,.recent-task h2{margin-top:7px;font:400 19px/1.4 var(--display);letter-spacing:1px}.import-title span{display:block;margin-top:7px;color:var(--muted);font:9px "Microsoft YaHei",sans-serif}.import-tabs,.import-mode{display:flex;align-items:center;gap:8px;flex-wrap:wrap}.import-tabs button,.import-mode button,.clear-button,.outline-button{padding:8px 9px;border:1px solid var(--line);background:transparent;color:var(--muted);cursor:pointer;font:10px "Microsoft YaHei",sans-serif}.import-tabs button.active,.import-mode button.active{border-color:var(--red);color:var(--red);background:#eee5da}.import-actions{display:flex;gap:7px;align-items:center}.outline-button:hover,.clear-button:hover{color:var(--red);border-color:var(--red)}.primary-button{padding:9px 12px;border:0;background:var(--red);color:white;cursor:pointer;font:10px "Microsoft YaHei",sans-serif}.primary-button:hover{background:var(--red-dark)}button:disabled{cursor:not-allowed;opacity:.55}.import-selection{grid-column:2/4;overflow:hidden;color:var(--muted);font:9px Consolas,monospace;text-overflow:ellipsis;white-space:nowrap}.import-selection span{margin-left:14px;color:var(--red);font-family:"Microsoft YaHei",sans-serif}.upload-progress{grid-column:2/5;height:3px;background:#e4ded1}.upload-progress i,.task-progress i{display:block;height:100%;background:var(--red);transition:width .3s}.asset-section{margin-top:27px}.asset-heading{display:flex;align-items:flex-end;justify-content:space-between;gap:18px;padding-bottom:13px;border-bottom:1px solid var(--line)}.asset-tools{display:flex;align-items:center;gap:5px}.asset-tools label{display:flex;align-items:center;gap:6px;width:190px;border-bottom:1px solid var(--line);padding:5px 3px;color:var(--muted)}.asset-tools input{width:100%;border:0;outline:0;background:transparent;color:var(--ink);font:10px "Microsoft YaHei",sans-serif}.asset-tools button{width:28px;height:28px;border:1px solid var(--line);background:transparent;color:var(--muted);cursor:pointer}.asset-tools button.active,.asset-tools button:hover{border-color:var(--red);color:var(--red)}.asset-table{min-height:300px}.asset-item{display:grid;grid-template-columns:45px minmax(0,1fr) 105px 70px 125px;align-items:center;gap:14px;padding:15px 10px;border-bottom:1px solid var(--line)}.asset-item:hover{background:#f8f5ed}.asset-index{color:var(--clay);font:10px Consolas,monospace;letter-spacing:1px}.asset-main{min-width:0}.asset-main strong,.asset-main small{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.asset-main strong{font:12px "Microsoft YaHei",sans-serif}.asset-main small,.asset-item time{margin-top:5px;color:var(--muted);font:9px Consolas,monospace}.asset-item time{margin-top:0}.asset-status,.task-state{justify-self:start;padding:5px 7px;font:8px "Microsoft YaHei",sans-serif}.asset-status.ready,.task-state.ready{color:#627351;background:#e5e8dc}.asset-status.pending,.task-state.pending{color:#9a7444;background:#eee4d1}.asset-status.danger,.task-state.danger{color:var(--red);background:#eee0d9}.asset-actions{display:flex;justify-content:flex-end;gap:9px}.asset-actions button{border:0;background:transparent;color:var(--red);cursor:pointer;font:9px "Microsoft YaHei",sans-serif}.asset-actions button+button{color:var(--muted)}.grid-view{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px;padding-top:12px}.grid-view .asset-item{display:block;padding:17px;border:1px solid var(--line)}.grid-view .asset-index{display:block;margin-bottom:16px}.grid-view .asset-item time{display:block;margin-top:13px}.grid-view .asset-status{display:inline-block;margin-top:11px}.grid-view .asset-actions{justify-content:flex-start;margin-top:16px}.manage-empty{display:grid;place-items:center;min-height:260px;color:var(--muted);font:11px "Microsoft YaHei",sans-serif}.manage-empty strong{font:14px var(--display);font-weight:400;color:var(--ink)}.manage-empty span{margin-top:7px}.manage-error{margin-top:15px;padding:12px;border:1px solid #c6816b;color:var(--red);font:10px "Microsoft YaHei",sans-serif}.manage-error button{margin-left:10px;border:0;background:transparent;color:var(--red);text-decoration:underline;cursor:pointer}.recent-task{display:grid;grid-template-columns:1fr auto;gap:12px 20px;margin-top:25px;padding:18px 20px;border:1px solid var(--line);background:var(--paper-soft)}.recent-task h2{font-size:17px}.task-state{align-self:center}.task-progress{grid-column:1/-1;height:4px;background:#e4ded1}.recent-task dl{display:grid;grid-template-columns:repeat(3,1fr);grid-column:1/-1;gap:20px;margin:0}.recent-task dl div{display:flex;justify-content:space-between;gap:10px;border-top:1px solid var(--line);padding-top:9px}.recent-task dt{color:var(--muted);font:9px "Microsoft YaHei",sans-serif}.recent-task dd{margin:0;text-align:right;font:10px "Microsoft YaHei",sans-serif}.manage-footer{display:flex;justify-content:space-between;margin-top:18px;padding-top:14px;border-top:1px solid var(--line);color:var(--muted);font:8px Consolas,monospace}.manage-footer i{display:inline-block;width:5px;height:5px;margin-right:6px;border-radius:50%;background:var(--red)}
@media(max-width:1100px){.import-panel{grid-template-columns:1fr 1fr}.import-actions,.import-selection,.upload-progress{grid-column:auto}.asset-item{grid-template-columns:35px minmax(0,1fr) 85px 60px 110px}}
@media(max-width:720px){.manage-heading{display:block}.refresh-button{margin-top:15px}.manage-heading h1{font-size:27px}.manage-overview{grid-template-columns:repeat(2,1fr)}.overview-item:nth-child(2){border-right:0}.overview-item:nth-child(-n+2){border-bottom:1px solid var(--line)}.import-panel{display:block;padding:16px}.import-tabs,.import-mode,.import-actions{margin-top:14px}.import-selection{margin-top:12px}.upload-progress{margin-top:12px}.asset-heading{display:block}.asset-tools{margin-top:14px}.asset-item{grid-template-columns:32px minmax(0,1fr) 55px;gap:8px}.asset-item time{display:none}.asset-status{display:none}.asset-actions{grid-column:2/4;justify-content:flex-start;margin-top:8px}.grid-view{display:block}.grid-view .asset-item{margin-bottom:10px}.recent-task dl{grid-template-columns:1fr}.manage-footer{display:block;line-height:2}.manage-footer span{display:block}}

/* The heritage prototype uses a table-first archive with a narrow import card. */
.manage-content{max-width:1700px;padding-top:27px}
.manage-heading{align-items:flex-end;padding-bottom:20px}
.manage-heading h1{font:400 34px/1.35 var(--display);letter-spacing:2px}
.manage-overview{grid-template-columns:repeat(4,minmax(0,1fr));background:none;border:0;border-bottom:1px solid var(--line);border-radius:0;padding:5px 0 28px;margin:0 0 24px}
.manage-overview .overview-item{min-height:0;padding:0 28px;border:0;border-right:1px solid var(--line);background:none}
.manage-overview .overview-item:first-child{padding-left:0}
.manage-overview .overview-item:last-child{border-right:0}
.manage-overview .overview-item:hover,.manage-overview .overview-item.active{background:none}
.manage-overview .overview-item span{font-size:10px}
.manage-overview .overview-item strong{font:36px Georgia,serif;margin-top:13px}
.manage-overview .overview-item strong small{display:inline;font:9px "Microsoft YaHei",sans-serif;margin-left:8px;color:var(--muted)}
.manage-layout{display:grid;grid-template-columns:minmax(0,1fr);gap:24px}
.asset-table{grid-column:1;grid-row:2;background:transparent;border:1px solid #d3cbb9;border-radius:0;min-height:560px;overflow:hidden}
.table-heading{display:flex;align-items:center;gap:14px;padding:23px 25px}
.table-heading>div:first-child{margin-right:auto}
.table-heading h2{font:400 19px var(--display);letter-spacing:2px}
.result-count{font-size:9px;color:var(--muted);display:block;margin-top:4px}
.search-field{display:flex;align-items:center;gap:6px;width:210px;border-bottom:1px solid var(--line);padding:5px 3px;color:var(--muted)}
.search-field input{width:100%;border:0;outline:0;background:transparent;color:var(--ink);font:10px "Microsoft YaHei",sans-serif}
.view-switch{display:flex;gap:2px}
.view-switch button{width:29px;height:29px;border:1px solid var(--line);background:transparent;color:var(--muted);cursor:pointer}
.view-switch button.active,.view-switch button:hover{color:var(--red);border-color:var(--red)}
.asset-filters{display:flex;gap:32px;padding:0 26px;border-bottom:1px solid var(--line);overflow:auto}
.asset-filters button{display:flex;align-items:center;gap:7px;padding:0 0 13px;border:0;border-bottom:2px solid transparent;background:transparent;color:var(--muted);font:10px "Microsoft YaHei",sans-serif;white-space:nowrap;cursor:pointer}
.asset-filters button.active{color:var(--red);border-bottom-color:var(--red)}
.asset-filters button span{font:8px Consolas,monospace;background:#e9e3d5;padding:2px 4px;border-radius:3px;color:var(--muted)}
.table-scroll{overflow-x:auto}
.table-scroll table{border-collapse:collapse;width:100%;white-space:nowrap;font-size:10px}
.table-scroll th{text-align:left;padding:14px 12px;background:#e9e3d5;color:#867b68;font:9px "Microsoft YaHei",sans-serif;font-weight:400}
.table-scroll td{padding:20px 12px;border-bottom:1px solid var(--line)}
.table-scroll th:first-child,.table-scroll td:first-child{padding-left:18px;width:36px}
.table-scroll th:last-child,.table-scroll td:last-child{padding-right:16px;text-align:right}
.table-scroll td:nth-child(7){font:9px Consolas,monospace;color:var(--muted)}
.table-scroll input[type=checkbox]{width:12px;height:12px;accent-color:var(--red);cursor:pointer}
.asset-name{display:flex;gap:10px;align-items:center}
.asset-name strong{font-size:12px;font-weight:500;display:block}
.asset-name small{font:8px Consolas,monospace;display:block;color:var(--muted);margin-top:4px}
.asset-symbol{width:33px;height:33px;border-radius:2px;background:#e9e3d5;color:var(--red);display:grid;place-items:center;flex:none;font:16px Georgia,serif}
.asset-symbol.type-POINT_CLOUD{color:#5b8aa2;background:#6f9aa815}
.asset-symbol.type-GAUSSIAN{color:#9280aa;background:#9280aa15}
.asset-symbol.type-DESIGN{color:#aa8760;background:#aa876015}
.format-label{font:8px Consolas,monospace;background:#e9e3d5;padding:3px 5px;border-radius:3px;color:var(--muted)}
.badge{display:inline-block;font:8px "Microsoft YaHei",sans-serif;padding:3px 6px}
.badge.ready{color:#627351;background:#e5e8dc}.badge.pending{color:#9a7444;background:#eee4d1}.badge.danger{color:var(--red);background:#eee0d9}
.table-action{font:9px "Microsoft YaHei",sans-serif;border:0;background:none;padding:4px;color:var(--red);cursor:pointer}
.table-action.danger{color:#b09b91}.table-action.danger:hover{color:#b94d43}
.asset-table>footer{display:flex;justify-content:space-between;padding:17px 26px;color:var(--muted);font-size:9px}
.import-panel{grid-column:1;grid-row:1;display:grid;grid-template-columns:56px 1fr 1fr 180px;align-items:center;gap:5px 22px;margin:0;padding:20px 24px;background:#eae3d6;border:0;border-radius:0}
.upload-mark{grid-column:1;grid-row:1/3;width:52px;height:52px;background:transparent;border:1px solid #c4b39d;border-radius:0;display:grid;place-items:center;color:var(--red);font:25px Georgia,serif;margin:0}
.import-panel .eyebrow{grid-column:2;grid-row:1;margin:0;font:8px Consolas,monospace;letter-spacing:1px;color:var(--muted)}
.import-panel h2{grid-column:2;grid-row:2;font:400 20px/1.65 var(--display);letter-spacing:1px}
.import-panel h2 br{display:none}
.import-panel>p:not(.eyebrow){grid-column:3;grid-row:1/3;font-size:10px;color:var(--muted);line-height:1.9;margin:0}
.import-trigger{grid-column:4;grid-row:1/3;width:100%;margin:0;padding:10px 13px;border:0;background:var(--red);color:#fff;cursor:pointer;font:10px "Microsoft YaHei",sans-serif;text-align:center}
.import-trigger span{margin-left:8px;font-size:14px}
.supported-formats{display:none;gap:5px;margin-top:35px;flex-wrap:wrap}
.supported-formats span{font:8px Consolas,monospace;padding:5px;border:1px solid var(--line);border-radius:3px;color:var(--muted)}
.import-panel>small{display:none;font-size:9px;color:var(--muted);line-height:1.9;margin-top:12px}
.import-form{grid-column:1/-1;width:100%;margin-top:15px;padding-top:17px;border-top:1px solid var(--line)}
.import-tabs,.import-mode{display:flex;gap:5px;flex-wrap:wrap;margin-bottom:10px}
.import-tabs button,.import-mode button,.outline-button,.clear-button{padding:6px 7px;border:1px solid var(--line);background:transparent;color:var(--muted);cursor:pointer;font:9px "Microsoft YaHei",sans-serif}
.import-tabs button.active,.import-mode button.active{border-color:var(--red);color:var(--red);background:#eee5da}
.import-selection{overflow:hidden;color:var(--muted);font:8px Consolas,monospace;text-overflow:ellipsis;white-space:nowrap;margin-top:10px}
.import-selection span{display:block;margin-top:5px;color:var(--red);font-family:"Microsoft YaHei",sans-serif}
.import-form-actions{display:flex;gap:6px;margin-top:10px}
.primary-button{padding:7px 9px;border:0;background:var(--red);color:#fff;cursor:pointer;font:9px "Microsoft YaHei",sans-serif}
.upload-progress{height:3px;margin-top:10px;background:#e4ded1}.upload-progress i,.progress i{display:block;height:100%;background:var(--red);transition:width .3s}
.recent-task{grid-column:1/-1;grid-row:3;display:flex;gap:18px;align-items:center;margin:0;padding:18px 0;background:transparent;border:0;border-top:1px solid var(--line);border-bottom:0;border-radius:0}
.task-symbol{width:42px;height:42px;display:grid;place-items:center;background:#e9e3d5;color:var(--red);border-radius:2px;font-size:22px}
.recent-task>div:nth-child(2){flex:1;max-width:600px}.recent-task strong{font-size:11px;font-weight:500}.recent-task p{font-size:9px;color:var(--muted);margin-top:6px}.task-percent{float:right;font-family:Consolas,monospace}.progress{height:4px;border-radius:3px;background:#e4ded1;margin-top:8px;overflow:hidden}.recent-task .badge{margin-left:20px}.recent-task>small{margin-left:auto;color:var(--muted);font-size:9px}
.no-results{text-align:center;padding:50px;color:var(--muted);font:10px "Microsoft YaHei",sans-serif}.no-results strong,.no-results span{display:block}.no-results strong{font:14px var(--display);font-weight:400;color:var(--ink)}.no-results span{margin-top:7px}
.cards-mode .table-scroll table,.cards-mode .table-scroll thead{display:none}.cards-mode .table-scroll tbody{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px;padding:18px}.cards-mode .table-scroll tbody tr{display:grid;grid-template-columns:30px 1fr 1fr;border:1px solid var(--line);padding:8px}.cards-mode .table-scroll td{border:0;padding:6px 5px;font-size:9px}.cards-mode .table-scroll td:nth-child(2){grid-column:2/-1}.cards-mode .table-scroll td:nth-child(3){display:none}.cards-mode .table-scroll td:nth-child(4){grid-column:2}.cards-mode .table-scroll td:last-child{grid-column:2/-1;text-align:left}
@media(max-width:1100px){.manage-layout{grid-template-columns:minmax(0,1fr)}.import-panel{grid-column:1;grid-row:1;grid-template-columns:45px 1fr 1fr 155px}.asset-table{grid-row:2}.recent-task{grid-row:3}.table-scroll th:nth-child(7),.table-scroll td:nth-child(7){display:none}}
@media(max-width:720px){.manage-overview{grid-template-columns:repeat(2,minmax(0,1fr));row-gap:20px}.manage-overview .overview-item{padding:0 15px}.manage-overview .overview-item:nth-child(3){padding-left:0;border-right:0}.manage-overview .overview-item:nth-child(4){padding-left:15px}.manage-overview .overview-item:nth-child(-n+2){border-bottom:0}.table-heading{display:block;padding:20px}.search-field{width:100%;margin-top:14px}.view-switch{display:none}.asset-filters{padding:0 20px;gap:25px}.table-scroll th:nth-child(3),.table-scroll td:nth-child(3),.table-scroll th:nth-child(5),.table-scroll td:nth-child(5){display:none}.table-scroll td{padding:14px 7px}.table-scroll th:first-child,.table-scroll td:first-child{padding-left:12px}.import-panel{padding:20px}.recent-task{display:grid;grid-template-columns:42px 1fr}.recent-task .badge,.recent-task>small{grid-column:2;margin-left:0}.recent-task>small{margin:0}.cards-mode .table-scroll tbody{grid-template-columns:1fr}}
</style>
