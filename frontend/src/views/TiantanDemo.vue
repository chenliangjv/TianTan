<template>
  <div class="main-content">
    <!-- Canvas Container -->
    <div ref="canvasContainerRef" class="canvas-container">
      <nav class="viewer-toolbar" aria-label="模型视图工具">
        <button class="toolbar-button" :class="{ 'is-active': showSceneControl }" type="button" :aria-pressed="showSceneControl" title="显示或隐藏场景控制" @click="toggleSceneControl">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 5h16" /><path d="M4 12h16" /><path d="M4 19h16" /><circle cx="9" cy="5" r="2" /><circle cx="15" cy="12" r="2" /><circle cx="11" cy="19" r="2" /></svg>
          <span>场景控制</span>
        </button>
        <button class="toolbar-button" :class="{ 'is-active': showModelLoading }" type="button" :aria-pressed="showModelLoading" title="显示或隐藏模型加载" @click="toggleModelLoading">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m12 3 8 4.5v9L12 21l-8-4.5v-9L12 3Z" /><path d="m4 7.5 8 4.5 8-4.5" /><path d="M12 12v9" /></svg>
          <span>模型加载</span>
        </button>
        <button class="toolbar-button" :class="{ 'is-active': showRuntimeTelemetry }" type="button" :aria-pressed="showRuntimeTelemetry" title="显示或隐藏模型实时信息" @click="toggleRuntimeTelemetry">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 18V6" /><path d="M4 18h16" /><path d="m7 15 4-5 3 3 4-6" /></svg>
          <span>实时信息</span>
        </button>
        <button class="toolbar-button" :class="{ 'is-active': showBackgroundColor }" type="button" :aria-pressed="showBackgroundColor" title="显示或隐藏场景背景颜色" @click="showBackgroundColor = !showBackgroundColor">
          <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="13.5" cy="10.5" r="7.5" /><path d="M6 20.5c1.5-1.5 3.2-2.3 5-2.3 2.1 0 2.5.8 4.2.8 2.9 0 5.3-2.5 5.3-5.5" /><circle cx="8.5" cy="9" r=".9" /><circle cx="12" cy="6.5" r=".9" /><circle cx="16" cy="7.5" r=".9" /></svg>
          <span>背景色</span>
        </button>
        <button class="toolbar-button" :class="{ 'is-active': showDiseaseList }" type="button" :aria-pressed="showDiseaseList" title="显示或隐藏病害列表" @click="showDiseaseList = !showDiseaseList">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 5h14v14H5z" /><path d="M8 9h8M8 12h8M8 15h5" /></svg>
          <span>病害列表</span>
        </button>
      </nav>

      <div class="viewer-actions">
        <el-tooltip content="复位相机" placement="bottom">
          <el-button circle :icon="Camera" :disabled="!hasModel" aria-label="复位相机" @click="resetModelCamera" />
        </el-tooltip>
        <el-tooltip :content="isFullscreen ? '退出全屏' : '全屏显示'" placement="bottom">
          <el-button circle :icon="FullScreen" :class="{ 'is-active': isFullscreen }" :aria-label="isFullscreen ? '退出全屏' : '全屏显示'" @click="toggleFullscreen" />
        </el-tooltip>
      </div>

      <div class="viewer-panels">
        <div v-show="showSceneControl || showRuntimeTelemetry" class="viewer-panel-slot left-panel-slot">
          <div v-show="showSceneControl" class="left-panel-section scene-panel-section" :style="{ flexBasis: showRuntimeTelemetry ? `${scenePanelRatio * 100}%` : '100%' }">
          <ControlPanel
            @clear="clearModels"
            @exportImage="handleExportImage"
            @alignView="handleAlignView"
            @toggleBoundingBox="handleToggleBoundingBox"
            @updateColors="handleUpdateColors"
            @diseaseSelect="toggleDiseaseSelectMode"
          />
          </div>
          <div v-show="showSceneControl && showRuntimeTelemetry" class="left-panel-resizer" role="separator" aria-orientation="horizontal" aria-label="调整场景控制和实时信息高度" @pointerdown="startLeftPanelResize" @pointercancel="stopLeftPanelResize"></div>
          <div v-show="showRuntimeTelemetry" class="left-panel-section telemetry-panel-section" :style="{ flexBasis: showSceneControl ? `${(1 - scenePanelRatio) * 100}%` : '100%' }">
            <ModelInfoPanel ref="modelInfoPanelRef" />
          </div>
        </div>
        <div v-show="showModelLoading" class="viewer-panel-slot backend-panel-slot">
          <BackendModelPanel
            :models="backendModels"
            :loading="backendModelLoading"
            :loading-model-id="backendLoadingModelId"
            :active-model-id="backendModelId"
            :octree-count="backendOctreeIds.length"
            @refresh="fetchBackendModels"
            @load="loadBackendModel"
            @unload="clearModels"
            @localLoad="triggerLoadFolder"
          />
        </div>
      </div>
      <BackgroundColorPanel
        v-show="showBackgroundColor"
        class="background-color-popover"
        :model-value="backgroundColor"
        @update:model-value="handleBackgroundColorChange"
      />

      <div id="viewerContainer">
        <div id="glFullscreen">
          <div id="viewerPane" ref="viewerPaneRef">
            <canvas id="example" ref="renderEle"></canvas>
            <canvas id="hudView" ref="hudViewEle" style="position: absolute; top: 0; left: 0; pointer-events: none; display: none;"></canvas>
          </div>
        </div>
      </div>

      <input 
        type="file" 
        ref="folderInput" 
        webkitdirectory 
        directory 
        multiple 
        style="display: none;" 
        @change="handleFolderSelect"
      >

      <!-- 病害记录面板 (Right Side) -->
      <div class="disease-records-panel" :class="{ 'is-collapsed': isDiseaseRecordsCollapsed }" v-show="showDiseaseList">
        <div class="disease-records-header">
          <div class="disease-records-title">
            <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/></svg>
            病害记录 ({{ diseaseRecords.length }})
          </div>
          <button class="disease-records-toggle" @click="isDiseaseRecordsCollapsed = !isDiseaseRecordsCollapsed">
            <svg v-if="isDiseaseRecordsCollapsed" viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><polyline points="15 18 9 12 15 6"/></svg>
            <svg v-else viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><polyline points="6 15 12 9 18 15"/></svg>
          </button>
        </div>
        <div class="disease-records-body" v-show="!isDiseaseRecordsCollapsed">
          <div 
            v-for="record in diseaseRecords" :key="record.id"
            class="disease-record-item"
            :class="{ 'is-active': highlightedDiseaseId === record.id }"
            @click="highlightDiseaseRecord(record)"
          >
            <div class="disease-record-top">
              <span class="disease-record-type">{{ record.type }}</span>
              <button class="disease-record-delete" @click.stop="deleteDiseaseRecord(record.id)" title="删除记录">×</button>
            </div>
            <div class="disease-record-top">
              <span class="disease-record-severity" :class="'severity-' + record.severity">{{ record.severity }}</span>
            </div>
            <div class="disease-record-bottom">
              <span class="disease-record-date">{{ record.date }}</span>
              <span class="disease-record-recorder">{{ record.recorder }}</span>
            </div>
          </div>
          <div v-if="diseaseRecords.length === 0" class="disease-records-empty">{{ hasModel ? '当前模型暂无病害记录' : '请先加载模型' }}</div>
        </div>
      </div>

      <!-- Monomer Info Panel (Right Side) -->
      <transition name="panel-fade">
        <div class="monomer-info-panel" :class="{ 'is-collapsed': !isMonomerPanelVisible }" v-show="selectedMonomer">
          <div class="monomer-panel-header">
            <div class="monomer-panel-title">
              <svg class="title-icon" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="12" y1="16" x2="12" y2="12"/><line x1="12" y1="8" x2="12.01" y2="8"/></svg>
              构件信息
            </div>
            <div class="monomer-panel-actions">
              <button class="monomer-btn monomer-btn-clear" @click="clearMonomerSelection" title="清除选中">
                <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
              </button>
              <button class="monomer-btn monomer-btn-toggle" @click="isMonomerPanelVisible = !isMonomerPanelVisible" :title="isMonomerPanelVisible ? '收起' : '展开'">
                <svg v-if="isMonomerPanelVisible" viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><polyline points="6 15 12 9 18 15"/></svg>
                <svg v-else viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><polyline points="6 9 12 15 18 9"/></svg>
              </button>
            </div>
          </div>
          <div class="monomer-panel-body" v-show="isMonomerPanelVisible">
            <!-- 构件标识卡片 -->
            <div class="monomer-card">
              <div class="monomer-icon-wrap">
                <svg viewBox="0 0 24 24" width="28" height="28" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/></svg>
              </div>
              <div class="monomer-card-text">
                <div class="monomer-name">{{ selectedMonomer?.name || '未知构件' }}</div>
              </div>
            </div>

            <!-- 属性信息 -->
            <div v-if="monomerInfoList && Object.keys(monomerInfoList).length > 0" class="monomer-props-section">
              <div class="monomer-props-title">属性信息</div>
              <table class="monomer-props-table">
                <tr v-for="(value, key, idx) in monomerInfoList" :key="key" :class="{ 'row-even': idx % 2 === 0 }">
                  <td class="prop-key">{{ key }}</td>
                  <td class="prop-sep"></td>
                  <td class="prop-value">{{ value }}</td>
                </tr>
              </table>
            </div>
            <div v-else class="monomer-no-info">暂无属性信息</div>
          </div>
        </div>
      </transition>

      <!-- 病害多边形框选 SVG 覆盖层（仅绘制模式） -->
      <svg v-if="isDiseaseSelectMode" class="polygon-overlay" @click.stop="handlePolygonClick" @dblclick.prevent="closePolygon">
        <!-- 已完成的多边形边 -->
        <polygon 
          v-if="diseasePolygonPoints.length >= 3"
          :points="diseasePolygonPoints.map(p => `${p.x},${p.y}`).join(' ')"
          fill="rgba(230, 162, 60, 0.15)"
          stroke="#E6A23C"
          stroke-width="2"
          stroke-dasharray="6,3"
        />
        <!-- 正在绘制的边 -->
        <polyline 
          v-if="diseasePolygonPoints.length >= 1"
          :points="diseasePolygonPoints.map(p => `${p.x},${p.y}`).join(' ')"
          fill="none"
          stroke="#E6A23C"
          stroke-width="2"
        />
        <!-- 顶点标记 -->
        <circle 
          v-for="(pt, idx) in diseasePolygonPoints" :key="idx"
          :cx="pt.x" :cy="pt.y" r="5"
          fill="#E6A23C" stroke="#fff" stroke-width="2"
        />
        <!-- 提示文字 -->
        <text v-if="diseasePolygonPoints.length === 0" x="50%" y="86%" text-anchor="middle" fill="#E6A23C" font-size="15" font-weight="600" style="pointer-events:none;">
          点击绘制多边形顶点，双击完成框选
        </text>
        <text v-else-if="diseasePolygonPoints.length < 3" x="50%" y="86%" text-anchor="middle" fill="#E6A23C" font-size="15" font-weight="600" style="pointer-events:none;">
          至少再点{{ 3 - diseasePolygonPoints.length }}个顶点，双击完成
        </text>
      </svg>
    </div>
  </div>

  <!-- 病害录入对话框 -->
  <transition name="dialog-fade">
    <div v-if="showDiseaseDialog" class="disease-dialog-overlay" @click.self="cancelDiseaseDialog">
      <div class="disease-dialog">
        <div class="disease-dialog-header">
          <h3>病害信息录入</h3>
          <button class="disease-dialog-close" @click="cancelDiseaseDialog">
            <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
          </button>
        </div>
        <div class="disease-dialog-body">
          <div class="disease-form-item">
            <label>病害类型</label>
            <select v-model="diseaseForm.type" class="disease-select">
              <option value="">-- 请选择 --</option>
              <option value="墙面开裂">墙面开裂</option>
              <option value="地基沉降">地基沉降</option>
              <option value="城砖断裂">城砖断裂</option>
              <option value="风化剥落">风化剥落</option>
              <option value="渗水侵蚀">渗水侵蚀</option>
              <option value="结构变形">结构变形</option>
              <option value="生物病害">生物病害</option>
              <option value="其他">其他</option>
            </select>
          </div>
          <div class="disease-form-item">
            <label>录入日期</label>
            <input v-model="diseaseForm.date" class="disease-input" readonly />
          </div>
          <div class="disease-form-item">
            <label>录入人</label>
            <input v-model="diseaseForm.recorder" class="disease-input" placeholder="请输入录入人姓名" />
          </div>
          <div class="disease-form-item">
            <label>病害描述</label>
            <textarea v-model="diseaseForm.description" class="disease-textarea" placeholder="请描述病害详情..." rows="4"></textarea>
          </div>
          <div class="disease-form-item">
            <label>严重程度</label>
            <div class="disease-severity">
              <label v-for="level in ['轻度', '中度', '重度']" :key="level" class="severity-radio">
                <input type="radio" v-model="diseaseForm.severity" :value="level" />
                <span>{{ level }}</span>
              </label>
            </div>
          </div>
        </div>
        <div class="disease-dialog-footer">
          <button class="disease-btn disease-btn-cancel" @click="cancelDiseaseDialog">取消</button>
          <button class="disease-btn disease-btn-submit" @click="submitDiseaseInfo">确认录入</button>
        </div>
      </div>
    </div>
  </transition>

  <!-- Hidden Data Elements for JS Logic -->
  <div ref="hiddenStats" style="display: none;"></div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue';
