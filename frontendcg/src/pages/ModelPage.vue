<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { RouterLink } from 'vue-router'
import * as THREE from '../assets/modelLOD/build/three.module.js'
import { LODScene, Viewer, modelLod } from '../assets/modelLOD/build/fullStackLodControl.js'

const API_BASE = (import.meta.env.VITE_API_BASE_URL || '').replace(/\/$/, '')
const WS_BASE = (import.meta.env.VITE_WS_URL || (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host).replace(/\/$/, '')
const navigation = [
  ['首页', '/'], ['模型展示', '/model'], ['点云展示', '/pointcloud'],
  ['高斯渲染', '/gaussian'], ['设计图预览', '/design'], ['数据管理', '/manage'], ['信息统计', '/stats'],
]
const models = ref([])
const diseases = ref([])
const modelQuery = ref('')
const diseaseQuery = ref('')
const selectedModel = ref(null)
const activeDisease = ref(null)
const loadingModels = ref(false)
const loadingModelId = ref(null)
const statusMessage = ref('正在连接模型服务')
const modelLoadError = ref('')
const rendererError = ref('')
const notice = ref('')
const noticeKind = ref('info')
const canvas = ref(null)
const viewport = ref(null)
const fileInput = ref(null)
const hasModel = ref(false)
const showDiseaseLabels = ref(true)
const showBounds = ref(false)
const selectingDisease = ref(false)
const diseaseDialog = ref(false)
const polygonScreen = ref([])
const polygonWorld = ref([])
const currentView = ref('')
const solidColor = ref('#e3d5bd')
const telemetry = reactive({ nodes: 0, triangles: 0, lod: '--', fps: '--' })
const diseaseForm = reactive({ type: '', severity: '中度', recorder: '', description: '', date: '' })
const filteredModels = computed(() => models.value.filter(item => `${item.name || ''} ${item.id || ''}`.toLowerCase().includes(modelQuery.value.trim().toLowerCase())))
const filteredDiseases = computed(() => diseases.value.filter(item => `${item.type || ''} ${item.id || ''} ${item.recorder || ''}`.toLowerCase().includes(diseaseQuery.value.trim().toLowerCase())))
const modelCount = computed(() => String(models.value.length).padStart(2, '0'))
const api = (path, options) => fetch(`${API_BASE}${path}`, options)

let scene
let viewer
let lodManager
let ws
let resizeObserver
let generation = 0
let abortController
let octreeIds = []
let cameraTimer
let requestId = 0
let activeRequestId = 0
let cameraDirty = false
let backendBox
let fpsFrames = 0
let fpsStart = 0
let frameId
let rendererReady = false
let diseaseHighlightGroup

function notify(message, kind = 'info') {
  notice.value = message
  noticeKind.value = kind
  window.clearTimeout(notify.timer)
  notify.timer = window.setTimeout(() => { notice.value = '' }, 2800)
}

function scrollToSection(id) {
  document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'center' })
}

async function fetchModels() {
  loadingModels.value = true
  modelLoadError.value = ''
  try {
    const response = await api('/api/data/models/list')
    if (!response.ok) throw new Error(`HTTP ${response.status}`)
    const result = await response.json()
    if (!Array.isArray(result)) throw new Error('模型服务返回的数据格式无效')
    models.value = result
    statusMessage.value = models.value.length ? `${models.value.length} 项模型资源` : '暂无模型资源'
  } catch (error) {
    models.value = []
    modelLoadError.value = error.message
    statusMessage.value = '模型服务暂不可用'
    notify(`模型目录加载失败：${error.message}`, 'error')
  } finally {
    loadingModels.value = false
  }
}

function updateTelemetry() {
  const visible = scene?.visibleModels || []
  telemetry.nodes = visible.length || lodManager?.nodeModels?.size || 0
  let triangles = 0
  for (const model of visible) {
    model?.traverse?.(object => {
      const position = object.geometry?.attributes?.position
      if (position) triangles += (object.geometry.index?.count || position.count) / 3
    })
  }
  telemetry.triangles = Math.round(triangles)
  telemetry.lod = hasModel.value ? '自动' : '--'
}

function cameraSignature() {
  if (!scene?.camera) return ''
  const p = scene.camera.position
  return `${p.x.toFixed(2)},${p.y.toFixed(2)},${p.z.toFixed(2)}:${scene.camera.fov}:${viewport.value?.clientHeight}`
}

function sendCameraUpdate() {
  window.clearTimeout(cameraTimer)
  if (!hasModel.value || !octreeIds.length || ws?.readyState !== WebSocket.OPEN || !scene?.camera) return
  const signature = cameraSignature()
  if (signature === sendCameraUpdate.lastSignature && !cameraDirty) return
  if (activeRequestId) {
    cameraDirty = true
    return
  }
  scene.camera.updateMatrixWorld(true)
  const frustum = new THREE.Frustum()
  frustum.setFromMatrix(new THREE.Matrix4().multiplyMatrices(
    scene.camera.projectionMatrix,
    scene.camera.matrixWorldInverse,
  ))
  activeRequestId = ++requestId
  cameraDirty = false
  sendCameraUpdate.lastSignature = signature
  ws.send(JSON.stringify({
    requestId: activeRequestId,
    action: 'updateVisibility',
    dataType: 'MODEL',
    octreeIds,
    viewportHeight: scene.canvas?.clientHeight || window.innerHeight,
    cameraFov: scene.camera.fov,
    camera: { position: [scene.camera.position.x, scene.camera.position.y, scene.camera.position.z] },
    frustum: frustum.planes.map(plane => [plane.normal.x, plane.normal.y, plane.normal.z, plane.constant]),
  }))
}

function scheduleCameraUpdate() {
  cameraDirty = true
  window.clearTimeout(cameraTimer)
  cameraTimer = window.setTimeout(sendCameraUpdate, 90)
}

function connectScheduler() {
  ws?.close()
  ws = new WebSocket(`${WS_BASE}/api/scheduling/ws`)
  ws.onopen = () => { statusMessage.value = hasModel.value ? '调度已连接' : '调度服务已连接'; sendCameraUpdate() }
  ws.onmessage = event => {
    try {
      const data = JSON.parse(event.data)
      if (data.action !== 'updateVisibility' || Number(data.requestId) !== activeRequestId) return
      activeRequestId = 0
      lodManager?.updateVisibleNodesFromBackend(data.visibleNodes || [], scene)
      updateTelemetry()
      if (cameraDirty) sendCameraUpdate()
    } catch (error) {
      console.error('[model] 无法解析调度响应', error)
    }
  }
  ws.onerror = () => { statusMessage.value = '调度连接异常' }
  ws.onclose = () => {
    if (!disposed) reconnectTimer = window.setTimeout(connectScheduler, 2500)
  }
}

let reconnectTimer
let disposed = false

