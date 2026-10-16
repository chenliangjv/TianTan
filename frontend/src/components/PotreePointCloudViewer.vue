<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import {
  Box,
  Camera,
  FullScreen,
  RefreshRight,
} from '@element-plus/icons-vue';
import {
  Color,
  PerspectiveCamera,
  Scene,
  SRGBColorSpace,
  Vector3,
  WebGLRenderer,
} from 'three';
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js';
import { PointShape, PointSizeType, Potree, PotreeRenderer } from 'potree-core';
import config from '@/config/index.js';
import PointCloudDatasetPanel from '@/components/PointCloudDatasetPanel.vue';
import PointCloudRenderPanel from '@/components/PointCloudRenderPanel.vue';
import PointCloudStatusPanel from '@/components/PointCloudStatusPanel.vue';
import BackgroundColorPanel from '@/components/BackgroundColorPanel.vue';
import { attachPointCloudColor, DEFAULT_POINT_CLOUD_COLOR, normalizePointCloudColor } from '@/utils/pointCloudColor.js';
import { createKeyboardRoaming } from '@/utils/keyboardRoaming.js';
import { disposePointCloudBounds } from '@/utils/pointCloudBounds.js';

const viewerElement = ref(null);
const datasets = ref([]);
const selectedId = ref('');
const dataset = ref(null);
const loadingList = ref(false);
const loadingDataset = ref(false);
const loadError = ref('');
// Match Potree's conservative default so camera movement keeps priority over
// filling the GPU with points. The budget can still be raised from the panel.
const pointBudget = ref(1_000_000);
const pointSize = ref(1.1);
// EDL is an optional multi-pass effect. Keep the native single-pass Potree
// renderer as the default so camera movement and LOD scheduling stay smooth.
const edlEnabled = ref(false);
const showBoundingBox = ref(false);
const backgroundColor = ref('#07131f');
const colorSettings = ref({ ...DEFAULT_POINT_CLOUD_COLOR });
const colorError = ref('');
const visiblePointCount = ref(0);
const loadingProgress = ref(0);
const pointCloudLoaded = ref(false);
const showDatasetPanel = ref(false);
const showRenderPanel = ref(false);
const showStatusPanel = ref(false);
const showBackgroundColor = ref(false);
const isFullscreen = ref(false);
const scenePanelRatio = ref(0.58);
let leftPanelResizeState = null;

let scene;
let camera;
let renderer;
let controls;
let potree;
let potreeRenderer;
let pointCloud;
let resizeObserver;
let animationFrame;
let loadingRequest = 0;
let datasetAbortController;
let frameCount = 0;
let colorController;
let colorUpdateFrame;
let keyboardRoaming;

const hasDataset = computed(() => datasets.value.length > 0);
const selectedDataset = computed(() => datasets.value.find(item => String(item.id) === String(selectedId.value)) || null);
const selectedLabel = computed(() => selectedDataset.value?.name || dataset.value?.name || '未选择数据集');
const datasetPointCount = computed(() => dataset.value?.points ?? selectedDataset.value?.pointCount ?? 0);

const toggleDatasetPanel = () => {
  showDatasetPanel.value = !showDatasetPanel.value;
  if (showDatasetPanel.value) {
    showRenderPanel.value = false;
    showStatusPanel.value = false;
  }
};

const toggleRenderPanel = () => {
  showRenderPanel.value = !showRenderPanel.value;
  if (showRenderPanel.value) showDatasetPanel.value = false;
};

const toggleStatusPanel = () => {
  showStatusPanel.value = !showStatusPanel.value;
  if (showStatusPanel.value) showDatasetPanel.value = false;
};