import { onBeforeRouteLeave } from 'vue-router';
import { ElMessage } from 'element-plus';
import { Camera, FullScreen } from '@element-plus/icons-vue';
import * as THREE from '@/assets/modelLOD/build/three.module.js';
import { Viewer, LODScene, modelLod } from '@/assets/modelLOD/build/fullStackLodControl.js';
import { GLTFLoader } from '@/assets/modelLOD/examples/jsm/loaders/GLTFLoader.js';
import ModelInfoPanel from '@/components/ModelInfoPanel.vue';
import ControlPanel from '@/components/ControlPanel2.vue';
import BackendModelPanel from '@/components/BackendModelPanel.vue';
import BackgroundColorPanel from '@/components/BackgroundColorPanel.vue';
import config from '@/config/index.js';
import { createKeyboardRoaming, isTypingTarget } from '@/utils/keyboardRoaming.js';

const apiFetch = (path, options = {}) => fetch(`${config.API_BASE_URL}${path}`, options);

const renderEle = ref(null);
const hudViewEle = ref(null);
const folderInput = ref(null);
const modelInfoPanelRef = ref(null);
const showSceneControl = ref(false);
const showModelLoading = ref(false);
const showRuntimeTelemetry = ref(false);
const showBackgroundColor = ref(false);
const showDiseaseList = ref(false);
const backgroundColor = ref('#07131f');
const canvasContainerRef = ref(null);
const viewerPaneRef = ref(null);
const isFullscreen = ref(false);
const scenePanelRatio = ref(0.58);
let leftPanelResizeState = null;

const hiddenStats = ref(null);
const selectedView = ref('front');

// Monomer related state
const selectedMonomer = ref(null);
const monomerInfoList = ref(null);
const isMonomerPanelVisible = ref(true);

// 病害框选相关状态
const isDiseaseSelectMode = ref(false);
const diseasePolygonPoints = ref([]);   // 2D 屏幕坐标（SVG 绘制用）
const diseaseWorldPoints = ref([]);     // 3D 世界坐标（存储和 3D 渲染用）
const showDiseaseDialog = ref(false);
const diseaseForm = ref({
  type: '',
  date: '',
  recorder: '',
  description: '',
  severity: '中度'
});

// 病害记录列表
const diseaseRecords = ref([]);
const isDiseaseRecordsCollapsed = ref(false);
const highlightedDiseaseId = ref(null);
const highlightedDiseaseLabel = ref('');

// 3D 场景中的多边形对象引用
let diseaseDraw3DLine = null;         // 框选绘制中的 3D 线段
let highlightDisease3DObject = null;  // 历史记录高亮的 3D 多边形

// 模型加载状态
const hasModel = ref(false);

// 后端模型 LOD 状态；本地文件加载仍保留作为离线调试入口
const backendModels = ref([]);
const backendModelLoading = ref(false);
const backendLoadingModelId = ref(null);
const backendModelId = ref(null);
const backendModelOctrees = ref([]);
const backendMode = ref(true);
let backendWs = null;
let backendOctreeIds = [];
let backendCurrentRootCode = '1';
let backendLatestRequestId = 0;
let backendRequestInFlight = false;
let backendCameraDirty = false;
let backendCameraTimer = null;
let backendLastRequestAt = 0;
let backendLastCameraSignature = '';
// Keep visibility updates responsive while the websocket still coalesces
// pointer-move bursts into one in-flight request.
const BACKEND_CAMERA_REQUEST_INTERVAL = 90;

let scene = null;
let viewer = null;
let modelLODManager = null;
let lodFileMap = new Map();
let keyboardRoaming;
let modelGeneration = 0;
let modelAbortController;
let backendRequestGeneration = -1;
let monomerLoadTimer;
let modelRoamingScale = 1;

// Monomer data
let monomerModels = [];        // { name, fileName, object, infoData, originalMaterials, highlighted }
let selectedMonomerModel = null;
const monomerRaycaster = new THREE.Raycaster();
const monomerMouse = new THREE.Vector2();
const pointerDownPos = new THREE.Vector2();

// Highlight material
const highlightEmissive = new THREE.Color('#1aff66');
const highlightEmissiveIntensity = 0.8;

const globals = {
  isUnmounted: false
};

const fetchBackendModels = async () => {
  backendModelLoading.value = true;
  try {
    const response = await apiFetch('/api/data/models/list');
    if (!response.ok) throw new Error(`模型列表请求失败: ${response.status}`);
    backendModels.value = await response.json();
  } catch (error) {
    console.error('[Tiantan] 获取后端模型失败:', error);
    ElMessage.error('无法获取后端模型列表');
  } finally {
    backendModelLoading.value = false;
  }
};

const toggleModelLoading = () => {
  showModelLoading.value = !showModelLoading.value;
  if (showModelLoading.value) {
    showSceneControl.value = false;
    showRuntimeTelemetry.value = false;
  }
  if (showModelLoading.value && backendModels.value.length === 0 && !backendModelLoading.value) {
    fetchBackendModels();
  }
};

const toggleSceneControl = () => {
  showSceneControl.value = !showSceneControl.value;
  if (showSceneControl.value) showModelLoading.value = false;
};

const toggleRuntimeTelemetry = () => {
  showRuntimeTelemetry.value = !showRuntimeTelemetry.value;
  if (showRuntimeTelemetry.value) showModelLoading.value = false;
};

const startLeftPanelResize = event => {
  if (!showSceneControl.value || !showRuntimeTelemetry.value) return;
  event.preventDefault();
  leftPanelResizeState = { startY: event.clientY, startRatio: scenePanelRatio.value };
  document.body.classList.add('is-resizing-panels');
  window.addEventListener('pointermove', handleLeftPanelResize);
  window.addEventListener('pointerup', stopLeftPanelResize, { once: true });
  window.addEventListener('pointercancel', stopLeftPanelResize, { once: true });
};

const handleLeftPanelResize = event => {
  if (!leftPanelResizeState) return;
  const panel = document.querySelector('.left-panel-slot');
  if (!panel) return;
  const height = panel.getBoundingClientRect().height;
  if (height <= 0) return;
  const deltaRatio = (event.clientY - leftPanelResizeState.startY) / height;
  scenePanelRatio.value = Math.min(0.82, Math.max(0.2, leftPanelResizeState.startRatio + deltaRatio));
};

const stopLeftPanelResize = () => {
  leftPanelResizeState = null;
  document.body.classList.remove('is-resizing-panels');
  window.removeEventListener('pointermove', handleLeftPanelResize);
  window.removeEventListener('pointercancel', stopLeftPanelResize);
};

const getBackendCameraSignature = () => {
  if (!scene?.camera) return '';
  const { camera } = scene;
  const canvas = scene.canvas;
  return [
    camera.position.x, camera.position.y, camera.position.z,
    camera.quaternion.x, camera.quaternion.y, camera.quaternion.z, camera.quaternion.w,
    camera.fov, camera.aspect, canvas?.clientWidth || 0, canvas?.clientHeight || 0,
  ].map(value => Number(value).toFixed(4)).join('|');
};

const scheduleBackendCamera = ({ immediate = false, force = false } = {}) => {
  if (!hasModel.value || globals.isUnmounted) return;
  if (!backendMode.value || !backendWs || backendWs.readyState !== WebSocket.OPEN || !scene?.camera || !backendOctreeIds.length) return;
  if (force) backendLastCameraSignature = '';
  backendCameraDirty = true;
  if (backendRequestInFlight) return;

  if (backendCameraTimer !== null) {
    if (!immediate) return;
    window.clearTimeout(backendCameraTimer);
    backendCameraTimer = null;
  }

  const delay = immediate ? 0 : Math.max(0, backendLastRequestAt + BACKEND_CAMERA_REQUEST_INTERVAL - Date.now());
  backendCameraTimer = window.setTimeout(() => {
    backendCameraTimer = null;
    sendBackendCamera();
  }, delay);
};