function fitCamera(box) {
  if (!scene?.camera || !scene?.controls || !box || box.isEmpty()) return
  const center = box.getCenter(new THREE.Vector3())
  const radius = Math.max(box.getSize(new THREE.Vector3()).length() / 2, 1)
  const distance = radius / Math.sin(scene.camera.fov * Math.PI / 360) * 1.15
  scene.camera.near = Math.max(0.01, radius / 10000)
  scene.camera.far = Math.max(10000, radius * 40)
  scene.camera.position.copy(center).add(new THREE.Vector3(1, 0.72, 1).normalize().multiplyScalar(distance))
  scene.controls.target.copy(center)
  scene.camera.updateProjectionMatrix()
  scene.controls.update()
}

async function loadModel(model) {
  if (!rendererReady || !scene || !lodManager) {
    notify(rendererError.value || '三维场景尚未初始化完成，请稍后重试', 'error')
    return
  }
  await clearScene()
  const current = generation
  abortController = new AbortController()
  loadingModelId.value = model.id
  selectedModel.value = model
  try {
    const response = await api(`/api/data/models/${encodeURIComponent(model.id)}/octrees`, { signal: abortController.signal })
    if (!response.ok) throw new Error(`八叉树请求失败：${response.status}`)
    const octrees = await response.json()
    if (!Array.isArray(octrees) || !octrees.length) throw new Error('该模型没有可用的八叉树数据')
    if (current !== generation) return
    octreeIds = octrees.map(item => item.id)
    const roots = octrees.map(item => `${item.id}_${item.rootNodeCode || '1'}`)
    lodManager.setRootNodeIds(roots)
    lodManager.setBaseUrl(`${API_BASE}/api/data/files/`)
    const rootCode = octrees[0].rootNodeCode || '1'
    const rootResponse = await api(`/api/data/files/${octreeIds[0]}/${rootCode}/${rootCode}.json`, { signal: abortController.signal })
    if (rootResponse.ok) {
      const root = await rootResponse.json()
      const bounds = root.infactAabbBox || root.boundingBox
      if (bounds) {
        backendBox = new THREE.Box3(
          new THREE.Vector3(bounds.lx, bounds.ly, bounds.lz),
          new THREE.Vector3(bounds.ux, bounds.uy, bounds.uz),
        )
        fitCamera(backendBox)
      }
    }
    if (current !== generation) return
    hasModel.value = true
    statusMessage.value = '模型已加载，正在调度可视节点'
    await fetchDiseases(model.id, current)
    cameraDirty = true
    sendCameraUpdate()
  } catch (error) {
    if (error.name !== 'AbortError' && current === generation) {
      notify(`模型加载失败：${error.message}`, 'error')
      await clearScene()
    }
  } finally {
    if (current === generation) loadingModelId.value = null
  }
}

async function fetchDiseases(modelId, expectedGeneration = generation) {
  try {
    const response = await api(`/api/data/diseases?modelId=${encodeURIComponent(modelId)}`)
    if (!response.ok) return
    const result = await response.json()
    if (expectedGeneration === generation) {
      diseases.value = Array.isArray(result) ? result : []
      rebuildDiseaseHighlights()
    }
  } catch (error) {
    console.error('[model] 病害列表加载失败', error)
  }
}

async function clearScene() {
  generation++
  abortController?.abort()
  abortController = undefined
  window.clearTimeout(cameraTimer)
  activeRequestId = 0
  cameraDirty = false
  lodManager?.resetBackendState()
  scene?.clearModels()
  if (scene) {
    scene.localLodEnabled = false
    scene.visibleModels = []
  }
  hasModel.value = false
  selectedModel.value = null
  octreeIds = []
  backendBox = null
  diseases.value = []
  activeDisease.value = null
  clearDiseaseHighlights()
  polygonScreen.value = []
  polygonWorld.value = []
  selectingDisease.value = false
  diseaseDialog.value = false
  telemetry.nodes = 0
  telemetry.triangles = 0
  telemetry.lod = '--'
  statusMessage.value = models.value.length ? '场景已清空' : '暂无模型资源'
}

function setEntityColor(color) {
  solidColor.value = color
  scene?.updateModelColors?.(new THREE.Color(color))
}

function alignView(view) {
  currentView.value = view
  if (!hasModel.value || !backendBox || backendBox.isEmpty()) {
    notify('请先加载模型，再切换视角')
    return
  }
  if (view === 'front' || view === 'back' || view === 'left' || view === 'right' || view === 'top' || view === 'bottom') {
    const center = backendBox.getCenter(new THREE.Vector3())
    const radius = Math.max(backendBox.getSize(new THREE.Vector3()).length() / 2, 1)
    const distance = radius / Math.sin(scene.camera.fov * Math.PI / 360) * 1.15
    const directions = {
      front: new THREE.Vector3(0, 0, 1),
      back: new THREE.Vector3(0, 0, -1),
      left: new THREE.Vector3(-1, 0, 0),
      right: new THREE.Vector3(1, 0, 0),
      top: new THREE.Vector3(0, 1, 0),
      bottom: new THREE.Vector3(0, -1, 0),
    }
    const direction = directions[view]
    scene.camera.position.copy(center).addScaledVector(direction, distance)
    scene.camera.up.set(0, 1, 0)
    if (view === 'top' || view === 'bottom') scene.camera.up.set(0, 0, view === 'top' ? -1 : 1)
    scene.controls.target.copy(center)
    scene.camera.lookAt(center)
    scene.controls.update()
  }
  scheduleCameraUpdate()
}

function resetCamera() {
  fitCamera(backendBox)
  scheduleCameraUpdate()
}

function toggleBounds(event) {
  showBounds.value = event.target.checked
  scene?.toggleBoundingBox?.(showBounds.value)
  lodManager?.setBoundingBoxVisible(showBounds.value, scene)
}

function onResize() {
  scene?.resizeDisplayGL?.()
  scheduleCameraUpdate()
}

function resetSettings() {
  setEntityColor('#e3d5bd')
  showBounds.value = false
  if (scene) {
    scene.toggleBoundingBox?.(false)
    lodManager?.setBoundingBoxVisible(false, scene)
  }
  showDiseaseLabels.value = true
  rebuildDiseaseHighlights()
  scheduleCameraUpdate()
}

function normalizeDiseasePoints(record) {
  const source = record?.worldPolygon || record?.points || record?.polygon || []
  if (!Array.isArray(source)) return []
  return source.map(point => {
    if (Array.isArray(point)) {
      return new THREE.Vector3(Number(point[0]), Number(point[1]), Number(point[2]))
    }
    return new THREE.Vector3(Number(point?.x), Number(point?.y), Number(point?.z))
  }).filter(point => point.toArray().every(Number.isFinite))
}