const apiUrl = path => {
  if (/^https?:\/\//i.test(path)) return path;
  return `${config.API_BASE_URL}${path}`;
};

const getErrorMessage = async response => {
  const text = await response.text();
  return text || `请求失败: ${response.status}`;
};

const updateRendererBackground = () => {
  if (!renderer) return;
  renderer.setClearColor(new Color(backgroundColor.value), 1);
};

const updatePointCloudColor = changes => {
  colorSettings.value = normalizePointCloudColor({ ...colorSettings.value, ...changes });
  if (!colorController || colorUpdateFrame) return;
  colorUpdateFrame = window.requestAnimationFrame(() => {
    colorUpdateFrame = undefined;
    colorController?.update(colorSettings.value);
  });
};

const resetPointCloudColor = () => updatePointCloudColor(DEFAULT_POINT_CLOUD_COLOR);

const setupPointCloudColor = () => {
  colorError.value = '';
  try {
    colorController = attachPointCloudColor(pointCloud.material, {
      onError: error => {
        colorError.value = error.message;
        console.error('[PointCloud] 颜色控制不可用:', error);
      },
    });
    colorController.update(colorSettings.value);
  } catch (error) {
    colorError.value = error.message;
    console.error('[PointCloud] 颜色控制不可用:', error);
  }
};

const applyPointCloudSettings = () => {
  if (!potree || !pointCloud) return;
  potree.pointBudget = pointBudget.value;
  pointCloud.material.pointSizeType = PointSizeType.ADAPTIVE;
  // Circle sprites discard the square corners at fragment level, which is
  // the key difference between a smooth point cloud and visible pixel blocks.
  pointCloud.material.shape = PointShape.CIRCLE;
  pointCloud.material.size = pointSize.value;
  pointCloud.material.minSize = 0.65;
  pointCloud.material.maxSize = Math.max(3, pointSize.value * 4);
  pointCloud.showBoundingBox = showBoundingBox.value;
  potreeRenderer?.setEDL({
    enabled: edlEnabled.value,
    pointCloudLayer: 1,
    strength: 0.24,
    radius: 1.15,
    opacity: 1,
  });
};

const resetCamera = () => {
  if (!camera || !controls || !pointCloud) return;
  const sphere = pointCloud.boundingSphere;
  const center = sphere.center.clone();
  const radius = Math.max(sphere.radius, 1);
  const distance = radius / Math.sin((camera.fov * Math.PI) / 360) * 1.15;
  const viewDirection = new Vector3(1, 0.72, 1).normalize();

  camera.near = Math.max(0.01, radius / 10_000);
  camera.far = Math.max(10_000, radius * 40);
  camera.position.copy(center).add(viewDirection.multiplyScalar(distance));
  controls.target.copy(center);
  camera.updateProjectionMatrix();
  controls.update();
};

const handleFullscreenChange = () => {
  isFullscreen.value = document.fullscreenElement === viewerElement.value;
  window.requestAnimationFrame(resizeViewer);
};

const toggleFullscreen = async () => {
  try {
    if (document.fullscreenElement === viewerElement.value) {
      await document.exitFullscreen();
    } else {
      await viewerElement.value?.requestFullscreen?.();
    }
  } catch (error) {
    console.error('[PointCloud] 全屏切换失败:', error);
  }
};

const disposePointCloud = () => {
  keyboardRoaming?.reset();
  if (colorUpdateFrame) window.cancelAnimationFrame(colorUpdateFrame);
  colorUpdateFrame = undefined;
  colorController?.dispose();
  colorController = undefined;
  colorError.value = '';
  disposePointCloudBounds(pointCloud, scene);
  if (!pointCloud) return;
  scene?.remove(pointCloud);
  pointCloud.dispose();
  pointCloud = null;
  pointCloudLoaded.value = false;
  visiblePointCount.value = 0;
  loadingProgress.value = 0;
};

const createPotreeScheduler = () => {
  potree = new Potree();
  potree.pointBudget = pointBudget.value;
  potree.maxNumNodesLoading = 4;
};

const createPotreeRenderer = () => {
  // PotreeRenderer retains the last EDL point-cloud list. Dispose it before
  // replacing a dataset so that a removed cloud cannot stay reachable.
  potreeRenderer?.dispose();
  potreeRenderer = new PotreeRenderer({
    edl: { enabled: edlEnabled.value, pointCloudLayer: 1, strength: 0.24, radius: 1.15 },
  });
};

const releasePointCloudRuntime = () => {
  disposePointCloud();
  // A fresh scheduler releases the old LRU node cache. Recreate the EDL
  // renderer too, because it keeps a reference to its last point-cloud list.
  createPotreeScheduler();
  createPotreeRenderer();
};

const unloadDataset = () => {
  ++loadingRequest;
  datasetAbortController?.abort();
  datasetAbortController = undefined;
  loadingDataset.value = false;
  loadError.value = '';
  releasePointCloudRuntime();
  selectedId.value = '';
  dataset.value = null;
};

const loadDataset = async id => {
  if (!id || !potree || !scene) return;
  const requestId = ++loadingRequest;
  datasetAbortController?.abort();
  const requestController = new AbortController();
  datasetAbortController = requestController;
  loadingDataset.value = true;
  loadError.value = '';
  // Switching datasets must also discard the previous scheduler cache and
  // renderer state; otherwise its LRU may retain the old node hierarchy.
  releasePointCloudRuntime();

  try {
    selectedId.value = String(id);
    const response = await fetch(apiUrl(`/api/data/point-clouds/${id}/dataset`), { signal: requestController.signal });
    if (!response.ok) throw new Error(await getErrorMessage(response));
    const nextDataset = await response.json();
    if (nextDataset.format !== 'POTREE_2') throw new Error('当前数据集不是 Potree 2.0 格式');
    if (!nextDataset.metadataUrl) throw new Error('数据集缺少 metadata.json 地址');

    dataset.value = nextDataset;
    const metadataUrl = apiUrl(nextDataset.metadataUrl);
    // `potree-core` treats a string second argument as a prefix and blindly
    // prepends it to the metadata URL. The API returns a complete resource
    // path, so use a request manager that resolves each URL exactly once.
    const requestManager = {
      getUrl: async resource => apiUrl(resource),
      fetch: async (resource, options = {}) => {
        const headers = new Headers(options.headers);
        // potree-core adds this response-only MIME type to byte-range GETs.
        // Some multipart resolvers reject such a request before it reaches the
        // static file endpoint, while it is unnecessary for normal HTTP Range.
        if (headers.get('content-type')?.toLowerCase().startsWith('multipart/byteranges')) {
          headers.delete('content-type');
        }
        const response = await window.fetch(
          typeof resource === 'string' ? apiUrl(resource) : resource,
          { ...options, headers, signal: requestController.signal },
        );
        if (!response.ok) throw new Error(await getErrorMessage(response));
        return response;
      },
    };
    const nextPointCloud = await potree.loadPointCloud(metadataUrl, requestManager);

    if (requestId !== loadingRequest) {
      nextPointCloud.dispose();
      return;
    }

    pointCloud = nextPointCloud;
    pointCloud.name = nextDataset.name || 'Potree point cloud';
    scene.add(pointCloud);
    applyPointCloudSettings();
    setupPointCloudColor();
    pointCloudLoaded.value = true;
    resetCamera();
  } catch (error) {
    if (requestId === loadingRequest) {
      selectedId.value = '';
      dataset.value = null;
      loadError.value = error instanceof Error ? error.message : '点云数据加载失败';
      console.error('[PotreePointCloudViewer] 加载点云失败:', error);
    }
  } finally {
    if (requestId === loadingRequest) {
      loadingDataset.value = false;
      datasetAbortController = undefined;
    }
  }
};

const loadDatasets = async ({ preserveSelection = true } = {}) => {
  loadingList.value = true;
  loadError.value = '';
  try {
    const response = await fetch(apiUrl('/api/data/point-clouds/list'));
    if (!response.ok) throw new Error(await getErrorMessage(response));
    datasets.value = await response.json();
    const exists = datasets.value.some(item => String(item.id) === String(selectedId.value));
    const nextId = preserveSelection && exists ? selectedId.value : '';
    selectedId.value = nextId;
    if (!nextId || !exists) {
      unloadDataset();
    }
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : '无法获取点云数据集列表';
    console.error('[PotreePointCloudViewer] 获取数据集失败:', error);
  } finally {
    loadingList.value = false;
  }
};

const changeDataset = async value => {
  await loadDataset(value);
};

const startLeftPanelResize = event => {
  if (!showRenderPanel.value || !showStatusPanel.value) return;
  event.preventDefault();
  leftPanelResizeState = { startY: event.clientY, startRatio: scenePanelRatio.value };
  document.body.classList.add('is-resizing-panels');
  window.addEventListener('pointermove', handleLeftPanelResize);
  window.addEventListener('pointerup', stopLeftPanelResize, { once: true });
  window.addEventListener('pointercancel', stopLeftPanelResize, { once: true });
};

const handleLeftPanelResize = event => {
  if (!leftPanelResizeState) return;
  const panel = document.querySelector('.pointcloud-left-panel-slot');
  if (!panel) return;
  const bounds = panel.getBoundingClientRect();
  const nextRatio = leftPanelResizeState.startRatio + (event.clientY - leftPanelResizeState.startY) / bounds.height;
  scenePanelRatio.value = Math.min(0.84, Math.max(0.2, nextRatio));
};

const stopLeftPanelResize = () => {
  leftPanelResizeState = null;
  document.body.classList.remove('is-resizing-panels');
  window.removeEventListener('pointermove', handleLeftPanelResize);
};

const resizeViewer = () => {
  if (!viewerElement.value || !renderer || !camera) return;
  const { clientWidth: width, clientHeight: height } = viewerElement.value;
  if (!width || !height) return;
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 1.5));
  renderer.setSize(width, height, false);
  camera.aspect = width / height;
  camera.updateProjectionMatrix();
};