const sendBackendCamera = () => {
  if (!hasModel.value || !backendMode.value || globals.isUnmounted) return;
  if (!backendWs || backendWs.readyState !== WebSocket.OPEN || !scene?.camera || !backendOctreeIds.length) return;
  if (backendRequestInFlight) {
    backendCameraDirty = true;
    return;
  }
  scene.camera.updateMatrixWorld();
  scene.camera.updateProjectionMatrix();
  const matrix = new THREE.Matrix4().multiplyMatrices(scene.camera.projectionMatrix, scene.camera.matrixWorldInverse);
  const frustum = new THREE.Frustum();
  // The bundled Three.js build predates setFromProjectionMatrix.
  frustum.setFromMatrix(matrix);
  const cameraSignature = getBackendCameraSignature();
  if (cameraSignature === backendLastCameraSignature && backendLatestRequestId !== 0) {
    backendCameraDirty = false;
    return;
  }
  const requestId = Date.now();
  backendLatestRequestId = requestId;
  backendRequestInFlight = true;
  backendRequestGeneration = modelGeneration;
  backendCameraDirty = false;
  backendLastCameraSignature = cameraSignature;
  backendLastRequestAt = Date.now();
  backendWs.send(JSON.stringify({
    requestId,
    action: 'updateVisibility',
    dataType: 'MODEL',
    octreeIds: backendOctreeIds,
    viewportHeight: scene.canvas?.clientHeight || window.innerHeight,
    cameraFov: scene.camera.fov,
    camera: { position: [scene.camera.position.x, scene.camera.position.y, scene.camera.position.z] },
    frustum: frustum.planes.map(plane => [plane.normal.x, plane.normal.y, plane.normal.z, plane.constant])
  }));
};

const initBackendWebSocket = () => {
  if (backendWs) {
    try { backendWs.close(); } catch { /* ignore */ }
  }
  backendWs = new WebSocket(`${config.WS_URL}/api/scheduling/ws`);
  backendWs.onopen = () => {
    backendRequestInFlight = false;
    backendCameraDirty = false;
    scheduleBackendCamera({ immediate: true });
  };
  backendWs.onmessage = (event) => {
    try {
      const data = JSON.parse(event.data);
      backendRequestInFlight = false;
      if (data.action === 'updateVisibility'
        && backendMode.value
        && hasModel.value && !globals.isUnmounted
        && backendRequestGeneration === modelGeneration
        && (data.requestId == null || Number(data.requestId) === backendLatestRequestId)) {
        modelLODManager?.updateVisibleNodesFromBackend(data.visibleNodes || [], scene);
      }
      if (backendCameraDirty) {
        scheduleBackendCamera();
      }
    } catch (error) { console.error('[Tiantan] LOD 响应解析失败:', error); }
  };
  backendWs.onerror = error => {
    backendRequestInFlight = false;
    console.error('[Tiantan] LOD WebSocket 错误:', error);
  };
};

const loadBackendModel = async (model) => {
  clearModels();
  const generation = modelGeneration;
  const controller = new AbortController();
  modelAbortController = controller;
  const isCurrent = () => generation === modelGeneration && !globals.isUnmounted;
  backendLoadingModelId.value = model.id;
  backendMode.value = true;
  if (scene) scene.localLodEnabled = false;
  try {
    const response = await apiFetch(`/api/data/models/${model.id}/octrees`, { signal: controller.signal });
    if (!response.ok) throw new Error(`八叉树请求失败: ${response.status}`);
    const octrees = await response.json();
    if (!isCurrent()) return;
    if (!octrees.length) throw new Error('模型没有八叉树数据');
    backendModelOctrees.value = octrees;
    backendOctreeIds = octrees.map(item => item.id);
    backendCurrentRootCode = octrees[0].rootNodeCode || '1';
    backendLatestRequestId = 0;
    backendCameraDirty = false;
    backendLastCameraSignature = '';
    modelLODManager?.setRootNodeIds(octrees.map(item => `${item.id}_${item.rootNodeCode || '1'}`));
    modelLODManager?.setBaseUrl(`${config.API_BASE_URL}/api/data/files/`);

    const rootResponse = await apiFetch(`/api/data/files/${backendOctreeIds[0]}/${backendCurrentRootCode}/${backendCurrentRootCode}.json`, { signal: controller.signal });
    if (!isCurrent()) return;
    if (rootResponse.ok) {
      const root = await rootResponse.json();
      if (!isCurrent()) return;
      const bbox = root.infactAabbBox || root.boundingBox;
      if (bbox) {
        const box = new THREE.Box3(
          new THREE.Vector3(bbox.lx, bbox.ly, bbox.lz),
          new THREE.Vector3(bbox.ux, bbox.uy, bbox.uz)
        );
        currentBackendBox = box;
        modelRoamingScale = Math.max(box.getSize(new THREE.Vector3()).length(), 1);
        recenterBackendCamera(box);
      }
    }
    if (!isCurrent()) return;
    backendModelId.value = model.id;
    hasModel.value = true;
    void loadBackendDiseaseRecords(model.id, generation, controller.signal);
    scheduleBackendCamera({ immediate: true, force: true });
    ElMessage.success(`已加载后端模型: ${model.name}`);
  } catch (error) {
    if (!isCurrent() || error.name === 'AbortError') return;
    console.error('[Tiantan] 后端模型加载失败:', error);
    ElMessage.error(`模型加载失败: ${error.message || '未知错误'}`);
    clearModels();
  } finally {
    if (isCurrent()) backendLoadingModelId.value = null;
  }
};

let currentBackendBox = null;
const recenterBackendCamera = (box) => {
  if (!scene?.camera || !scene?.controls) return;
  const center = box.getCenter(new THREE.Vector3());
  const size = box.getSize(new THREE.Vector3());
  const radius = Math.max(size.length() / 2, 1);
  const distance = radius / Math.sin((scene.camera.fov * Math.PI) / 360) * 1.15;
  const viewDirection = new THREE.Vector3(1, 0.72, 1).normalize();
  scene.camera.near = Math.max(0.01, radius / 10000);
  scene.camera.far = Math.max(10000, radius * 40);
  scene.camera.position.copy(center).add(viewDirection.multiplyScalar(distance));
  scene.controls.target.copy(center);
  scene.camera.updateProjectionMatrix();
  scene.controls.update();
};

const getCurrentModelBox = () => {
  if (backendMode.value && currentBackendBox && !currentBackendBox.isEmpty()) {
    return currentBackendBox.clone();
  }
  const box = new THREE.Box3();
  for (const model of scene?.models || []) {
    if (typeof model?.getBoundingBox !== 'function') continue;
    const modelBox = model.getBoundingBox();
    if (modelBox && !modelBox.isEmpty()) box.union(modelBox);
  }
  return box.isEmpty() ? null : box;
};

const resetModelCamera = () => {
  const box = getCurrentModelBox();
  if (!box) {
    ElMessage.warning('当前没有可定位的模型');
    return;
  }
  recenterBackendCamera(box);
  if (backendMode.value) scheduleBackendCamera({ force: true });
};

const handleFullscreenChange = () => {
  isFullscreen.value = document.fullscreenElement === canvasContainerRef.value;
  window.requestAnimationFrame(handleResize);
};

const toggleFullscreen = async () => {
  try {
    if (document.fullscreenElement === canvasContainerRef.value) {
      await document.exitFullscreen();
    } else {
      await canvasContainerRef.value?.requestFullscreen?.();
    }
  } catch (error) {
    console.error('[Tiantan] 全屏切换失败:', error);
    ElMessage.error('无法切换全屏显示');
  }
};

const handleBackendWheel = () => {
  scheduleBackendCamera();
};

const handleBackendCameraChange = () => {
  scheduleBackendCamera();
};

const loadBackendDiseaseRecords = async (modelId, generation, signal) => {
  try {
    const response = await apiFetch(`/api/data/diseases?modelId=${encodeURIComponent(modelId)}`, { signal });
    if (!response.ok) return;
    const records = await response.json();
    if (generation === modelGeneration && !globals.isUnmounted && backendModelId.value === modelId) {
      diseaseRecords.value = records.map(normalizeDiseaseRecord);
    }
  } catch (error) {
    if (error.name !== 'AbortError') console.error('[Tiantan] 病害记录加载失败:', error);
  }
};

const handleResize = () => {
  if (scene && scene.renderer && scene.canvas) {
    scene.resizeDisplayGL();
  }
  if (backendMode.value) scheduleBackendCamera();
};

const handleKeyDown = (event) => {
  if (!hasModel.value || isTypingTarget(event.target) || event.ctrlKey || event.metaKey || event.altKey
    || isDiseaseSelectMode.value || showDiseaseDialog.value) return;
  if (event.key === 'x' || event.key === 'X') {
    if (scene) {
      scene.rotateModels(-Math.PI / 2);
      if (selectedView && selectedView.value) {
          scene.alignView(selectedView.value);
      }
      ElMessage.success('模型已旋转90度');
    }
  }

};

// ---- Monomer Functions ----

/**
 * 清除当前 monomer 选中高亮，恢复透明不可见
 */
const clearMonomerHighlight = () => {
  if (!selectedMonomerModel) return;
  
  const model = selectedMonomerModel;
  model.object.traverse((child) => {
    if (child.isMesh && child.userData.__originalMaterial) {
      child.material = child.userData.__originalMaterial;
      child.renderOrder = 1;
      delete child.userData.__originalMaterial;
    }
  });
  
  selectedMonomerModel = null;
};

/**
 * 高亮选中的 monomer（恢复可见 + 绿色发光）
 */
const highlightMonomer = (model) => {
  // 先清除之前的高亮
  clearMonomerHighlight();
  
  model.object.traverse((child) => {
    if (child.isMesh) {
      child.userData.__originalMaterial = child.material;
      child.renderOrder = 2;
      const materials = Array.isArray(child.material) ? child.material : [child.material];
      const newMaterials = materials.map(mat => {
        const newMat = mat.clone();
        // 恢复可见性
        newMat.transparent = true;
        newMat.opacity = 1;
        newMat.depthWrite = true;
        // Polygon offset 避免与 LOD 重叠闪烁
        newMat.polygonOffset = true;
        newMat.polygonOffsetFactor = -1;
        newMat.polygonOffsetUnits = -1;
        // 绿色发光高亮
        newMat.emissive = highlightEmissive;
        newMat.emissiveIntensity = highlightEmissiveIntensity;
        newMat.needsUpdate = true;
        return newMat;
      });
      child.material = Array.isArray(child.material) ? newMaterials : newMaterials[0];
    }
  });
  
  selectedMonomerModel = model;
};

/**
 * 射线检测 monomer 模型
 */
const raycastMonomers = (event) => {
  if (!scene?.camera || !scene?.renderer || monomerModels.length === 0) {
    return null;
  }

  const rect = scene.renderer.domElement.getBoundingClientRect();
  monomerMouse.x = ((event.clientX - rect.left) / rect.width) * 2 - 1;
  monomerMouse.y = -((event.clientY - rect.top) / rect.height) * 2 + 1;

  monomerRaycaster.setFromCamera(monomerMouse, scene.camera);
  
  // 收集所有 monomer 的 mesh
  const allMeshes = [];
  monomerModels.forEach((model, index) => {
    model.object.traverse((child) => {
      if (child.isMesh) {
        allMeshes.push({ mesh: child, modelIndex: index });
      }
    });
  });

  const intersects = monomerRaycaster.intersectObjects(allMeshes.map(m => m.mesh), false);
  
  if (intersects.length > 0) {
    const hitMesh = intersects[0].object;
    const found = allMeshes.find(m => m.mesh === hitMesh);
    if (found !== undefined) {
      return monomerModels[found.modelIndex];
    }
  }
  
  return null;
};