function clearDiseaseHighlights() {
  if (!diseaseHighlightGroup) return
  while (diseaseHighlightGroup.children.length) {
    const object = diseaseHighlightGroup.children[diseaseHighlightGroup.children.length - 1]
    diseaseHighlightGroup.remove(object)
    object.geometry?.dispose()
    if (Array.isArray(object.material)) object.material.forEach(material => material.dispose())
    else object.material?.dispose()
  }
}

function createDiseaseHighlight(record) {
  const points = normalizeDiseasePoints(record)
  if (points.length < 2) return
  const selected = activeDisease.value?.id === record.id
  const group = new THREE.Group()
  const linePoints = [...points, points[0]]
  const line = new THREE.Line(
    new THREE.BufferGeometry().setFromPoints(linePoints),
    new THREE.LineBasicMaterial({
      color: selected ? 0xffd36a : 0xff5b4d,
      transparent: true,
      opacity: 1,
      depthTest: false,
    }),
  )
  line.renderOrder = 1001
  group.add(line)

  if (points.length >= 3) {
    const vertices = []
    for (let index = 1; index < points.length - 1; index += 1) {
      vertices.push(points[0].x, points[0].y, points[0].z)
      vertices.push(points[index].x, points[index].y, points[index].z)
      vertices.push(points[index + 1].x, points[index + 1].y, points[index + 1].z)
    }
    const fill = new THREE.Mesh(
      new THREE.BufferGeometry().setAttribute('position', new THREE.Float32BufferAttribute(vertices, 3)),
      new THREE.MeshBasicMaterial({
        color: selected ? 0xffc45c : 0xf04b3e,
        transparent: true,
        opacity: selected ? 0.42 : 0.24,
        side: THREE.DoubleSide,
        depthTest: false,
        depthWrite: false,
      }),
    )
    fill.renderOrder = 1000
    group.add(fill)
  }

  const markers = new THREE.Points(
    new THREE.BufferGeometry().setFromPoints(points),
    new THREE.PointsMaterial({
      color: selected ? 0xfff0a6 : 0xff8a7d,
      size: selected ? 9 : 6,
      sizeAttenuation: false,
      transparent: true,
      opacity: 0.95,
      depthTest: false,
    }),
  )
  markers.renderOrder = 1002
  group.add(markers)
  diseaseHighlightGroup.add(group)
}

function rebuildDiseaseHighlights() {
  clearDiseaseHighlights()
  if (!showDiseaseLabels.value || !diseaseHighlightGroup || !hasModel.value) return
  diseases.value.forEach(createDiseaseHighlight)
}

function toggleDiseaseLabels(event) {
  showDiseaseLabels.value = event.target.checked
  rebuildDiseaseHighlights()
}

function pointerToWorld(event) {
  if (!scene?.camera || !scene?.renderer || !scene?.scene) return null
  const rect = canvas.value.getBoundingClientRect()
  const ndc = new THREE.Vector2((event.clientX - rect.left) / rect.width * 2 - 1, -((event.clientY - rect.top) / rect.height) * 2 + 1)
  const ray = new THREE.Raycaster()
  ray.setFromCamera(ndc, scene.camera)
  const meshes = []
  scene.scene.traverse(object => { if (object.isMesh && object.visible) meshes.push(object) })
  const hit = ray.intersectObjects(meshes, false)[0]
  if (hit) return hit.point.clone()
  const direction = new THREE.Vector3()
  scene.camera.getWorldDirection(direction)
  return scene.camera.position.clone().addScaledVector(direction, 50)
}

function addDiseasePoint(event) {
  if (!selectingDisease.value || event.detail > 1) return
  const rect = viewport.value.getBoundingClientRect()
  polygonScreen.value.push({ x: event.clientX - rect.left, y: event.clientY - rect.top })
  const world = pointerToWorld(event)
  if (world) polygonWorld.value.push({ x: world.x, y: world.y, z: world.z })
}

function finishDiseaseSelection() {
  if (polygonWorld.value.length < 3) {
    notify('请至少选择三个顶点', 'error')
    return
  }
  diseaseForm.date = new Date().toISOString().slice(0, 10)
  diseaseDialog.value = true
}

function cancelDiseaseSelection() {
  selectingDisease.value = false
  diseaseDialog.value = false
  polygonScreen.value = []
  polygonWorld.value = []
}

async function saveDisease() {
  if (!diseaseForm.type || !diseaseForm.recorder.trim()) {
    notify('请填写病害类型和录入人', 'error')
    return
  }
  try {
    const response = await api('/api/data/diseases', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        modelId: selectedModel.value?.id,
        type: diseaseForm.type,
        severity: diseaseForm.severity,
        recordDate: diseaseForm.date,
        recorder: diseaseForm.recorder.trim(),
        description: diseaseForm.description,
        coordinateSystem: 'MODEL_WORLD',
        points: polygonWorld.value,
      }),
    })
    if (!response.ok) throw new Error(`HTTP ${response.status}`)
    const saved = await response.json()
    diseases.value.unshift(saved)
    rebuildDiseaseHighlights()
    cancelDiseaseSelection()
    Object.assign(diseaseForm, { type: '', severity: '中度', recorder: '', description: '', date: '' })
    notify('病害记录已保存', 'success')
  } catch (error) {
    notify(`保存病害失败：${error.message}`, 'error')
  }
}

async function deleteDisease(record) {
  if (!window.confirm(`确定删除病害记录“${record.type || record.id}”？`)) return
  try {
    const response = await api(`/api/data/diseases/${encodeURIComponent(record.id)}`, { method: 'DELETE' })
    if (!response.ok) throw new Error(`HTTP ${response.status}`)
    diseases.value = diseases.value.filter(item => item.id !== record.id)
    if (activeDisease.value?.id === record.id) activeDisease.value = null
    rebuildDiseaseHighlights()
    notify('病害记录已删除', 'success')
  } catch (error) {
    notify(`删除病害失败：${error.message}`, 'error')
  }
}

function selectDisease(record) {
  activeDisease.value = record
  rebuildDiseaseHighlights()
  const points = normalizeDiseasePoints(record)
  if (points.length) {
    const box = new THREE.Box3()
    points.forEach(point => box.expandByPoint(point))
    fitCamera(box)
    scheduleCameraUpdate()
  }
}

function loadLocalFiles(event) {
  notify('本地目录加载功能由原始 GLB/八叉树目录格式提供，当前保留服务端模型加载。')
  event.target.value = ''
}

function measureFps() {
  if (disposed) return
  fpsFrames++
  const now = performance.now()
  if (!fpsStart) fpsStart = now
  if (now - fpsStart >= 1000) {
    telemetry.fps = `${Math.round(fpsFrames * 1000 / (now - fpsStart))} FPS`
    fpsFrames = 0
    fpsStart = now
  }
  frameId = requestAnimationFrame(measureFps)
}