const renderLoop = (time) => {
  animationFrame = window.requestAnimationFrame(renderLoop);
  if (!renderer || !camera || !scene) return;
  keyboardRoaming?.update(time);
  controls?.update();
  if (potree && pointCloud) {
    // Keep the normal path identical to Potree's documented render loop.
    // PotreeRenderer is only needed when the user explicitly enables EDL.
    const result = potree.updatePointClouds([pointCloud], camera, renderer);
    if (edlEnabled.value) {
      potreeRenderer.render({ renderer, scene, camera, pointClouds: [pointCloud] });
    } else {
      renderer.render(scene, camera);
    }
    frameCount += 1;
    if (frameCount % 12 === 0) {
      visiblePointCount.value = result.numVisiblePoints || 0;
      loadingProgress.value = pointCloud.progress || 0;
    }
  } else {
    renderer.render(scene, camera);
  }
};

const initViewer = async () => {
  await nextTick();
  if (!viewerElement.value) return;
  scene = new Scene();
  camera = new PerspectiveCamera(60, 1, 0.01, 100_000);
  renderer = new WebGLRenderer({ antialias: true, logarithmicDepthBuffer: true, powerPreference: 'high-performance' });
  renderer.outputColorSpace = SRGBColorSpace;
  viewerElement.value.appendChild(renderer.domElement);
  updateRendererBackground();

  controls = new OrbitControls(camera, renderer.domElement);
  controls.enableDamping = true;
  controls.dampingFactor = 0.08;
  controls.screenSpacePanning = true;
  controls.minDistance = 1;
  controls.maxDistance = 100_000_000;
  keyboardRoaming = createKeyboardRoaming({
    camera, controls,
    isEnabled: () => pointCloudLoaded.value && !loadingDataset.value,
    getScale: () => pointCloud?.boundingSphere.radius * 2,
  });

  createPotreeScheduler();
  createPotreeRenderer();
  resizeObserver = new ResizeObserver(resizeViewer);
  resizeObserver.observe(viewerElement.value);
  resizeViewer();
  renderLoop();
  await loadDatasets({ preserveSelection: false });
};