/**
 * 处理 monomer 点击选择
 */
const handleMonomerClick = (event) => {
  const hitModel = raycastMonomers(event);
  
  if (hitModel) {
    highlightMonomer(hitModel);
    selectedMonomer.value = {
      name: hitModel.name,
      fileName: hitModel.fileName
    };
    
    // 设置 info 数据为动态键值对
    monomerInfoList.value = hitModel.infoData || null;
  } else {
    // 点击空白处，取消高亮
    clearMonomerSelection();
  }
};

const clearMonomerSelection = () => {
  clearMonomerHighlight();
  selectedMonomer.value = null;
  monomerInfoList.value = null;
};

// ---- 病害框选功能 ----

/**
 * 从屏幕坐标射线检测，获取与模型的三维交点
 * 返回 THREE.Vector3 或 null（无交点时用相机前向推算默认深度）
 */
const getWorldPointFromScreen = (clientX, clientY) => {
  if (!scene?.camera || !scene?.renderer || !scene?.scene) return null;

  const rect = scene.renderer.domElement.getBoundingClientRect();
  const mouse = new THREE.Vector2();
  mouse.x = ((clientX - rect.left) / rect.width) * 2 - 1;
  mouse.y = -((clientY - rect.top) / rect.height) * 2 + 1;

  const raycaster = new THREE.Raycaster();
  raycaster.setFromCamera(mouse, scene.camera);

  // 收集场景中所有可射线检测的对象
  const targets = [];
  scene.scene.traverse((child) => {
    if (child.isMesh && child.visible !== false) {
      targets.push(child);
    }
  });

  if (targets.length > 0) {
    const intersects = raycaster.intersectObjects(targets, false);
    if (intersects.length > 0) {
      return intersects[0].point.clone();
    }
  }

  // 无交点时，在相机前方一定深度处投影
  const dist = 50; // 默认深度
  const dir = new THREE.Vector3();
  scene.camera.getWorldDirection(dir);
  const origin = scene.camera.position.clone();
  return origin.addScaledVector(dir, dist);
};

/**
 * 切换病害框选模式
 */
const toggleDiseaseSelectMode = () => {
  if (!hasModel.value) return;
  if (isDiseaseSelectMode.value) {
    exitDiseaseSelectMode();
  } else {
    isDiseaseSelectMode.value = true;
    diseasePolygonPoints.value = [];
    diseaseWorldPoints.value = [];
    ElMessage.info('进入病害框选模式，点击添加顶点，双击完成');
  }
};

const exitDiseaseSelectMode = () => {
  isDiseaseSelectMode.value = false;
  diseasePolygonPoints.value = [];
  diseaseWorldPoints.value = [];
  removeDiseaseDraw3DLine();
};

/**
 * 在 SVG 覆盖层上点击添加多边形顶点
 */
const handlePolygonClick = (event) => {
  if (!isDiseaseSelectMode.value) return;

  // 获取 3D 世界坐标
  const worldPt = getWorldPointFromScreen(event.clientX, event.clientY);
  if (!worldPt) return;

  diseaseWorldPoints.value.push(worldPt);

  // 获取 2D 屏幕坐标（SVG 绘制用）
  const svgEl = event.currentTarget;
  if (!svgEl) return;
  const rect = svgEl.getBoundingClientRect();

  diseasePolygonPoints.value.push({
    x: event.clientX - rect.left,
    y: event.clientY - rect.top
  });

  // 更新 3D 绘制线段
  updateDiseaseDraw3DLine();
};

/**
 * 更新/创建 3D 场景中的绘制线段
 */
const updateDiseaseDraw3DLine = () => {
  removeDiseaseDraw3DLine();
  if (!scene?.scene || diseaseWorldPoints.value.length < 2) return;

  const pts = diseaseWorldPoints.value;
  const isClosed = pts.length >= 3;

  const positions = [];
  for (const p of pts) {
    positions.push(p.x, p.y, p.z);
  }
  // 如果闭合，回到起点
  if (isClosed) {
    positions.push(pts[0].x, pts[0].y, pts[0].z);
  }

  const geometry = new THREE.BufferGeometry();
  geometry.setAttribute('position', new THREE.Float32BufferAttribute(positions, 3));

  const material = new THREE.LineBasicMaterial({
    color: 0xE6A23C,
    linewidth: 1,
    transparent: true,
    opacity: 0.9,
    depthTest: true
  });

  diseaseDraw3DLine = new THREE.Line(geometry, material);
  diseaseDraw3DLine.renderOrder = 999;
  diseaseDraw3DLine.material.depthTest = true;
  diseaseDraw3DLine.material.depthWrite = false;
  scene.scene.add(diseaseDraw3DLine);
};

const removeDiseaseDraw3DLine = () => {
  if (diseaseDraw3DLine && scene?.scene) {
    scene.scene.remove(diseaseDraw3DLine);
    if (diseaseDraw3DLine.geometry) diseaseDraw3DLine.geometry.dispose();
    if (diseaseDraw3DLine.material) diseaseDraw3DLine.material.dispose();
    diseaseDraw3DLine = null;
  }
};

/**
 * 双击闭合多边形，弹出病害录入对话框
 */
const closePolygon = () => {
  if (!isDiseaseSelectMode.value) return;
  if (diseasePolygonPoints.value.length < 3) {
    ElMessage.warning('至少需要3个顶点才能构成多边形');
    return;
  }

  // 自动填充日期
  const now = new Date();
  diseaseForm.value.date = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;

  showDiseaseDialog.value = true;
};

const cancelDiseaseDialog = () => {
  showDiseaseDialog.value = false;
  exitDiseaseSelectMode();
};

/**
 * 在 3D 场景中创建病害多边形 mesh（半透明面 + 边框）
 * 使用简单的三角剖分（扇形 fan triangulation，适用于凸多边形/近似凸多边形）
 */
const createDisease3DPolygon = (worldPoints) => {
  if (!scene?.scene || worldPoints.length < 3) return null;

  const group = new THREE.Group();
  group.renderOrder = 998;

  // 计算法向量（用于面的朝向）
  const center = new THREE.Vector3();
  worldPoints.forEach(p => center.add(p));
  center.divideScalar(worldPoints.length);

  // === 半透明填充面 ===
  const fillGeom = new THREE.BufferGeometry();
  const vertices = [];
  // 扇形三角剖分：以第一个点为扇心
  for (let i = 1; i < worldPoints.length - 1; i++) {
    vertices.push(
      worldPoints[0].x, worldPoints[0].y, worldPoints[0].z,
      worldPoints[i].x, worldPoints[i].y, worldPoints[i].z,
      worldPoints[i + 1].x, worldPoints[i + 1].y, worldPoints[i + 1].z
    );
  }
  fillGeom.setAttribute('position', new THREE.Float32BufferAttribute(vertices, 3));
  fillGeom.computeVertexNormals();

  const fillMat = new THREE.MeshBasicMaterial({
    color: 0xE6A23C,
    side: THREE.DoubleSide,
    transparent: true,
    opacity: 0.2,
    depthTest: true,
    depthWrite: false
  });
  const fillMesh = new THREE.Mesh(fillGeom, fillMat);
  group.add(fillMesh);

  // === 边框线 ===
  const linePts = [];
  for (const p of worldPoints) {
    linePts.push(p.x, p.y, p.z);
  }
  linePts.push(worldPoints[0].x, worldPoints[0].y, worldPoints[0].z); // 闭合
  const lineGeom = new THREE.BufferGeometry();
  lineGeom.setAttribute('position', new THREE.Float32BufferAttribute(linePts, 3));
  const lineMat = new THREE.LineBasicMaterial({
    color: 0xE6A23C,
    linewidth: 1,
    transparent: true,
    opacity: 0.9,
    depthTest: true,
    depthWrite: false
  });
  const line = new THREE.Line(lineGeom, lineMat);
  group.add(line);

  return group;
};

const normalizeDiseaseRecord = (record) => ({
  ...record,
  date: record.date || record.recordDate,
  worldPolygon: record.worldPolygon || (record.points || []).map(point => ({ x: point.x, y: point.y, z: point.z })),
  polygon: record.polygon || []
});

const submitDiseaseInfo = async () => {
  if (!hasModel.value) return;
  const generation = modelGeneration;
  if (!diseaseForm.value.type) {
    ElMessage.warning('请选择病害类型');
    return;
  }
  if (!diseaseForm.value.recorder.trim()) {
    ElMessage.warning('请输入录入人');
    return;
  }

  // 将 3D 世界坐标序列化为普通数组
  const worldPolygon = diseaseWorldPoints.value.map(p => ({ x: p.x, y: p.y, z: p.z }));

  const record = {
    id: `disease_${Date.now()}`,
    polygon: diseasePolygonPoints.value.map(p => ({ x: p.x, y: p.y })), // 保留 2D（兼容旧数据）
    worldPolygon: worldPolygon,  // 新增：3D 世界坐标
    type: diseaseForm.value.type,
    date: diseaseForm.value.date,
    recorder: diseaseForm.value.recorder,
    description: diseaseForm.value.description,
    severity: diseaseForm.value.severity,
    createdAt: new Date().toISOString()
  };

  if (backendMode.value && backendModelId.value) {
    try {
      const response = await apiFetch('/api/data/diseases', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          modelId: backendModelId.value,
          type: record.type,
          severity: record.severity,
          recordDate: record.date,
          recorder: record.recorder,
          description: record.description,
          coordinateSystem: 'MODEL_WORLD',
          points: worldPolygon
        })
      });
      if (!response.ok) throw new Error(await response.text());
      const saved = normalizeDiseaseRecord(await response.json());
      if (generation !== modelGeneration || globals.isUnmounted) return;
      diseaseRecords.value.unshift(saved);
    } catch (error) {
      if (generation !== modelGeneration || globals.isUnmounted) return;
      console.error('[Tiantan] 保存病害失败:', error);
      ElMessage.error('病害保存失败');
      return;
    }
  } else {
    const existing = JSON.parse(localStorage.getItem('disease_records') || '[]');
    existing.push(record);
    localStorage.setItem('disease_records', JSON.stringify(existing));
    diseaseRecords.value.push(record);
  }

  ElMessage.success('病害信息录入成功');

  showDiseaseDialog.value = false;
  exitDiseaseSelectMode();

  diseaseForm.value = {
    type: '',
    date: '',
    recorder: '',
    description: '',
    severity: '中度'
  };
};

/**
 * 删除病害记录
 */