onMounted(async () => {
  disposed = false
  await nextTick()
  try {
    scene = new LODScene(canvas.value, null, null, null, null, null)
    scene.initGL()
    if (!scene.renderer || !scene.camera || !scene.controls) {
      throw new Error('三维场景初始化失败')
    }
    scene.renderer?.setClearColor(0x000000, 1)
    if (scene.scene) scene.scene.background = new THREE.Color(0x000000)
    scene.resizeDisplayGL()
    scene.controls.keys = []
    viewer = new Viewer(scene)
    viewer.start()
    lodManager = new modelLod()
    diseaseHighlightGroup = new THREE.Group()
    diseaseHighlightGroup.name = 'disease-highlights'
    diseaseHighlightGroup.renderOrder = 1000
    scene.scene.add(diseaseHighlightGroup)
    rendererReady = true
    scene.controls?.addEventListener('change', scheduleCameraUpdate)
    scene.renderer?.domElement?.addEventListener('wheel', scheduleCameraUpdate, { passive: true })
    resizeObserver = new ResizeObserver(onResize)
    resizeObserver.observe(viewport.value)
  } catch (error) {
    rendererReady = false
    rendererError.value = error.message || 'WebGL 初始化失败'
    statusMessage.value = models.value.length
      ? `${models.value.length} 项模型资源 · 三维画布不可用`
      : '三维画布不可用'
    console.error('[model] Three.js 初始化失败', error)
  }
  await fetchModels()
  if (scene && lodManager) connectScheduler()
  frameId = requestAnimationFrame(measureFps)
})

onBeforeUnmount(() => {
  disposed = true
  rendererReady = false
  generation++
  abortController?.abort()
  window.clearTimeout(cameraTimer)
  window.clearTimeout(reconnectTimer)
  window.cancelAnimationFrame(frameId)
  resizeObserver?.disconnect()
  ws?.close()
  scene?.controls?.removeEventListener('change', scheduleCameraUpdate)
  viewer?.stop()
  lodManager?.resetBackendState()
  scene?.clearModels()
  clearDiseaseHighlights()
  if (diseaseHighlightGroup?.parent) diseaseHighlightGroup.parent.remove(diseaseHighlightGroup)
})
</script>