watch([pointBudget, pointSize, edlEnabled, showBoundingBox], applyPointCloudSettings);
watch(backgroundColor, updateRendererBackground);

onMounted(() => {
  document.addEventListener('fullscreenchange', handleFullscreenChange);
  initViewer();
});

onBeforeUnmount(() => {
  document.removeEventListener('fullscreenchange', handleFullscreenChange);
  ++loadingRequest;
  datasetAbortController?.abort();
  if (animationFrame) window.cancelAnimationFrame(animationFrame);
  resizeObserver?.disconnect();
  stopLeftPanelResize();
  disposePointCloud();
  keyboardRoaming?.dispose();
  controls?.dispose();
  potreeRenderer?.dispose();
  renderer?.dispose();
  renderer?.domElement.remove();
});
</script>

<template>
  <section ref="viewerElement" class="potree-viewer" aria-label="Potree 点云查看器">
    <nav class="viewer-toolbar" aria-label="点云视图工具">
      <button class="toolbar-button" :class="{ 'is-active': showRenderPanel }" type="button" :aria-pressed="showRenderPanel" title="显示或隐藏场景控制" @click="toggleRenderPanel">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 5h16" /><path d="M4 12h16" /><path d="M4 19h16" /><circle cx="9" cy="5" r="2" /><circle cx="15" cy="12" r="2" /><circle cx="11" cy="19" r="2" /></svg>
        <span>场景控制</span>
      </button>
      <button class="toolbar-button" :class="{ 'is-active': showDatasetPanel }" type="button" :aria-pressed="showDatasetPanel" title="显示或隐藏点云加载" @click="toggleDatasetPanel">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 6h16v12H4z" /><path d="M4 10h16M8 6v12" /></svg>
        <span>点云加载</span>
      </button>
      <button class="toolbar-button" :class="{ 'is-active': showStatusPanel }" type="button" :aria-pressed="showStatusPanel" title="显示或隐藏实时信息" @click="toggleStatusPanel">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 18V6M4 18h16" /><path d="m7 15 4-5 3 3 4-6" /></svg>
        <span>实时信息</span>
      </button>
      <button class="toolbar-button" :class="{ 'is-active': showBackgroundColor }" type="button" :aria-pressed="showBackgroundColor" title="显示或隐藏背景色控制" @click="showBackgroundColor = !showBackgroundColor">
        <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="13.5" cy="10.5" r="7.5" /><path d="M6 20.5c1.5-1.5 3.2-2.3 5-2.3 2.1 0 2.5.8 4.2.8 2.9 0 5.3-2.5 5.3-5.5" /><circle cx="8.5" cy="9" r=".9" /><circle cx="12" cy="6.5" r=".9" /><circle cx="16" cy="7.5" r=".9" /></svg>
        <span>背景色</span>
      </button>
    </nav>

    <div class="viewer-actions">
      <el-tooltip content="复位相机" placement="bottom">
        <el-button circle :icon="Camera" :disabled="!pointCloudLoaded" aria-label="复位相机" @click="resetCamera" />
      </el-tooltip>
      <el-tooltip :content="isFullscreen ? '退出全屏' : '全屏显示'" placement="bottom">
        <el-button circle :icon="FullScreen" :class="{ 'is-active': isFullscreen }" :aria-label="isFullscreen ? '退出全屏' : '全屏显示'" @click="toggleFullscreen" />
      </el-tooltip>
    </div>

    <div v-show="showDatasetPanel" class="viewer-panel-slot dataset-panel-slot">
      <PointCloudDatasetPanel
        :datasets="datasets"
        :selected-id="selectedId"
        :loading-list="loadingList"
        :loading-dataset="loadingDataset"
        :loaded="pointCloudLoaded"
        :has-dataset="hasDataset"
        @refresh="loadDatasets()"
        @load="changeDataset"
        @unload="unloadDataset"
      />
    </div>
    <div v-show="showRenderPanel || showStatusPanel" class="viewer-panel-slot pointcloud-left-panel-slot">
      <div v-show="showRenderPanel" class="left-panel-section scene-panel-section" :style="{ flexBasis: showStatusPanel ? `${scenePanelRatio * 100}%` : '100%' }">
        <PointCloudRenderPanel
          :point-budget="pointBudget"
          :point-size="pointSize"
          :edl-enabled="edlEnabled"
          :show-bounding-box="showBoundingBox"
          :color-settings="colorSettings"
          :color-disabled="!pointCloudLoaded || Boolean(colorError)"
          :color-hint="colorError || (!pointCloudLoaded ? '加载点云后可调整颜色' : '')"
          @update:point-budget="pointBudget = $event"
          @update:point-size="pointSize = $event"
          @update:edl-enabled="edlEnabled = $event"
          @update:show-bounding-box="showBoundingBox = $event"
          @update:color-settings="updatePointCloudColor"
          @reset-color="resetPointCloudColor"
        />
      </div>
      <div v-show="showRenderPanel && showStatusPanel" class="left-panel-resizer" role="separator" aria-orientation="horizontal" aria-label="调整场景控制和实时信息高度" @pointerdown="startLeftPanelResize" @pointercancel="stopLeftPanelResize"></div>
      <div v-show="showStatusPanel" class="left-panel-section telemetry-panel-section" :style="{ flexBasis: showRenderPanel ? `${(1 - scenePanelRatio) * 100}%` : '100%' }">
        <PointCloudStatusPanel
          :selected-label="selectedLabel"
          :load-error="loadError"
          :dataset-point-count="datasetPointCount"
          :visible-point-count="visiblePointCount"
          :loading-progress="loadingProgress"
          :point-cloud="pointCloud"
        />
      </div>
    </div>

    <BackgroundColorPanel
      v-show="showBackgroundColor"
      class="background-color-popover"
      :model-value="backgroundColor"
      @update:model-value="backgroundColor = $event"
    />

    <div v-if="loadingDataset" class="viewer-loading">
      <el-icon class="loading-icon"><Box /></el-icon>
      <strong>正在建立点云索引</strong>
      <span>根节点完成后将按视角流式加载。</span>
    </div>
    <div v-else-if="loadError" class="viewer-message is-error">
      <strong>点云加载失败</strong>
      <span>{{ loadError }}</span>
      <el-button type="primary" :icon="RefreshRight" @click="loadDatasets()">重试</el-button>
    </div>
    <div v-else-if="!hasDataset && !loadingList" class="viewer-message">
      <strong>暂无可用点云数据集</strong>
      <span>请在数据管理页面提交包含 metadata.json、hierarchy.bin 和 octree.bin 的 Potree 2.0 数据集。</span>
    </div>
  </section>