const deleteDiseaseRecord = async (id) => {
  const generation = modelGeneration;
  // 先清除高亮（如果删除的是正在高亮的）
  if (highlightedDiseaseId.value === id) {
    clearDiseaseHighlight();
  }

  if (backendMode.value && backendModelId.value && typeof id === 'number') {
    try {
      const response = await apiFetch(`/api/data/diseases/${id}`, { method: 'DELETE' });
      if (!response.ok) throw new Error(`删除失败: ${response.status}`);
    } catch (error) {
      console.error('[Tiantan] 删除病害失败:', error);
      ElMessage.error('病害删除失败');
      return;
    }
  } else {
    const existing = JSON.parse(localStorage.getItem('disease_records') || '[]');
    localStorage.setItem('disease_records', JSON.stringify(existing.filter(r => r.id !== id)));
  }
  if (generation !== modelGeneration || globals.isUnmounted) return;
  diseaseRecords.value = diseaseRecords.value.filter(r => r.id !== id);

  ElMessage.success('病害记录已删除');
};

/**
 * 高亮显示某个病害记录的多边形
 */
const highlightDiseaseRecord = (record) => {
  if (!hasModel.value) return;
  // 如果点击的是已高亮的，取消
  if (highlightedDiseaseId.value === record.id) {
    clearDiseaseHighlight();
    return;
  }

  // 优先使用 worldPolygon（3D 坐标），回退到 polygon（2D 屏幕坐标）
  let worldPts;
  if (record.worldPolygon && record.worldPolygon.length >= 3) {
    worldPts = record.worldPolygon.map(p => new THREE.Vector3(p.x, p.y, p.z));
  } else if (record.polygon && record.polygon.length >= 3) {
    // 旧数据没有 3D 坐标，无法吸附到模型上，提示用户
    ElMessage.warning('该记录为旧格式，没有3D坐标信息，请重新框选录入');
    return;
  } else {
    ElMessage.warning('该记录无有效框选区域');
    return;
  }

  clearDiseaseHighlight();

  highlightedDiseaseId.value = record.id;
  highlightedDiseaseLabel.value = `${record.type} · ${record.date}`;

  // 在 3D 场景中创建高亮多边形（绿色）
  const group = new THREE.Group();
  group.renderOrder = 1000;

  // 半透明填充面（绿色）
  const fillGeom = new THREE.BufferGeometry();
  const vertices = [];
  for (let i = 1; i < worldPts.length - 1; i++) {
    vertices.push(
      worldPts[0].x, worldPts[0].y, worldPts[0].z,
      worldPts[i].x, worldPts[i].y, worldPts[i].z,
      worldPts[i + 1].x, worldPts[i + 1].y, worldPts[i + 1].z
    );
  }
  fillGeom.setAttribute('position', new THREE.Float32BufferAttribute(vertices, 3));
  fillGeom.computeVertexNormals();

  const fillMat = new THREE.MeshBasicMaterial({
    color: 0x1aff66,
    side: THREE.DoubleSide,
    transparent: true,
    opacity: 0.3,
    depthTest: true,
    depthWrite: false
  });
  const fillMesh = new THREE.Mesh(fillGeom, fillMat);
  group.add(fillMesh);

  // 边框线（绿色）
  const linePts = [];
  for (const p of worldPts) {
    linePts.push(p.x, p.y, p.z);
  }
  linePts.push(worldPts[0].x, worldPts[0].y, worldPts[0].z);
  const lineGeom = new THREE.BufferGeometry();
  lineGeom.setAttribute('position', new THREE.Float32BufferAttribute(linePts, 3));
  const lineMat = new THREE.LineBasicMaterial({
    color: 0x1aff66,
    linewidth: 1,
    transparent: true,
    opacity: 1,
    depthTest: true,
    depthWrite: false
  });
  const line = new THREE.Line(lineGeom, lineMat);
  group.add(line);

  highlightDisease3DObject = group;
  scene.scene.add(group);

  // 关闭框选模式
  if (isDiseaseSelectMode.value) {
    exitDiseaseSelectMode();
  }
};

/**
 * 清除病害记录高亮
 */
const clearDiseaseHighlight = () => {
  if (highlightDisease3DObject && scene?.scene) {
    scene.scene.remove(highlightDisease3DObject);
    highlightDisease3DObject.traverse((child) => {
      if (child.geometry) child.geometry.dispose();
      if (child.material) {
        if (Array.isArray(child.material)) {
          child.material.forEach(m => m.dispose());
        } else {
          child.material.dispose();
        }
      }
    });
    highlightDisease3DObject = null;
  }
  highlightedDiseaseId.value = null;
  highlightedDiseaseLabel.value = '';
};

// Pointer events for distinguishing click from drag
const onPointerDown = (event) => {
  // 病害框选模式下不处理
  if (isDiseaseSelectMode.value) return;
  pointerDownPos.set(event.clientX, event.clientY);
};

const onPointerUp = (event) => {
  // 病害框选模式下不处理
  if (isDiseaseSelectMode.value) return;
  const dist = pointerDownPos.distanceTo(new THREE.Vector2(event.clientX, event.clientY));
  // 如果移动距离小于阈值，认为是点击
  if (dist < 5) {
    handleMonomerClick(event);
  }
};

/**
 * 从 file map 中查找 info 数据
 * 支持 info.json 和 info.txt
 */
const findInfoData = (fileMap, glbPath) => {
  // glbPath like: "Monomer/part1/model.glb" or "Monomer/model.glb"
  const dir = glbPath.substring(0, glbPath.lastIndexOf('/'));
  const baseName = glbPath.substring(glbPath.lastIndexOf('/') + 1).replace(/\.glb$/i, '');
  
  // 尝试多种 info 文件命名模式
  const candidates = [
    `${dir}/info.json`,
    `${dir}/${baseName}_info.json`,
    `${dir}/${baseName}.info.json`,
    // 兼容 info.txt
    `${dir}/info.txt`,
    `${dir}/${baseName}_info.txt`,
    `${dir}/${baseName}.info.txt`,
  ];
  
  for (const candidate of candidates) {
    const url = fileMap.get(candidate);
    if (url) {
      return { url, isJson: candidate.endsWith('.json') };
    }
  }
  
  // 也尝试规范化路径
  for (const candidate of candidates) {
    const normalized = candidate.replace(/\\/g, '/');
    const url = fileMap.get(normalized);
    if (url) {
      return { url, isJson: normalized.endsWith('.json') };
    }
  }
  
  return null;
};

/**
 * 解析 info 文件内容
 */
const parseInfoContent = async (blobUrl, isJson) => {
  try {
    const response = await fetch(blobUrl);
    const text = await response.text();
    
    if (isJson) {
      return JSON.parse(text);
    } else {
      // 解析 txt 格式：每行 key: value 或 key=value
      const result = {};
      const lines = text.split(/\r?\n/);
      for (const line of lines) {
        const trimmed = line.trim();
        if (!trimmed) continue;
        
        const colonIdx = trimmed.indexOf(':');
        const eqIdx = trimmed.indexOf('=');
        let sepIdx = -1;
        
        if (colonIdx !== -1 && eqIdx !== -1) {
          sepIdx = Math.min(colonIdx, eqIdx);
        } else if (colonIdx !== -1) {
          sepIdx = colonIdx;
        } else if (eqIdx !== -1) {
          sepIdx = eqIdx;
        }
        
        if (sepIdx !== -1) {
          const key = trimmed.substring(0, sepIdx).trim();
          const value = trimmed.substring(sepIdx + 1).trim();
          result[key] = value;
        }
      }
      return result;
    }
  } catch (error) {
    console.warn('解析 info 文件失败:', error);
    return null;
  }
};

/**
 * 加载单个 monomer GLB 模型
 */
const loadMonomerModel = async (blobUrl, name, fileName) => {
  return new Promise((resolve, reject) => {
    const loader = new GLTFLoader();
    
    fetch(blobUrl)
      .then(response => response.arrayBuffer())
      .then(arrayBuffer => {
        loader.parse(arrayBuffer, '', (gltf) => {
          resolve({ scene: gltf.scene, name, fileName });
        }, (error) => {
          reject(error);
        });
      })
      .catch(reject);
  });
};

/**
 * 加载所有 Monomer 模型
 */
const loadAllMonomers = async (fileMap, generation) => {
  if (generation !== modelGeneration || globals.isUnmounted) return;
  // 清理旧模型
  unloadAllMonomers();
  
  const monomerGlbFiles = [];
  
  // 扫描 Monomer 目录下的所有 .glb 文件
  for (const [path, url] of fileMap.entries()) {
    const normalizedPath = path.replace(/\\/g, '/');
    if (normalizedPath.toLowerCase().endsWith('.glb') && 
        (normalizedPath.toLowerCase().includes('/monomer/') || 
         normalizedPath.toLowerCase().startsWith('monomer/'))) {
      
      const dir = normalizedPath.substring(0, normalizedPath.lastIndexOf('/'));
      const fileName = normalizedPath.substring(normalizedPath.lastIndexOf('/') + 1);
      const baseName = fileName.replace(/\.glb$/i, '');
      
      monomerGlbFiles.push({
        path: normalizedPath,
        url,
        name: baseName,
        fileName,
        dir
      });
    }
  }
  
  if (monomerGlbFiles.length === 0) {
    return;
  }
  
  console.log(`发现 ${monomerGlbFiles.length} 个 Monomer GLB 模型文件`);
  
  for (const glbFile of monomerGlbFiles) {
    if (globals.isUnmounted || generation !== modelGeneration) break;
    
    // 查找 info 文件
    const infoFile = findInfoData(fileMap, glbFile.path);
    
    try {
      const result = await loadMonomerModel(glbFile.url, glbFile.name, glbFile.fileName);
      if (globals.isUnmounted || generation !== modelGeneration) {
        disposeMonomerObject(result.scene);
        break;
      }
      
      // 加载 info 数据
      let infoData = null;
      if (infoFile) {
        infoData = await parseInfoContent(infoFile.url, infoFile.isJson);
      }
      if (globals.isUnmounted || generation !== modelGeneration) {
        disposeMonomerObject(result.scene);
        break;
      }
      
      // 设为透明不可见（但保留 visible=true 以支持射线检测）
      result.scene.name = `monomer_${glbFile.name}`;
      result.scene.traverse((child) => {
        if (child.isMesh && child.material) {
          child.renderOrder = 1;
          const materials = Array.isArray(child.material) ? child.material : [child.material];
          materials.forEach(mat => {
            mat.transparent = true;
            mat.opacity = 0;
            mat.depthWrite = false;
            mat.needsUpdate = true;
          });
        }
      });
      
      // 添加到场景
      scene.scene.add(result.scene);
      
      monomerModels.push({
        name: glbFile.name,
        fileName: glbFile.fileName,
        object: result.scene,
        infoData,
        originalMaterials: [],
        highlighted: false
      });
      
      console.log(`已加载构件: ${glbFile.name}`);
    } catch (error) {
      console.warn(`加载构件 ${glbFile.name} 失败:`, error);
    }
  }
};

/**
 * 卸载所有 monomer 模型
 */