<template>
  <div class="heritage-shell model-shell">
    <aside class="heritage-spine" aria-label="天坛数字遗产">
      <RouterLink class="seal" to="/" aria-label="返回首页">天<br>坛</RouterLink>
      <p>让遗产在数字中延续</p>
      <span>TIANTAN<br>1530 — 2026</span>
    </aside>
    <div class="heritage-body">
      <header class="global-top">
        <RouterLink class="brand" to="/"><strong>天坛 · 数字遗产</strong><small>TEMPLE OF HEAVEN</small></RouterLink>
        <nav aria-label="主导航">
          <RouterLink v-for="[label, to] in navigation" :key="to" :to="to" class="nav-link" :class="{ active: to === '/model' }">{{ label }}</RouterLink>
        </nav>
        <button class="help-button" type="button" aria-label="帮助" title="帮助">?</button>
      </header>

      <main class="main-content model-content">
        <div class="page-heading">
          <div><span class="eyebrow"><i>MODEL</i> / EXPLORER</span><h1>理解建筑，从空间开始。</h1><p>按需加载建筑模型，查看构件、层级与病害信息。</p></div>
          <RouterLink class="manage-link" to="/manage">管理资源 <span aria-hidden="true">→</span></RouterLink>
        </div>

        <div class="workspace-toolbar">
          <strong>模型工作空间</strong>
          <div class="toolbar-actions">
            <button type="button" @click="scrollToSection('scene-settings')"><span class="tool-icon">☷</span>场景控制</button>
            <button type="button" @click="scrollToSection('runtime-info')"><span class="tool-icon">▥</span>实时信息</button>
            <button type="button" @click="scrollToSection('disease-list')"><span class="tool-icon">▤</span>病害列表</button>
          </div>
          <span class="workspace-status">{{ statusMessage }}</span>
        </div>

        <section class="viewer-layout">
          <aside class="library-column">
            <div class="eyebrow">COLLECTION</div>
            <div class="panel-heading"><h2>模型资源</h2><span class="count-badge">{{ modelCount }}</span></div>
            <label class="search-field"><span aria-hidden="true">⌕</span><input v-model="modelQuery" type="search" placeholder="搜索名称或编号" aria-label="搜索模型"></label>
            <div class="resource-list" aria-live="polite">
              <article v-for="model in filteredModels" :key="model.id" class="resource-row" :class="{ selected: selectedModel?.id === model.id }">
                <div class="resource-copy"><strong>{{ model.name || `模型 ${model.id}` }}</strong><small>{{ model.type || '三维模型' }}<template v-if="model.size"> · {{ model.size }}</template></small></div>
                <button class="resource-button" type="button" :disabled="loadingModelId === model.id" @click="selectedModel?.id === model.id ? clearScene() : loadModel(model)">{{ loadingModelId === model.id ? '加载中…' : selectedModel?.id === model.id ? '卸载　×' : '加载　＋' }}</button>
              </article>
              <p v-if="loadingModels" class="empty-state">正在读取模型目录…</p>
              <div v-else-if="modelLoadError" class="empty-state resource-error">
                <span>模型目录加载失败</span>
                <button type="button" @click="fetchModels">重试</button>
              </div>
              <p v-else-if="!filteredModels.length" class="empty-state">{{ models.length ? '没有匹配的模型' : '暂无模型资源' }}</p>
            </div>
            <button class="manage-resource" type="button" @click="fetchModels">▱　管理模型资源 <span>↗</span></button>

            <section id="disease-list" class="disease-list">
              <div class="panel-heading"><h2>病害列表</h2><span class="disease-count">{{ String(diseases.length).padStart(2, '0') }}</span></div>
              <label class="search-field"><span aria-hidden="true">⌕</span><input v-model="diseaseQuery" type="search" placeholder="搜索病害名称" aria-label="搜索病害"></label>
              <article v-for="record in filteredDiseases" :key="record.id" class="disease-row" :class="{ selected: activeDisease?.id === record.id }" @click="selectDisease(record)">
                <div class="disease-summary">
                  <strong>{{ record.type || '未命名病害' }}</strong>
                  <small>{{ record.severity || '未标注程度' }} · {{ record.recorder || '录入人未记录' }}</small>
                </div>
                <div class="disease-actions"><button type="button" @click.stop="selectDisease(record)">⊙ 定位</button><button type="button" @click.stop="deleteDisease(record)">⌫ 删除</button></div>
              </article>
              <p v-if="!filteredDiseases.length" class="empty-state">{{ hasModel ? '当前模型暂无病害记录' : '加载模型后查看病害记录' }}</p>
            </section>
          </aside>

          <section class="viewport-column">
            <div ref="viewport" class="viewport" :class="{ selecting: selectingDisease }">
              <canvas ref="canvas" aria-label="三维模型视窗"></canvas>
              <div class="viewport-tag"><i></i><span>{{ selectedModel?.name || '未加载模型' }}</span><small>MODEL EXPLORER / {{ selectedModel ? '001' : '--' }}</small></div>
              <div class="viewport-buttons"><button type="button" title="复位视角" aria-label="复位视角" @click="resetCamera">⊙</button><button type="button" title="切换全屏" aria-label="切换全屏" @click="viewport?.requestFullscreen?.()">⛶</button></div>
              <div class="view-chip">{{ currentView ? `${{front:'正视图',back:'后视图',left:'左视图',right:'右视图',top:'顶视图',bottom:'底视图'}[currentView]}` : '自由视角' }}</div>
              <div v-if="!hasModel" class="viewport-empty"><div class="empty-mark">天</div><strong>{{ rendererError || (models.length ? '选择左侧模型以开始浏览' : '模型工作空间') }}</strong><span>{{ models.length ? '模型将按视角自动调度加载' : '服务端模型加载后将在此显示' }}</span></div>
              <svg v-if="selectingDisease" class="selection-layer" @click="addDiseasePoint" @dblclick.prevent="finishDiseaseSelection">
                <polygon v-if="polygonScreen.length > 2" :points="polygonScreen.map(point => `${point.x},${point.y}`).join(' ')" />
                <polyline v-if="polygonScreen.length" :points="polygonScreen.map(point => `${point.x},${point.y}`).join(' ')" />
                <circle v-for="(point, index) in polygonScreen" :key="index" :cx="point.x" :cy="point.y" r="5" />
              </svg>
              <div v-if="selectingDisease" class="selection-hint">点击添加顶点，双击完成框选　·　{{ polygonWorld.length }} 个顶点 <button type="button" @click.stop="cancelDiseaseSelection">取消</button></div>
              <div class="viewport-controls"><button type="button" title="模型信息" @click="scrollToSection('runtime-info')">⬡</button><button type="button" title="放大" @click="scene?.camera && (scene.camera.zoom *= 1.1, scene.camera.updateProjectionMatrix(), scene.render(), scheduleCameraUpdate())">＋</button><button type="button" title="缩小" @click="scene?.camera && (scene.camera.zoom /= 1.1, scene.camera.updateProjectionMatrix(), scene.render(), scheduleCameraUpdate())">−</button><button type="button" title="复位" @click="resetCamera">⊙</button></div>
              <div class="scale-bar"><span>{{ hasModel ? '模型坐标' : '无数据' }}</span><i></i><small>10 m</small></div>
            </div>
            <section id="runtime-info" class="runtime-panel">
              <header><div class="runtime-title"><h2>实时信息</h2><span>当前状态</span></div><span class="runtime-status"><i></i>{{ hasModel ? '场景已就绪' : '等待模型加载' }}</span></header>
              <dl><div><dt>可视节点</dt><dd>{{ telemetry.nodes.toLocaleString('zh-CN') }}</dd></div><div><dt>三角面片</dt><dd>{{ telemetry.triangles.toLocaleString('zh-CN') }}</dd></div><div><dt>当前层级</dt><dd>{{ telemetry.lod }}</dd></div><div><dt>帧率</dt><dd>{{ telemetry.fps }}</dd></div></dl>
            </section>
          </section>

          <aside id="scene-settings" class="inspector-column">
            <div class="eyebrow">SCENE SETTINGS</div>
            <div class="panel-heading inspector-heading"><h2>场景控制</h2><span>☷</span></div>
            <section class="control-section">
              <div class="control-title">场景操作</div>
              <div class="command-row"><button type="button" :disabled="!hasModel" :aria-pressed="selectingDisease" @click="selectingDisease = !selectingDisease; polygonScreen = []; polygonWorld = []">⊙　病害框选</button><button type="button" :disabled="!hasModel" @click="clearScene">×　清空场景</button></div>
              <label class="color-field"><span>实体颜色</span><output>{{ solidColor.toUpperCase() }}</output><input type="color" :value="solidColor" aria-label="实体颜色" @input="setEntityColor($event.target.value)"></label>
              <div class="swatches"><button v-for="color in ['#e3d5bd','#ffffff','#aabdac','#b97361']" :key="color" type="button" :style="{ '--swatch': color }" :aria-label="`实体颜色 ${color}`" :aria-pressed="solidColor === color" @click="setEntityColor(color)"></button></div>
            </section>
            <section class="control-section">
              <div class="control-title">视角切换 <small>六向对齐</small></div>
              <div class="view-options"><button v-for="[id,label] in [['front','正视图'],['back','后视图'],['left','左视图'],['right','右视图'],['top','顶视图'],['bottom','底视图']]" :key="id" type="button" :aria-pressed="currentView === id" @click="alignView(id)">{{ label }}</button></div>
            </section>
            <section class="control-section toggles">
              <label class="switch-row"><span>节点包围盒</span><input type="checkbox" :checked="showBounds" @change="toggleBounds"><i></i></label>
              <label class="switch-row"><span>显示病害标注</span><input type="checkbox" :checked="showDiseaseLabels" @change="toggleDiseaseLabels"><i></i></label>
            </section>
            <section class="control-section background-section">
              <div class="control-title">背景色 <small>纯黑视窗</small></div>
              <div class="color-field"><span>画布背景</span><output>#000000</output><i class="background-swatch"></i></div>
            </section>
            <button class="reset-link" type="button" @click="resetSettings">恢复默认设置 <span>☷</span></button>
            <input ref="fileInput" type="file" webkitdirectory directory multiple hidden @change="loadLocalFiles">
          </aside>
        </section>
        <footer class="page-footer"><span>01 / 三维模型工作空间</span><span>“制器象天，取法自然。”　在当代的数字记录中，延续古建筑的秩序。</span><small>TIANTAN DIGITAL ARCHIVE</small></footer>
      </main>
    </div>

    <div v-if="notice" class="notice" :class="noticeKind" role="status">{{ notice }}</div>
    <div v-if="diseaseDialog" class="dialog-backdrop" @click.self="cancelDiseaseSelection">
      <form class="disease-dialog" @submit.prevent="saveDisease">
        <header><h2>录入病害信息</h2><button type="button" aria-label="关闭" @click="cancelDiseaseSelection">×</button></header>
        <label>病害类型<select v-model="diseaseForm.type" required><option value="" disabled>请选择病害类型</option><option>墙面开裂</option><option>地基沉降</option><option>城砖断裂</option><option>风化剥落</option><option>渗水侵蚀</option><option>结构变形</option><option>生物病害</option><option>其他</option></select></label>
        <label>录入日期<input v-model="diseaseForm.date" readonly></label>
        <label>录入人<input v-model="diseaseForm.recorder" placeholder="请输入录入人姓名" required></label>
        <label>病害描述<textarea v-model="diseaseForm.description" rows="3" placeholder="描述病害情况"></textarea></label>
        <fieldset><legend>严重程度</legend><label v-for="severity in ['轻度','中度','重度']" :key="severity"><input v-model="diseaseForm.severity" type="radio" :value="severity">{{ severity }}</label></fieldset>
        <footer><button type="button" @click="cancelDiseaseSelection">取消</button><button type="submit">确认录入</button></footer>
      </form>
    </div>
  </div>