</template>

<style scoped>
.potree-viewer { position: relative; width: 100%; height: 100%; overflow: hidden; background: #07131f; }.potree-viewer :deep(canvas) { display: block; width: 100%; height: 100%; outline: none; }
.viewer-toolbar { position: absolute; z-index: 30; top: 14px; left: 50%; display: flex; align-items: center; gap: 4px; padding: 4px; border: 1px solid rgba(85,214,230,.22); border-radius: 6px; background: rgba(6,20,31,.9); box-shadow: 0 8px 24px rgba(0,0,0,.24), 0 0 20px rgba(85,214,230,.06); backdrop-filter: blur(14px); pointer-events: auto; transform: translateX(-50%); }.toolbar-button { display: inline-flex; align-items: center; justify-content: center; gap: 7px; min-width: 96px; height: 31px; padding: 0 10px; border: 1px solid transparent; border-radius: 3px; color: #829eaa; background: transparent; font-size: 11px; cursor: pointer; white-space: nowrap; transition: .2s ease; }.toolbar-button:hover { color: #ccecf0; background: rgba(85,214,230,.08); }.toolbar-button.is-active { border-color: rgba(85,214,230,.34); color: var(--ui-cyan); background: rgba(85,214,230,.13); box-shadow: inset 0 0 12px rgba(85,214,230,.06); }.toolbar-button svg { width: 15px; height: 15px; fill: none; stroke: currentColor; stroke-linecap: round; stroke-linejoin: round; stroke-width: 1.7; }
.viewer-actions { position: absolute; z-index: 30; top: 16px; right: 14px; display: flex; gap: 6px; }.viewer-actions :deep(.el-button) { width: 34px; height: 34px; margin: 0; border-color: rgba(108,203,220,.26); background: rgba(5,20,31,.88); color: #a8c7d0; }.viewer-actions :deep(.el-button:hover),.viewer-actions :deep(.el-button.is-active) { border-color: var(--ui-cyan); color: var(--ui-cyan); background: rgba(85,214,230,.12); }
.viewer-panels { position: absolute; z-index: 20; inset: 0; pointer-events: none; }.viewer-panel-slot { position: absolute; top: 60px; bottom: 14px; display: flex; min-width: 0; pointer-events: auto; }.dataset-panel-slot { left: 14px; width: 320px; }.pointcloud-left-panel-slot { top: 14px; bottom: 14px; left: 14px; width: 320px; flex-direction: column; overflow: hidden; border: 1px solid rgba(85,214,230,.3); background: rgba(6,20,31,.96); box-shadow: 18px 0 42px rgba(0,0,0,.28), 0 0 24px rgba(85,214,230,.08); backdrop-filter: blur(16px); }.left-panel-section { width: 100%; min-height: 0; overflow: hidden; flex-grow: 0; flex-shrink: 1; }.left-panel-section > :deep(section) { border: 0; box-shadow: none; }.left-panel-resizer { position: relative; z-index: 2; flex: 0 0 9px; cursor: row-resize; background: rgba(4,16,25,.94); border-top: 1px solid rgba(143,190,210,.18); border-bottom: 1px solid rgba(143,190,210,.18); touch-action: none; }.left-panel-resizer::after { position: absolute; top: 3px; left: 50%; width: 36px; height: 2px; background: rgba(85,214,230,.5); content: ''; transform: translateX(-50%); box-shadow: 0 3px 0 rgba(85,214,230,.2); transition: .2s ease; }.left-panel-resizer:hover,.left-panel-resizer:active { background: rgba(85,214,230,.13); }.left-panel-resizer:hover::after,.left-panel-resizer:active::after { background: var(--ui-cyan); box-shadow: 0 3px 0 rgba(85,214,230,.5), 0 0 8px rgba(85,214,230,.55); }.background-color-popover { position: absolute; z-index: 29; top: 60px; left: 50%; transform: translateX(-50%); }.viewer-panel-slot > :deep(section) { border-radius: 0; }
.viewer-loading, .viewer-message { position: absolute; z-index: 40; top: 50%; left: 50%; display: grid; justify-items: center; gap: 9px; max-width: min(460px, calc(100% - 48px)); padding: 24px 28px; border: 1px solid rgba(108,203,220,.3); background: rgba(6,21,32,.92); box-shadow: 0 16px 40px rgba(0,0,0,.34); color: #d8eef1; text-align: center; transform: translate(-50%, -50%); }.viewer-loading strong, .viewer-message strong { font-size: 14px; font-weight: 600; }.viewer-loading span, .viewer-message span { color: #7895a0; font-size: 12px; line-height: 1.65; }.loading-icon { color: var(--ui-cyan); font-size: 26px; animation: pointcloud-pulse 1.4s ease-in-out infinite; }.viewer-message.is-error { border-color: rgba(239,118,122,.48); }.viewer-message :deep(.el-button) { margin-top: 5px; } @keyframes pointcloud-pulse { 50% { opacity: .38; transform: scale(.82); } }
@media (max-width: 760px) { .toolbar-button { min-width: 34px; padding: 0 9px; }.toolbar-button span { display: none; }.viewer-panel-slot { top: 58px; bottom: 10px; }.dataset-panel-slot,.pointcloud-left-panel-slot { left: 10px; width: 280px; } }
@media (max-width: 560px) { .viewer-toolbar { top: 10px; max-width: calc(100% - 20px); }.viewer-actions { top: 10px; right: 10px; }.viewer-panel-slot { top: 54px; right: 10px; bottom: 10px; left: 10px; width: auto; }.dataset-panel-slot,.pointcloud-left-panel-slot { width: auto; }.background-color-popover { top: 54px; max-width: calc(100% - 20px); } }
</style>