const disposeMonomerObject = object => {
  object.parent?.remove(object);
  object.traverse(child => {
    child.geometry?.dispose();
    const materials = Array.isArray(child.material) ? child.material : [child.material];
    for (const material of materials) {
      if (!material) continue;
      material.map?.dispose();
      material.dispose();
    }
  });
};

const unloadAllMonomers = () => {
  clearMonomerHighlight();
  selectedMonomer.value = null;
  monomerInfoList.value = null;
  
  monomerModels.forEach(model => {
    disposeMonomerObject(model.object);
  });
  monomerModels = [];
};

onMounted(() => {
  globals.isUnmounted = false;
  
  scene = new LODScene(
    renderEle.value,
    null,
    modelInfoPanelRef.value?.trianglesRef,
    modelInfoPanelRef.value?.visiblenodesRef,
    null,
    hiddenStats.value
  );
  
  scene.initGL();
  scene.resizeDisplayGL();
  // TrackballControls reserves A/S/D for mouse modes by default.
  scene.controls.keys = [];
  keyboardRoaming = createKeyboardRoaming({
    camera: scene.camera,
    controls: scene.controls,
    isEnabled: () => hasModel.value && !isDiseaseSelectMode.value && !showDiseaseDialog.value,
    getScale: () => modelRoamingScale,
  });

  viewer = new Viewer(scene);
  viewer.beforeRender = time => keyboardRoaming.update(time);
  viewer.start();
  
  modelLODManager = new modelLod();

  // 注册射线检测事件
  viewerPaneRef.value?.addEventListener('pointerdown', onPointerDown);
  viewerPaneRef.value?.addEventListener('pointerup', onPointerUp);

  window.addEventListener('resize', handleResize);
  window.addEventListener('keydown', handleKeyDown);
  document.addEventListener('fullscreenchange', handleFullscreenChange);
  if (renderEle.value) {
    renderEle.value.addEventListener('wheel', handleBackendWheel, { passive: true });
    renderEle.value.addEventListener('mouseup', handleBackendWheel);
  }
  scene.controls?.addEventListener('change', handleBackendCameraChange);

  initBackendWebSocket();
});

onBeforeRouteLeave((to, from, next) => {
  if (to.path !== from.path) {
    window.location.href = to.fullPath;
  } else {
    next();
  }
});

onBeforeUnmount(() => {
  globals.isUnmounted = true;
  clearModels();
  keyboardRoaming?.dispose();
  window.removeEventListener('resize', handleResize);
  window.removeEventListener('keydown', handleKeyDown);
  document.removeEventListener('fullscreenchange', handleFullscreenChange);
  if (renderEle.value) {
    renderEle.value.removeEventListener('wheel', handleBackendWheel);
    renderEle.value.removeEventListener('mouseup', handleBackendWheel);
  }
  scene?.controls?.removeEventListener('change', handleBackendCameraChange);
  if (backendCameraTimer !== null) window.clearTimeout(backendCameraTimer);
  stopLeftPanelResize();
  if (backendWs) {
    try { backendWs.onmessage = null; backendWs.close(); } catch { /* ignore */ }
    backendWs = null;
  }
  viewerPaneRef.value?.removeEventListener('pointerdown', onPointerDown);
  viewerPaneRef.value?.removeEventListener('pointerup', onPointerUp);
  
  unloadAllMonomers();
  
  // 清理 3D 病害多边形
  removeDiseaseDraw3DLine();
  clearDiseaseHighlight();
  
  if (viewer) {
    try {
      viewer.stop();
    } catch(e) { console.warn("viewer stop err", e); }
  }
  
  if (scene) {
    try {
      if (scene.renderer) {
        scene.renderer.setAnimationLoop(null);
      }
      scene.render = function() {}; 
    } catch (e) {
      console.warn("Error during scene clear bindings", e);
    }
  }

  if (lodFileMap) {
    lodFileMap.clear();
  }
  modelLODManager?.resetBackendState();
  window.LOD_FILE_MAP = null;
  modelLODManager = null;
});

const triggerLoadFolder = () => {
  if (!folderInput.value) {
    ElMessage.error('本地文件选择器未初始化，请刷新页面后重试');
    return;
  }
  folderInput.value.value = '';
  folderInput.value.click();
};

const handleToggleBoundingBox = (visible) => {
  if (scene) {
    scene.toggleBoundingBox(visible);
    modelLODManager?.setBoundingBoxVisible(visible, scene);
    ElMessage.success(`节点包围盒已${visible ? '显示' : '隐藏'}`);
  }
};

const handleUpdateColors = (colors) => {
  if (scene) {
    scene.updateModelColors(colors.solid);
  }
};

const handleBackgroundColorChange = (color) => {
  backgroundColor.value = color;
  if (!scene?.renderer) return;
  scene.renderer.setClearColor(color, 1);
  if (scene.scene) scene.scene.background = new THREE.Color(color);
};

const handleExportImage = (options) => {
  if (!scene) return;
  
  const resolution = typeof options === 'string' ? options : options.resolution;
  const projection = typeof options === 'string' ? 'perspective' : options.projection;
  
  const [width, height] = resolution.split('x').map(Number);
  
  try {
    const dataURL = scene.exportImage(width, height, projection);
    if (dataURL) {
      const link = document.createElement('a');
      link.download = `tiantan_export_${projection}_${resolution}.png`;
      link.href = dataURL;
      link.click();
      ElMessage.success(`图片已导出 (${projection}, ${resolution})`);
    } else {
      ElMessage.error('图片导出失败');
    }
  } catch (error) {
    console.error('Export error:', error);
    ElMessage.error('图片导出失败，请检查控制台报错');
  }
};

const handleAlignView = (viewType) => {
  if (!scene) {
    return;
  }
  
  selectedView.value = viewType;
  
  const success = scene.alignView(viewType);
  if (success) {
    const viewNames = {
      'front': '正视图', 'back': '后视图', 'left': '左视图', 
      'right': '右视图', 'top': '顶视图', 'bottom': '底视图'
    };
    ElMessage.success(`已切换至 ${viewNames[viewType]}`);
    if (backendMode.value) scheduleBackendCamera({ force: true });
  } else {
    ElMessage.warning('没有可对齐的模型');
  }
};

const clearModels = () => {
  ++modelGeneration;
  modelAbortController?.abort();
  modelAbortController = undefined;
  window.clearTimeout(monomerLoadTimer);
  monomerLoadTimer = undefined;
  if (backendCameraTimer !== null) window.clearTimeout(backendCameraTimer);
  backendCameraTimer = null;
  backendCameraDirty = false;
  backendLastCameraSignature = '';
  // Keep an older WS request in flight until its response arrives: the server
  // drops concurrent requests on a session. Its generation is now invalid.
  backendLoadingModelId.value = null;
  keyboardRoaming?.reset();
  exitDiseaseSelectMode();
  clearDiseaseHighlight();
  showDiseaseDialog.value = false;
  diseaseForm.value = { type: '', date: '', recorder: '', description: '', severity: '中度' };
  if (scene) {
    try {
      modelLODManager?.resetBackendState();
      scene.clearModels();
      scene.localLodEnabled = false;
      scene.visibleModels = [];
    } catch(e) {
      console.warn(e);
    }
  }
  
  for (const url of lodFileMap.values()) URL.revokeObjectURL(url);
  lodFileMap.clear();
  window.LOD_FILE_MAP = null;
  backendModelId.value = null;
  backendModelOctrees.value = [];
  backendOctreeIds = [];
  currentBackendBox = null;
  modelRoamingScale = 1;
  unloadAllMonomers();
  clearMonomerSelection();
  hasModel.value = false;
  diseaseRecords.value = [];
  backendLatestRequestId = 0;
  
  if (folderInput.value) {
    folderInput.value.value = '';
  }
};

const handleFolderSelect = (event) => {
  const files = Array.from(event.target.files);
  if (!files.length) return;

  if (!scene) {
    ElMessage.error('三维场景尚未初始化，请稍后重试');
    return;
  }
  if (!modelLODManager) {
    modelLODManager = new modelLod();
  }

  clearModels();
  const generation = modelGeneration;
  backendMode.value = false;
  scene.localLodEnabled = true;

  const modelRoots = new Set();

  for (let i = 0; i < files.length; i++) {
    const file = files[i];
    const path = file.webkitRelativePath || file.name;
    const blobUrl = URL.createObjectURL(file);
    
    const normalizedPath = path.replace(/\\/g, '/');
    lodFileMap.set(normalizedPath, blobUrl);
    
    // The browser may report either "model/1/1.json" or "1/1.json"
    // depending on which folder level the user selected.
    if (/(^|\/)1\/1\.json$/.test(normalizedPath)) {
      const rootDir = normalizedPath.substring(0, normalizedPath.length - 6);
      modelRoots.add(rootDir);
    }
  }

  console.log("Detected Model Roots:", modelRoots);

  if (modelRoots.size === 0) {
    clearModels();
    ElMessage.warning('未识别到 LOD 根文件，请选择包含 1/1.json 及对应 GLB 文件的模型目录');
    event.target.value = '';
    return;
  }

  window.LOD_FILE_MAP = lodFileMap;
  ElMessage.info(`已识别 ${modelRoots.size} 个本地 LOD 模型，正在加载`);

  // 加载 LOD 模型
  modelRoots.forEach(rootDir => {
    console.log("Loading model from root:", rootDir);
    
    if (globals.isUnmounted) return;

    modelLODManager.load(rootDir, '1', scene, function (resultModel) {
      if (globals.isUnmounted || !scene || generation !== modelGeneration) {
        if (resultModel) modelLODManager?._disposeModel(resultModel);
        return;
      }
      
      if (resultModel) {
        scene.addModel(resultModel);
        
        let combinedBox = new THREE.Box3();
        if (scene.models && scene.models.length > 0) {
          for (let m of scene.models) {
            if (m.getBoundingBox) {
              combinedBox.union(m.getBoundingBox());
            }
          }
        }
        
        if (!combinedBox.isEmpty()) {
          modelRoamingScale = Math.max(combinedBox.getSize(new THREE.Vector3()).length(), 1);
          let center = new THREE.Vector3();
          combinedBox.getCenter(center);
          window.currentModelCenter = center;
          
          if (scene.controls) {
             scene.controls.target.copy(center);
          }
        }
        console.log("Model loaded successfully:", rootDir);
        if (!hasModel.value) {
          try {
            diseaseRecords.value = JSON.parse(localStorage.getItem('disease_records') || '[]');
          } catch (error) { console.warn('加载病害记录失败:', error); }
        }
        hasModel.value = true;
      } else {
        console.error("Failed to load model:", rootDir);
      }
    });
  });
  
  // 异步加载 Monomer 模型
  monomerLoadTimer = setTimeout(() => {
    monomerLoadTimer = undefined;
    loadAllMonomers(lodFileMap, generation);
  }, 500);
  
  event.target.value = '';
};
</script>

<style scoped>
.main-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  position: relative;
  overflow: hidden;
  height: 100%;
}