</template>

<style>
.model-shell{--line:#dbd5c6;--muted:#8b8879;--ink:#37392f;--red:#934a3e;--surface:#f8f6ef;--accent:#934a3e}
.model-content{max-width:1900px}
.page-heading{display:flex;align-items:center;justify-content:space-between;gap:20px;padding:1px 0 20px;border-bottom:1px solid var(--line);margin-bottom:18px}
.page-heading .eyebrow{display:block;color:var(--muted);font:8px/1.5 Consolas,monospace;letter-spacing:2px}
.page-heading .eyebrow i{color:var(--red);font-style:normal}
.page-heading h1{margin-top:12px;font:400 28px/1.45 var(--display)}
.page-heading p{margin-top:5px;color:var(--muted);font:11px/1.6 "Microsoft YaHei",sans-serif}
.manage-link{display:flex;align-items:center;gap:12px;padding:10px 13px;border:1px solid var(--line);font:11px "Microsoft YaHei",sans-serif;white-space:nowrap}
.manage-link:hover{color:var(--red);border-color:var(--red)}
.manage-link span{font-size:18px}
.workspace-toolbar{display:grid;grid-template-columns:1fr auto 1fr;align-items:center;gap:12px;padding:0 0 15px;border-bottom:1px solid var(--line);margin-bottom:19px}
.workspace-toolbar>strong{font:600 12px "Microsoft YaHei",sans-serif}
.toolbar-actions{display:flex;justify-content:center;gap:7px}
.toolbar-actions button{height:34px;padding:0 11px;border:1px solid #c9ab9b;background:transparent;color:var(--red);font:11px "Microsoft YaHei",sans-serif;white-space:nowrap;cursor:pointer}
.toolbar-actions button:hover{background:#ede1d6}
.tool-icon{margin-right:6px}
.workspace-status{justify-self:end;color:#516c5e;font:10px "Microsoft YaHei",sans-serif;text-align:right}
.viewer-layout{display:grid;grid-template-columns:224px minmax(0,1fr) 278px;grid-template-rows:minmax(555px,1fr) auto;gap:0 18px;min-height:700px;align-items:stretch}
.library-column{grid-column:1;grid-row:1/3;min-width:0;padding-right:17px;border-right:1px solid var(--line)}
.library-column>.eyebrow,.inspector-column>.eyebrow{color:var(--muted);font:8px/1.5 Consolas,monospace;letter-spacing:2px}
.panel-heading{display:flex;align-items:center;justify-content:space-between;gap:8px;margin:7px 0 13px}
.panel-heading h2{font:400 18px/1.5 var(--display)}
.count-badge{display:grid;place-items:center;width:24px;height:24px;background:#ede8dc;color:var(--ink);font:10px Consolas,monospace}
.search-field{height:34px;display:flex;align-items:center;gap:8px;padding:0 10px;border:1px solid var(--line);border-radius:4px;color:var(--muted)}
.search-field>span{font-size:17px}
.search-field input{width:100%;min-width:0;border:0;outline:0;background:transparent;color:var(--ink);font:10px "Microsoft YaHei",sans-serif}
.resource-list{display:flex;flex-direction:column;margin-top:7px}
.resource-row{display:flex;align-items:center;gap:10px;min-width:0;padding:13px 0;border-bottom:1px solid var(--line)}
.resource-row.selected .resource-copy>strong{color:var(--red)}
.resource-copy{min-width:0;flex:1}
.resource-copy>strong{display:block;overflow:hidden;color:var(--ink);font:500 10px/1.6 "Microsoft YaHei",sans-serif;text-overflow:ellipsis;white-space:nowrap}
.resource-copy>small{display:block;margin-top:4px;color:var(--muted);font:8px/1.5 Consolas,monospace;overflow-wrap:anywhere}
.resource-button{flex:0 0 auto;min-height:27px;padding:3px 8px;border:1px solid var(--line);background:transparent;color:var(--ink);font:10px "Microsoft YaHei",sans-serif;cursor:pointer}
.resource-button:hover:not(:disabled){border-color:var(--red);color:var(--red)}.resource-button:disabled{opacity:.55}
.resource-row.selected .resource-button{border-color:#e2cfc1;background:#ede1d6;color:var(--red)}
.empty-state{padding:17px 1px;color:var(--muted);font:10px/1.7 "Microsoft YaHei",sans-serif}
.manage-resource{width:100%;display:flex;justify-content:space-between;margin-top:6px;padding:12px 2px;border:0;border-bottom:1px solid var(--line);background:none;color:var(--red);text-align:left;font:10px "Microsoft YaHei",sans-serif;cursor:pointer}
.disease-list{padding-top:17px;margin-top:18px;border-top:1px solid var(--line)}
.disease-list .panel-heading{margin-top:0;margin-bottom:10px}
.disease-list .panel-heading h2{font-size:18px}
.disease-count{color:var(--red);font:10px Consolas,monospace}
.disease-row{display:flex;align-items:center;justify-content:space-between;gap:8px;padding:12px 0;border-bottom:1px solid var(--line);cursor:pointer}
.disease-row.selected{padding-left:9px;border-left:2px solid var(--red)}
.disease-summary{min-width:0;flex:1}
.disease-summary>strong{display:block;color:var(--ink);font:500 11px/1.5 "Microsoft YaHei",sans-serif;overflow-wrap:anywhere}
.disease-summary>small{display:block;margin-top:4px;color:var(--muted);font:9px/1.5 "Microsoft YaHei",sans-serif;overflow-wrap:anywhere}
.disease-actions{display:flex;flex:0 0 auto;gap:7px;margin-top:0}
.disease-actions button{padding:5px 7px;border:1px solid var(--line);background:var(--surface);font:9px "Microsoft YaHei",sans-serif;cursor:pointer}
.disease-actions button:hover{color:var(--red);border-color:var(--red)}
.viewport-column{grid-column:2;grid-row:1/3;display:flex;flex-direction:column;min-width:0}
.viewport{position:relative;flex:1;min-height:555px;overflow:hidden;border:1px solid var(--line);background:#000;isolation:isolate}
.viewport canvas{position:absolute;inset:0;z-index:1;width:100%;height:100%;outline:none}
.viewport-tag{position:absolute;z-index:3;top:17px;left:17px;max-width:55%;display:grid;grid-template-columns:8px minmax(0,1fr);column-gap:7px;align-items:center;padding:8px 10px;border:1px solid var(--line);background:rgba(248,246,239,.92)}
.viewport-tag i{width:5px;height:5px;border-radius:50%;background:var(--red)}
.viewport-tag span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font:10px "Microsoft YaHei",sans-serif}
.viewport-tag small{grid-column:2;margin-top:4px;color:var(--muted);font:6px Consolas,monospace;letter-spacing:1px}
.viewport-buttons{position:absolute;z-index:4;top:17px;right:15px;display:flex;gap:7px}
.viewport-buttons button{width:34px;height:34px;border:1px solid var(--line);background:rgba(248,246,239,.92);font-size:17px;cursor:pointer}
.viewport-buttons button:hover{color:var(--red);border-color:var(--red)}
.view-chip{position:absolute;z-index:3;top:91px;left:17px;padding:6px 8px;border:1px solid var(--line);background:rgba(248,246,239,.88);color:var(--muted);font:9px "Microsoft YaHei",sans-serif}
.viewport-empty{position:absolute;z-index:2;inset:0;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:10px;pointer-events:none}
.empty-mark{display:grid;place-items:center;width:52px;height:52px;border:1px solid #c9bbaa;color:var(--red);font:21px var(--display)}
.viewport-empty strong{color:#686456;font:400 13px var(--display)}
.viewport-empty span{color:var(--muted);font:9px "Microsoft YaHei",sans-serif}
.scale-bar{position:absolute;right:14px;bottom:12px;z-index:3;display:grid;grid-template-columns:auto 45px;column-gap:7px;align-items:end;color:var(--muted);font:7px Consolas,monospace}
.scale-bar>span{grid-column:1/3}.scale-bar i{height:5px;border:1px solid #888477;border-top:0}.scale-bar small{text-align:right}
.viewport-controls{position:absolute;bottom:18px;left:50%;z-index:4;display:flex;transform:translateX(-50%);border:1px solid var(--line);background:rgba(248,246,239,.95)}
.viewport-controls button{width:35px;height:34px;border:0;border-right:1px solid var(--line);background:transparent;color:#777464;font-size:15px;cursor:pointer}.viewport-controls button:last-child{border:0}.viewport-controls button:hover{color:var(--red)}
.viewport.selecting{cursor:crosshair}.selection-layer{position:absolute;inset:0;z-index:5;width:100%;height:100%;cursor:crosshair}
.selection-layer polygon{fill:#934a3e22;stroke:var(--red);stroke-width:2;stroke-dasharray:6 3}.selection-layer polyline{fill:none;stroke:var(--red);stroke-width:2}.selection-layer circle{fill:var(--red);stroke:#fff;stroke-width:2}
.selection-hint{position:absolute;z-index:6;left:50%;bottom:65px;transform:translateX(-50%);padding:8px 12px;background:#f8f6eff0;color:var(--red);font:10px "Microsoft YaHei",sans-serif;white-space:nowrap}
.selection-hint button{margin-left:8px;border:0;background:transparent;color:var(--red);cursor:pointer}
.runtime-panel{min-width:0;padding:13px 17px;border:1px solid var(--line);border-top:0;background:var(--surface)}
.runtime-panel header{display:flex;justify-content:space-between;align-items:center;gap:10px;margin-bottom:10px}
.runtime-title{display:flex;align-items:baseline;gap:9px}.runtime-title h2{font:500 12px "Microsoft YaHei",sans-serif}.runtime-title>span{color:var(--muted);font:9px "Microsoft YaHei",sans-serif}
.runtime-status{color:#516c5e;font:9px "Microsoft YaHei",sans-serif;white-space:nowrap}.runtime-status i{display:inline-block;width:5px;height:5px;margin:0 5px 1px 0;border-radius:50%;background:#718776}
.runtime-panel dl{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:10px;margin:0}
.runtime-panel dl>div{min-width:0;padding-left:12px;border-left:1px solid var(--line)}.runtime-panel dl>div:first-child{padding-left:0;border:0}
.runtime-panel dt{color:var(--muted);font:9px "Microsoft YaHei",sans-serif}.runtime-panel dd{margin:3px 0 0;color:var(--ink);font:15px/1.5 Consolas,monospace;overflow-wrap:anywhere}
.inspector-column{grid-column:3;grid-row:1/3;min-width:0;padding-left:17px;border-left:1px solid var(--line)}
.inspector-heading{padding:0 0 13px;border-bottom:1px solid var(--line);margin:7px 0 0}.inspector-heading h2{font-size:18px}.inspector-heading>span{color:var(--muted);font-size:16px}
.control-section{padding:13px 0;border-bottom:1px solid var(--line)}
.control-title{display:flex;justify-content:space-between;align-items:center;margin-bottom:10px;font:600 11px "Microsoft YaHei",sans-serif}.control-title small{color:var(--muted);font:9px "Microsoft YaHei",sans-serif}
.command-row{display:grid;grid-template-columns:1fr 1fr;gap:7px;margin-bottom:11px}
.command-row button{min-width:0;height:34px;border:1px solid var(--line);background:var(--surface);font:10px "Microsoft YaHei",sans-serif;white-space:nowrap;cursor:pointer}
.command-row button:hover:not(:disabled){color:var(--red);border-color:#c9ab9b}.command-row button:disabled{opacity:.45;cursor:not-allowed}
.color-field{display:flex;align-items:center;gap:8px;color:var(--ink);font:10px "Microsoft YaHei",sans-serif}
.color-field output{margin-left:auto;color:var(--muted);font:8px Consolas,monospace}
.color-field input{width:29px;height:27px;padding:3px;border:1px solid var(--line);background:var(--surface);cursor:pointer}
.background-swatch{width:20px;height:20px;margin-left:auto;border:1px solid #777;background:#000}
.resource-error{display:flex;align-items:center;justify-content:space-between;gap:8px;color:#75372f}
.resource-error button{padding:5px 8px;border:1px solid #c88c83;background:transparent;color:inherit;font:10px "Microsoft YaHei",sans-serif;cursor:pointer}
.swatches{display:flex;gap:11px;align-items:center;margin-top:10px}
.swatches button{width:22px;height:22px;border:3px solid var(--surface);border-radius:50%;background:var(--swatch);box-shadow:0 0 0 1px var(--line);cursor:pointer}
.swatches button[aria-pressed=true]{box-shadow:0 0 0 2px var(--red)}
.view-options{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:6px;margin-bottom:11px}
.view-options button{height:30px;padding:2px;border:1px solid var(--line);border-radius:2px;background:var(--surface);font:10px "Microsoft YaHei",sans-serif;cursor:pointer}
.view-options button:hover,.view-options button[aria-pressed=true]{border-color:#c9ab9b;color:var(--red);background:#ede1d6}
.toggles{padding-top:10px}.switch-row{height:29px;display:flex;align-items:center;justify-content:space-between;gap:10px;font:10px "Microsoft YaHei",sans-serif;cursor:pointer}.switch-row input{position:absolute;opacity:0}.switch-row i{position:relative;width:29px;height:16px;border-radius:12px;background:#c7c4b9;transition:background .15s}.switch-row i:after{position:absolute;top:3px;left:3px;width:10px;height:10px;border-radius:50%;background:white;content:"";transition:transform .15s}.switch-row input:checked+i{background:var(--red)}.switch-row input:checked+i:after{transform:translateX(13px)}
.background-section{margin-top:12px}.reset-link{width:100%;display:flex;justify-content:space-between;padding:12px 0;border:0;background:transparent;color:var(--red);font:10px "Microsoft YaHei",sans-serif;text-align:left;cursor:pointer}
.page-footer{display:grid;grid-template-columns:1fr 2fr 1fr;align-items:center;gap:12px;padding-top:14px;margin-top:19px;border-top:1px solid var(--line);color:var(--muted);font:9px "Microsoft YaHei",sans-serif}
.page-footer>span:first-child,.page-footer small{font:7px Consolas,monospace;letter-spacing:1px}.page-footer small{text-align:right}
.notice{position:fixed;right:20px;bottom:20px;z-index:50;max-width:min(420px,calc(100vw - 40px));padding:11px 15px;border:1px solid var(--line);background:#f8f6ef;box-shadow:0 4px 18px #37392f18;color:var(--ink);font:11px/1.5 "Microsoft YaHei",sans-serif}.notice.error{border-color:#c88c83;color:#75372f}.notice.success{border-color:#9db19d;color:#516c5e}
.dialog-backdrop{position:fixed;inset:0;z-index:60;display:grid;place-items:center;padding:18px;background:#211e1b88}
.disease-dialog{width:min(440px,100%);max-height:90vh;overflow:auto;padding:20px;background:#f7f4ec;border:1px solid var(--line);box-shadow:0 18px 50px #0003}
.disease-dialog header{display:flex;justify-content:space-between;align-items:center;padding-bottom:13px;border-bottom:1px solid var(--line);margin-bottom:15px}.disease-dialog h2{font:400 19px var(--display)}.disease-dialog header button{border:0;background:none;font-size:22px;cursor:pointer}
.disease-dialog>label{display:block;margin:12px 0;color:var(--muted);font:11px "Microsoft YaHei",sans-serif}.disease-dialog>label input,.disease-dialog>label select,.disease-dialog textarea{width:100%;margin-top:6px;padding:9px;border:1px solid var(--line);background:#fbfaf6;color:var(--ink);font:12px "Microsoft YaHei",sans-serif}
.disease-dialog textarea{resize:vertical}.disease-dialog fieldset{display:flex;gap:18px;border:0;padding:8px 0;color:var(--muted);font:11px "Microsoft YaHei",sans-serif}.disease-dialog legend{margin-bottom:8px}.disease-dialog fieldset label{display:flex;align-items:center;gap:5px}
.disease-dialog footer{display:flex;justify-content:flex-end;gap:8px;padding-top:14px;border-top:1px solid var(--line);margin-top:12px}.disease-dialog footer button{padding:8px 13px;border:1px solid var(--line);background:transparent;font:11px "Microsoft YaHei",sans-serif;cursor:pointer}.disease-dialog footer button[type=submit]{border-color:var(--red);background:var(--red);color:#fff}
@media(max-width:1250px){.viewer-layout{grid-template-columns:205px minmax(0,1fr) 250px;gap:0 13px}.library-column{padding-right:12px}.inspector-column{padding-left:12px}.main-content{padding-right:25px;padding-left:25px}.global-top{padding-right:25px;padding-left:25px}.global-top nav{gap:15px}}
@media(max-width:950px){.model-shell .global-top{height:auto;min-height:78px;flex-wrap:wrap;gap:0 14px;padding:14px 20px 0}.model-shell .global-top nav{order:3;width:100%;height:42px;justify-content:flex-start;gap:18px;overflow-x:auto}.model-shell .nav-link{height:42px;flex:0 0 auto;font-size:10px}.model-shell .nav-link.active:after{bottom:3px}.viewer-layout{grid-template-columns:minmax(170px, .75fr) minmax(0,2fr);grid-template-rows:minmax(480px,auto) auto auto;gap:14px}.library-column{grid-column:1;grid-row:1/4}.viewport-column{grid-column:2;grid-row:1/3}.inspector-column{grid-column:2;grid-row:3;padding:15px 0 0;border-left:0;border-top:1px solid var(--line)}.inspector-column .control-section{display:inline-block;vertical-align:top;width:49%;padding:12px}.workspace-toolbar{grid-template-columns:1fr auto}.workspace-status{grid-column:1/-1;justify-self:start}.model-content{padding-top:22px}}
@media(max-width:650px){.model-shell .heritage-spine{width:42px;padding-top:17px}.model-shell .heritage-spine .seal{padding:5px 4px;font-size:14px}.model-shell .heritage-spine p{margin-top:35px;font-size:9px;letter-spacing:3px}.model-shell .heritage-spine>span{margin-bottom:20px;font-size:6px;letter-spacing:2px}.model-shell .heritage-body{margin-left:42px}.model-shell .global-top{padding:12px 12px 0}.model-shell .brand strong{font-size:16px}.model-shell .brand small{display:none}.model-shell .global-top nav{gap:15px}.model-shell .nav-link{font-size:9px}.model-content{padding:18px 12px 25px}.page-heading{align-items:flex-start}.page-heading h1{font-size:22px}.page-heading p{max-width:235px;font-size:9px}.manage-link{padding:8px;font-size:9px}.manage-link span{display:none}.workspace-toolbar{display:flex;flex-wrap:wrap;justify-content:space-between}.toolbar-actions{order:3;width:100%;justify-content:flex-start;overflow-x:auto}.toolbar-actions button{padding:0 8px;font-size:9px}.workspace-status{max-width:100%;font-size:8px}.viewer-layout{display:flex;flex-direction:column;gap:15px;min-height:0}.viewport-column{order:0}.viewport{min-height:55vh;min-height:440px}.runtime-panel{padding:12px}.runtime-panel dl{grid-template-columns:repeat(2,minmax(0,1fr));row-gap:10px}.runtime-panel dl>div:nth-child(3){padding-left:0;border:0}.runtime-panel dd{font-size:13px}.library-column{order:1;padding:0;border:0}.resource-list{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));column-gap:12px}.resource-row{gap:7px}.disease-list{margin-top:14px}.inspector-column{order:2}.inspector-column .control-section{width:100%;padding:12px 0}.page-footer{grid-template-columns:1fr auto}.page-footer>span:nth-child(2){grid-row:2;grid-column:1/-1;line-height:1.8}.page-footer small{grid-column:2;grid-row:1;font-size:6px}.selection-hint{max-width:94%;white-space:normal;text-align:center}.viewport-empty{padding:12px;text-align:center}.viewport-empty strong{font-size:11px}.viewport-empty span{font-size:8px}}
</style>