.canvas-container {
  flex: 1;
  position: relative;
  background-color: #f0f0f0;
  overflow: hidden;
}

#viewerContainer {
  width: 100%;
  height: 100%;
}

#glFullscreen {
  width: 100%;
  height: 100%;
  display: flex;
}

#viewerPane {
  flex: 1;
  position: relative;
  width: 100%;
  height: 100%;
}

canvas {
  display: block;
  width: 100%;
  height: 100%;
}

/* === Monomer Info Panel (Right Side) === */
.panel-fade-enter-active,
.panel-fade-leave-active {
  transition: all 0.3s ease;
}

.panel-fade-enter-from,
.panel-fade-leave-to {
  opacity: 0;
  transform: translateX(20px);
}

.monomer-info-panel {
  position: absolute;
  top: 20px;
  right: 20px;
  width: 300px;
  max-height: calc(100% - 40px);
  background: rgba(255, 255, 255, 0.92);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
  border-radius: 12px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.1), 0 1px 4px rgba(0, 0, 0, 0.06);
  border: 1px solid rgba(255, 255, 255, 0.6);
  z-index: 15;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  font-family: 'Helvetica Neue', Helvetica, 'PingFang SC', 'Microsoft YaHei', Arial, sans-serif;
  transition: max-height 0.35s ease, box-shadow 0.35s ease;
}

.monomer-info-panel.is-collapsed {
  max-height: 52px;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.08);
}

.monomer-panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 18px;
  background: linear-gradient(90deg, rgba(21,125,145,.9), rgba(11,46,61,.95));
  flex-shrink: 0;
}

.monomer-panel-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
  font-weight: 600;
  color: #ffffff;
  letter-spacing: 0.5px;
}

.title-icon {
  flex-shrink: 0;
  opacity: 0.85;
}

.monomer-panel-actions {
  display: flex;
  gap: 4px;
}

.monomer-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border: 1px solid rgba(255, 255, 255, 0.3);
  background: rgba(255, 255, 255, 0.15);
  color: rgba(255, 255, 255, 0.9);
  border-radius: 6px;
  cursor: pointer;
  font-family: inherit;
  font-size: 12px;
  transition: all 0.2s ease;
}

.monomer-btn:hover {
  background: rgba(255, 255, 255, 0.28);
  border-color: rgba(255, 255, 255, 0.5);
  color: #ffffff;
}

.monomer-btn-clear:hover {
  background: rgba(245, 108, 108, 0.5);
  border-color: rgba(245, 108, 108, 0.7);
}

.monomer-panel-body {
  padding: 18px;
  overflow-y: auto;
  flex: 1;
}

.monomer-panel-body::-webkit-scrollbar {
  width: 4px;
}

.monomer-panel-body::-webkit-scrollbar-thumb {
  background: #d0d5dd;
  border-radius: 2px;
}

/* 构件标识卡片 */
.monomer-card {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 16px;
  background: linear-gradient(135deg, #f8f9ff 0%, #f0f2ff 100%);
  border-radius: 10px;
  border: 1px solid #e8ebf5;
  margin-bottom: 16px;
}

.monomer-icon-wrap {
  width: 48px;
  height: 48px;
  border-radius: 10px;
  background: rgba(85,214,230,.16);
  display: flex;
  align-items: center;
  justify-content: center;
  color: #ffffff;
  flex-shrink: 0;
  box-shadow: 0 4px 10px rgba(102, 126, 234, 0.3);
}

.monomer-card-text {
  flex: 1;
  min-width: 0;
}

.monomer-name {
  font-size: 16px;
  font-weight: 700;
  color: var(--ui-text);
  margin-bottom: 4px;
  line-height: 1.3;
}

.monomer-file {
  font-size: 11px;
  color: #8e8ea0;
  word-break: break-all;
  font-family: 'SF Mono', 'Menlo', 'Consolas', monospace;
}

/* 属性信息 */
.monomer-props-section {
  margin-top: 0;
}

.monomer-props-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--ui-cyan);
  text-transform: uppercase;
  letter-spacing: 1.5px;
  margin-bottom: 10px;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--ui-cyan);
  display: inline-block;
}

.monomer-props-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
  border-radius: 8px;
  overflow: hidden;
}

.monomer-props-table tr {
  transition: background 0.15s ease;
}

.monomer-props-table tr.row-even {
  background: #fafbfc;
}

.monomer-props-table tr:hover {
  background: #f0f2ff;
}

.prop-key {
  padding: 8px 12px 8px 0;
  color: #667085;
  font-weight: 500;
  white-space: nowrap;
  vertical-align: top;
  font-size: 12px;
}

.prop-sep {
  width: 16px;
  padding: 8px 0;
  vertical-align: top;
}

.prop-sep::after {
  content: '';
  display: block;
  width: 100%;
  height: 1px;
  background: #e0e0e0;
  margin-top: 9px;
}

.prop-value {
  padding: 8px 0;
  color: #d3e3e8;
  font-weight: 600;
  word-break: break-all;
  vertical-align: top;
  font-size: 13px;
}

.monomer-no-info {
  color: #c0c4cc;
  font-size: 13px;
  text-align: center;
  padding: 20px 0;
  font-style: italic;
}

/* === 病害多边形框选 SVG 覆盖层 === */
.polygon-overlay {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  z-index: 18;
  cursor: crosshair;
  pointer-events: auto;
}

/* === 病害录入对话框 === */
.dialog-fade-enter-active,
.dialog-fade-leave-active {
  transition: all 0.3s ease;
}

.dialog-fade-enter-from,
.dialog-fade-leave-to {
  opacity: 0;
}

.dialog-fade-enter-from .disease-dialog,
.dialog-fade-leave-to .disease-dialog {
  transform: scale(0.9);
}

.disease-dialog-overlay {
  position: fixed;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  background: rgba(0, 0, 0, 0.45);
  backdrop-filter: blur(4px);
  z-index: 1000;
  display: flex;
  align-items: center;
  justify-content: center;
}

.disease-dialog {
  width: 460px;
  max-height: 90vh;
  background: #0b1a27;
  border-radius: 12px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.2);
  overflow: hidden;
  transition: transform 0.3s ease;
  font-family: 'Helvetica Neue', Helvetica, 'PingFang SC', 'Microsoft YaHei', Arial, sans-serif;
}

.disease-dialog-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 18px 24px;
  background: linear-gradient(90deg, rgba(21,125,145,.9), rgba(11,46,61,.95));
}

.disease-dialog-header h3 {
  margin: 0;
  font-size: 17px;
  font-weight: 600;
  color: #ffffff;
  letter-spacing: 0.5px;
}

.disease-dialog-close {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border: none;
  background: rgba(255, 255, 255, 0.15);
  color: rgba(255, 255, 255, 0.9);
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.2s;
}

.disease-dialog-close:hover {
  background: rgba(255, 255, 255, 0.3);
}

.disease-dialog-body {
  padding: 24px;
  overflow-y: auto;
  max-height: calc(90vh - 140px);
}

.disease-form-item {
  margin-bottom: 18px;
}

.disease-form-item label {
  display: block;
  font-size: 13px;
  font-weight: 600;
  color: #475467;
  margin-bottom: 6px;
}

.disease-input,
.disease-select,
.disease-textarea {
  width: 100%;
  padding: 10px 12px;
  border: 1px solid #d0d5dd;
  border-radius: 8px;
  font-size: 14px;
  color: #d3e3e8;
  background: #fafbfc;
  font-family: inherit;
  transition: border-color 0.2s, box-shadow 0.2s;
  box-sizing: border-box;
}

.disease-input:focus,
.disease-select:focus,
.disease-textarea:focus {
  outline: none;
  border-color: var(--ui-cyan);
  box-shadow: 0 0 0 3px rgba(102, 126, 234, 0.15);
  background: rgba(4,16,25,.95);
}

.disease-input[readonly] {
  color: #667085;
  background: #f5f6f8;
  cursor: default;
}

.disease-textarea {
  resize: vertical;
  min-height: 80px;
}

.disease-select {
  cursor: pointer;
}

.disease-severity {
  display: flex;
  gap: 16px;
}

.severity-radio {
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  font-size: 14px;
  color: #475467;
}

.severity-radio input[type="radio"] {
  accent-color: var(--ui-cyan);
  width: 16px;
  height: 16px;
}

.disease-dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  padding: 16px 24px;
  background: #f9fafb;
  border-top: 1px solid #e8ebf0;
}

.disease-btn {
  padding: 9px 24px;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  border: none;
  font-family: inherit;
  transition: all 0.2s;
}

.disease-btn-cancel {
  background: rgba(4,16,25,.45);
  color: #475467;
  border: 1px solid #d0d5dd;
}

.disease-btn-cancel:hover {
  background: #f5f6f8;
}

.disease-btn-submit {
  background: var(--ui-cyan);
  color: #ffffff;
}

.disease-btn-submit:hover {
  opacity: 0.9;
}

/* === 病害记录面板 (Left Side) === */
.disease-records-panel {
  position: absolute;
  left: 10px;
  bottom: 20px;
  width: 260px;
  max-height: calc(50vh);
  background: rgba(255, 255, 255, 0.92);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
  border-radius: 12px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.1), 0 1px 4px rgba(0, 0, 0, 0.06);
  border: 1px solid rgba(255, 255, 255, 0.6);
  z-index: 17;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  font-family: 'Helvetica Neue', Helvetica, 'PingFang SC', 'Microsoft YaHei', Arial, sans-serif;
  transition: max-height 0.3s ease;
}

.disease-records-panel.is-collapsed {
  max-height: 44px;
}

.disease-records-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 11px 16px;
  background: linear-gradient(90deg, rgba(125,82,32,.9), rgba(66,48,28,.9));
  flex-shrink: 0;
}

.disease-records-title {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 13px;
  font-weight: 600;
  color: #ffffff;
  letter-spacing: 0.3px;
}

.disease-records-toggle {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border: none;
  background: rgba(255, 255, 255, 0.15);
  color: rgba(255, 255, 255, 0.9);
  border-radius: 4px;
  cursor: pointer;
  transition: all 0.2s;
}

.disease-records-toggle:hover {
  background: rgba(255, 255, 255, 0.28);
}

.disease-records-body {
  overflow-y: auto;
  flex: 1;
  padding: 8px;
}

.disease-records-body::-webkit-scrollbar {
  width: 4px;
}

.disease-records-body::-webkit-scrollbar-thumb {
  background: #d0d5dd;
  border-radius: 2px;
}

.disease-record-item {
  padding: 10px 12px;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s ease;
  margin-bottom: 4px;
  border: 1px solid transparent;
}

.disease-record-item:hover {
  background: #f0f2ff;
}

.disease-record-item.is-active {
  background: #f0f2ff;
  border-color: rgba(242,179,91,.42);
}

.disease-record-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 4px;
}

.disease-record-type {
  font-size: 13px;
  font-weight: 600;
  color: #d7e5e9;
  flex: 1;
}

.disease-record-delete {
  width: 22px;
  height: 22px;
  border: none;
  background: transparent;
  color: #c0c4cc;
  font-size: 16px;
  font-weight: 700;
  cursor: pointer;
  border-radius: 4px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  transition: all 0.15s;
  line-height: 1;
}

.disease-record-delete:hover {
  background: #f56c6c;
  color: #fff;
}

.disease-record-severity {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 10px;
  font-weight: 500;
}

.severity-轻度 {
  background: #e6f7ff;
  color: #1890ff;
}

.severity-中度 {
  background: rgba(242,179,91,.14);
  color: #fa8c16;
}

.severity-重度 {
  background: rgba(239,118,122,.14);
  color: #f5222d;
}

.disease-record-bottom {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 11px;
  color: #8e8ea0;
}

.disease-record-date {
  font-family: 'SF Mono', 'Menlo', 'Consolas', monospace;
}

.disease-record-recorder {
  color: #667085;
}

/* Unified dark workspace skin. The scene and interaction logic above stay unchanged. */
.canvas-container { background: #07131f; }
.viewer-toolbar { position: absolute; z-index: 40; top: 16px; left: 50%; display: flex; align-items: center; gap: 4px; padding: 4px; border: 1px solid rgba(85,214,230,.22); border-radius: 6px; background: rgba(6,20,31,.9); box-shadow: 0 8px 24px rgba(0,0,0,.24), 0 0 20px rgba(85,214,230,.06); backdrop-filter: blur(14px); transform: translateX(-50%); }
.toolbar-button { display: inline-flex; align-items: center; gap: 7px; min-width: 96px; height: 31px; padding: 0 10px; border: 1px solid transparent; border-radius: 3px; color: #829eaa; background: transparent; font-size: 11px; cursor: pointer; white-space: nowrap; transition: .2s ease; }.toolbar-button:hover { color: #ccecf0; background: rgba(85,214,230,.08); }.toolbar-button.is-active { border-color: rgba(85,214,230,.34); color: var(--ui-cyan); background: rgba(85,214,230,.13); box-shadow: inset 0 0 12px rgba(85,214,230,.06); }.toolbar-button svg { width: 15px; height: 15px; fill: none; stroke: currentColor; stroke-linecap: round; stroke-linejoin: round; stroke-width: 1.7; }
.viewer-actions { position: absolute; z-index: 40; top: 16px; right: 14px; display: flex; gap: 6px; }.viewer-actions :deep(.el-button) { width: 34px; height: 34px; margin: 0; border-color: rgba(108,203,220,.26); background: rgba(5,20,31,.88); color: #a8c7d0; }.viewer-actions :deep(.el-button:hover),.viewer-actions :deep(.el-button.is-active) { border-color: var(--ui-cyan); color: var(--ui-cyan); background: rgba(85,214,230,.12); }
.canvas-container:fullscreen { width: 100vw; height: 100vh; background: #07131f; }
.background-color-popover { position: absolute; z-index: 39; top: 66px; left: 50%; transform: translateX(-50%); }
.viewer-panels { position: absolute; z-index: 30; inset: 0; pointer-events: none; }
.viewer-panel-slot { position: absolute; display: flex; min-width: 0; pointer-events: auto; }
.left-panel-slot,.backend-panel-slot { top: 16px; bottom: 16px; left: 12px; width: 320px; }
.left-panel-slot { flex-direction: column; overflow: hidden; border: 1px solid rgba(85,214,230,.3); background: rgba(6,20,31,.96); box-shadow: 18px 0 42px rgba(0,0,0,.28), 0 0 24px rgba(85,214,230,.08); backdrop-filter: blur(16px); }
.left-panel-section { width: 100%; min-height: 0; overflow: hidden; flex-grow: 0; flex-shrink: 1; }
.left-panel-section > :deep(section) { border: 0; box-shadow: none; }
.left-panel-resizer { position: relative; z-index: 2; flex: 0 0 9px; cursor: row-resize; background: rgba(4,16,25,.94); border-top: 1px solid rgba(143,190,210,.18); border-bottom: 1px solid rgba(143,190,210,.18); touch-action: none; }
.left-panel-resizer::after { position: absolute; top: 3px; left: 50%; width: 36px; height: 2px; background: rgba(85,214,230,.5); content: ''; transform: translateX(-50%); box-shadow: 0 3px 0 rgba(85,214,230,.2); transition: .2s ease; }
.left-panel-resizer:hover,.left-panel-resizer:active { background: rgba(85,214,230,.13); }.left-panel-resizer:hover::after,.left-panel-resizer:active::after { background: var(--ui-cyan); box-shadow: 0 3px 0 rgba(85,214,230,.5), 0 0 8px rgba(85,214,230,.55); }
:global(body.is-resizing-panels) { cursor: row-resize; user-select: none; }
.monomer-info-panel { top: 18px; right: 12px; width: 300px; max-height: calc(100% - 36px); border: 1px solid rgba(85,214,230,.28); border-radius: 6px; background: rgba(8,24,36,.94); box-shadow: 0 12px 30px rgba(0,0,0,.3), 0 0 22px rgba(85,214,230,.08); }.monomer-panel-header { padding: 13px 15px; background: linear-gradient(90deg, rgba(21,125,145,.85), rgba(11,46,61,.9)); }.monomer-panel-title { color: #ddf7f8; font-size: 13px; }.monomer-btn { width: 27px; height: 27px; border-color: rgba(220,247,248,.26); border-radius: 4px; background: rgba(255,255,255,.08); color: #c7e9ed; }.monomer-btn:hover { background: rgba(85,214,230,.2); border-color: rgba(85,214,230,.5); }.monomer-panel-body { padding: 15px; }.monomer-panel-body::-webkit-scrollbar-thumb { background: rgba(143,190,210,.34); }.monomer-card { gap: 12px; padding: 12px; margin-bottom: 15px; border: 1px solid rgba(85,214,230,.18); border-radius: 4px; background: rgba(85,214,230,.06); }.monomer-icon-wrap { width: 42px; height: 42px; border-radius: 4px; background: rgba(85,214,230,.16); color: var(--ui-cyan); box-shadow: none; }.monomer-name { color: var(--ui-text); font-size: 14px; }.monomer-file { color: #72909c; }.monomer-props-title { margin-bottom: 9px; padding-bottom: 7px; border-bottom: 1px solid var(--ui-cyan); color: var(--ui-cyan); }.monomer-props-table { font-size: 12px; }.monomer-props-table tr.row-even,.monomer-props-table tr:hover { background: rgba(143,190,210,.06); }.prop-key { color: var(--ui-muted); }.prop-sep::after { background: rgba(143,190,210,.28); }.prop-value { color: #d3e3e8; }.monomer-no-info { color: #73909d; }
.disease-records-panel { right: 12px; left: auto; bottom: 18px; width: 270px; max-height: 50vh; border: 1px solid rgba(242,179,91,.28); border-radius: 6px; background: rgba(8,24,36,.95); box-shadow: 0 12px 30px rgba(0,0,0,.3); }.disease-records-header { padding: 11px 14px; background: linear-gradient(90deg, rgba(125,82,32,.9), rgba(66,48,28,.9)); }.disease-records-title { color: #f8d59d; font-size: 12px; }.disease-records-toggle { width: 24px; height: 24px; background: rgba(255,255,255,.1); color: #f8d59d; }.disease-records-body { padding: 8px; }.disease-records-empty { padding: 28px 12px; color: #78929d; font-size: 12px; text-align: center; }.disease-record-item { padding: 10px 11px; border-radius: 4px; }.disease-record-item:hover,.disease-record-item.is-active { background: rgba(242,179,91,.1); border-color: rgba(242,179,91,.42); }.disease-record-type { color: #d7e5e9; font-size: 12px; }.disease-record-delete { color: #78929d; }.disease-record-delete:hover { background: var(--ui-danger); }.disease-record-bottom { color: #6f8d99; }.disease-record-recorder { color: #89a3ad; }.severity-轻度 { background: rgba(85,214,230,.12); color: var(--ui-cyan); }.severity-中度 { background: rgba(242,179,91,.14); color: #f2c67f; }.severity-重度 { background: rgba(239,118,122,.14); color: #f39a9d; }
.disease-dialog-overlay { background: rgba(2,10,16,.72); backdrop-filter: blur(8px); }.disease-dialog { width: 460px; border: 1px solid rgba(85,214,230,.28); border-radius: 6px; background: #0b1a27; box-shadow: 0 20px 60px rgba(0,0,0,.42); }.disease-dialog-header { padding: 16px 20px; background: linear-gradient(90deg, rgba(21,125,145,.9), rgba(11,46,61,.95)); }.disease-dialog-header h3 { color: #ddf7f8; font-size: 16px; }.disease-dialog-close { width: 30px; height: 30px; border-radius: 4px; background: rgba(255,255,255,.1); }.disease-dialog-body { padding: 20px; }.disease-form-item { margin-bottom: 16px; }.disease-form-item label { margin-bottom: 6px; color: var(--ui-muted); font-size: 12px; }.disease-input,.disease-select,.disease-textarea { padding: 9px 10px; border: 1px solid rgba(143,190,210,.24); border-radius: 3px; color: #d7e7eb; background: rgba(4,16,25,.8); font-size: 13px; }.disease-input:focus,.disease-select:focus,.disease-textarea:focus { border-color: var(--ui-cyan); box-shadow: 0 0 0 2px rgba(85,214,230,.12); background: rgba(4,16,25,.95); }.disease-input[readonly] { color: #8ea9b4; background: rgba(143,190,210,.07); }.severity-radio { color: #bcd0d7; font-size: 13px; }.severity-radio input[type=radio] { accent-color: var(--ui-cyan); }.disease-dialog-footer { padding: 14px 20px; border-top-color: var(--ui-border); background: rgba(4,16,25,.45); }.disease-btn { padding: 8px 20px; border-radius: 3px; font-size: 13px; }.disease-btn-cancel { color: #b6cbd2; border-color: rgba(143,190,210,.25); background: rgba(143,190,210,.08); }.disease-btn-submit { color: #06202a; background: var(--ui-cyan); }.disease-btn-submit:hover { background: #8be8f0; }
@media (max-width: 900px) { .left-panel-slot,.backend-panel-slot { width: 296px; }.monomer-info-panel { width: 260px; } }
@media (max-width: 680px) { .viewer-toolbar { top: 12px; max-width: calc(100% - 110px); }.toolbar-button { min-width: 0; padding: 0 8px; }.toolbar-button span { display: none; }.viewer-actions { top: 12px; right: 10px; }.background-color-popover { top: 60px; max-width: calc(100% - 24px); }.left-panel-slot,.backend-panel-slot { top: 54px; right: 10px; bottom: 10px; left: 10px; width: auto; }.monomer-info-panel { top: 58px; right: 8px; width: 230px; }.disease-records-panel { right: 8px; left: auto; bottom: 12px; width: 230px; } }
</style>
