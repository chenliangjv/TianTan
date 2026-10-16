<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Delete, FolderOpened, RefreshRight, UploadFilled } from '@element-plus/icons-vue';
import { useRoute } from 'vue-router';
import DesignImageManager from '@/components/DesignImageManager.vue';
import GaussianManager from '@/components/GaussianManager.vue';
import config from '@/config/index.js';

const route = useRoute();
const category = ref(['DESIGN', 'GAUSSIAN', 'POINT_CLOUD'].includes(route.query.category) ? route.query.category : 'MODEL');
const uploadMode = ref('ARCHIVE');
const selectedFiles = ref([]);
const selectedArchive = ref(null);
const folderInput = ref(null);
const archiveInput = ref(null);
const submitting = ref(false);
const uploadProgress = ref(0);
const task = ref(null);
const assets = ref([]);
const assetsLoading = ref(false);
const deletingAssetId = ref(null);
let taskTimer = null;
let assetRequestId = 0;

const fileCount = computed(() => selectedFiles.value.length);
const selectedArchiveName = computed(() => selectedArchive.value?.name || '');
const selectedFolderName = computed(() => {
  const first = selectedFiles.value[0];
  return first ? first.webkitRelativePath?.split('/')[0] || first.name : '';
});
const isPointCloud = computed(() => category.value === 'POINT_CLOUD');
const isDesign = computed(() => category.value === 'DESIGN');
const isGaussian = computed(() => category.value === 'GAUSSIAN');
const categoryLabel = computed(() => isPointCloud.value ? '点云数据' : '三维模型');
const categoryDescription = computed(() => {
  if (isGaussian.value) return '归档高斯场景与天空效果，以分层细节探索建筑实景。';
  if (isDesign.value) return '图像入库与归档，支持 PNG / JPG；当前使用图像预览，后续扩展 CAD 转换。';
  return isPointCloud.value
    ? '仅接收 Potree 2.0 数据集，服务端将保存并以 HTTP Range 方式提供流式访问。'
    : '选择已完成八叉树切分的数据目录或压缩包，系统将异步处理并归档。';
});
const archiveDropTitle = computed(() => selectedArchive.value
  ? selectedArchiveName.value
  : isPointCloud.value ? '选择 Potree 数据集 ZIP 压缩包' : '选择模型 ZIP 压缩包');
const archiveDropHint = computed(() => selectedArchive.value
  ? '上传后由服务器流式解压并入库'
  : isPointCloud.value
    ? '压缩包根目录或其子目录必须包含 metadata.json、hierarchy.bin、octree.bin'
    : '大文件直接上传，服务器端解压并处理');
const folderDropTitle = computed(() => selectedFiles.value.length
  ? selectedFolderName.value
  : isPointCloud.value ? '选择 Potree 数据集文件夹' : '选择模型数据文件夹');
const folderDropHint = computed(() => selectedFiles.value.length
  ? `已包含 ${fileCount.value} 个文件`
  : isPointCloud.value
    ? '文件夹必须包含 metadata.json、hierarchy.bin、octree.bin'
    : '保留目录层级后上传');
const taskNodeLabel = computed(() => isPointCloud.value ? '数据集索引' : '处理节点');
const assetApi = computed(() => isPointCloud.value ? '/api/data/point-clouds/list' : '/api/data/models/list');

const openFolderPicker = () => folderInput.value?.click();
const openArchivePicker = () => archiveInput.value?.click();

const onFolderSelected = event => {
  selectedFiles.value = Array.from(event.target.files || []);
  selectedArchive.value = null;
  task.value = null;
  if (selectedFiles.value.length) ElMessage.success(`已选择 ${selectedFiles.value.length} 个文件`);
};

const onArchiveSelected = event => {
  const file = event.target.files?.[0];
  selectedArchive.value = file?.name.toLowerCase().endsWith('.zip') ? file : null;
  selectedFiles.value = [];
  task.value = null;
  uploadProgress.value = 0;
  if (file && !selectedArchive.value) {
    ElMessage.warning('请选择 ZIP 压缩包');
    event.target.value = '';
  } else if (selectedArchive.value) ElMessage.success(`已选择压缩包：${file.name}`);
};

const clearSelection = () => {
  selectedFiles.value = [];
  selectedArchive.value = null;
  uploadProgress.value = 0;
  task.value = null;
  if (folderInput.value) folderInput.value.value = '';
  if (archiveInput.value) archiveInput.value.value = '';
};

const taskEndpoint = () => category.value === 'MODEL' ? '/api/data/models/upload-folder' : '/api/data/point-clouds/upload-folder';
const archiveEndpoint = () => category.value === 'MODEL' ? '/api/data/models/upload-archive' : '/api/data/point-clouds/upload-archive';

const uploadForm = (url, formData) => new Promise((resolve, reject) => {
  const xhr = new XMLHttpRequest();
  xhr.open('POST', `${config.API_BASE_URL}${url}`);
  xhr.upload.onprogress = event => {
    if (event.lengthComputable) uploadProgress.value = Math.round(event.loaded / event.total * 100);
  };
  xhr.onload = () => {
    const body = xhr.responseText || '';
    if (xhr.status >= 200 && xhr.status < 300) resolve(body);
    else reject(new Error(body || `上传失败: ${xhr.status}`));
  };
  xhr.onerror = () => reject(new Error('网络错误，上传失败'));
  xhr.onabort = () => reject(new Error('上传已取消'));
  xhr.send(formData);
});

const loadAssets = async (showSuccess = false) => {
  const requestId = ++assetRequestId;
  if (isDesign.value || isGaussian.value) {
    assets.value = [];
    assetsLoading.value = false;
    return;
  }
  assetsLoading.value = true;
  try {
    const response = await fetch(`${config.API_BASE_URL}${assetApi.value}`);
    if (!response.ok) throw new Error(`资产列表获取失败: ${response.status}`);
    const data = await response.json();
    if (requestId !== assetRequestId) return;
    assets.value = Array.isArray(data) ? data : [];
    if (showSuccess) ElMessage.success(`${categoryLabel.value}列表已刷新`);
  } catch (error) {
    if (requestId !== assetRequestId) return;
    console.error('[DataImport] 获取资产列表失败:', error);
    assets.value = [];
    ElMessage.error('无法加载已入库数据');
  } finally {
    if (requestId === assetRequestId) assetsLoading.value = false;
  }
};

const refreshTask = async () => {
  if (!task.value?.taskId) return;
  const taskId = task.value.taskId;
  try {
    const response = await fetch(`${config.API_BASE_URL}/api/data/tasks/${taskId}`);
    if (!response.ok) throw new Error(`任务查询失败: ${response.status}`);
    const updatedTask = await response.json();
    if (task.value?.taskId !== taskId) return;
    task.value = updatedTask;
    if (['SUCCESS', 'FAILED'].includes(task.value.status)) {
      window.clearInterval(taskTimer);
      taskTimer = null;
      if (task.value.status === 'SUCCESS') {
        ElMessage.success('数据已入库');
        await loadAssets();
      }
    }
  } catch (error) {
    if (task.value?.taskId !== taskId) return;
    console.error('[DataImport] 查询入库任务失败:', error);
    window.clearInterval(taskTimer);
    taskTimer = null;
  }
};

const startTaskPolling = () => {
  window.clearInterval(taskTimer);
  taskTimer = window.setInterval(refreshTask, 2000);
};

const submit = async () => {
  if (isDesign.value) return;
  if (uploadMode.value === 'ARCHIVE' && !selectedArchive.value) return ElMessage.warning('请先选择待入库的 ZIP 压缩包');
  if (uploadMode.value === 'FOLDER' && !selectedFiles.value.length) return ElMessage.warning('请先选择待入库的数据文件夹');
  submitting.value = true;
  uploadProgress.value = 0;
  try {
    const formData = new FormData();
    let endpoint;
    if (uploadMode.value === 'ARCHIVE') {
      formData.append('archive', selectedArchive.value, selectedArchive.value.name);
      formData.append('folderName', selectedArchiveName.value.replace(/\.zip$/i, ''));
      endpoint = archiveEndpoint();
    } else {
      selectedFiles.value.forEach(file => formData.append('files', file, file.webkitRelativePath || file.name));
      formData.append('folderName', selectedFolderName.value);
      endpoint = taskEndpoint();
    }
    task.value = JSON.parse(await uploadForm(endpoint, formData));
    ElMessage.success('文件已上传，正在处理并入库');
    startTaskPolling();
  } catch (error) {
    console.error('[DataImport] 入库失败:', error);
    ElMessage.error(error.message || '数据处理失败');
  } finally {
    submitting.value = false;
  }
};

const deleteApi = asset => isPointCloud.value ? `/api/data/point-clouds/${asset.id}` : `/api/data/models/${asset.id}`;

const deleteAsset = async asset => {
  if (isDesign.value) return;
  try {
    await ElMessageBox.confirm(`删除“${asset.name}”会同时移除其已上传文件，此操作不可恢复。`, `删除${categoryLabel.value}`, {
      confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning', confirmButtonClass: 'el-button--danger',
    });
  } catch {
    return;
  }
  deletingAssetId.value = asset.id;
  try {
    const response = await fetch(`${config.API_BASE_URL}${deleteApi(asset)}`, { method: 'DELETE' });
    if (!response.ok) throw new Error((await response.text()) || `删除失败: ${response.status}`);
    ElMessage.success(`已删除${categoryLabel.value}“${asset.name}”`);
    await loadAssets();
  } catch (error) {
    console.error('[DataImport] 删除资产失败:', error);
    ElMessage.error(error.message || '删除失败');
  } finally {
    deletingAssetId.value = null;
  }
};

const formatDate = value => {
  if (!value) return '时间未知';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '时间未知';
  return new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit' }).format(date).replaceAll('/', '.');
};
const formatSize = value => {
  if (!Number.isFinite(Number(value))) return '';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let size = Number(value);
  let index = 0;
  while (size >= 1024 && index < units.length - 1) { size /= 1024; index += 1; }
  return `${size >= 10 || index === 0 ? size.toFixed(0) : size.toFixed(1)} ${units[index]}`;
};
const assetMeta = asset => {
  if (isPointCloud.value) {
    const points = Number.isFinite(Number(asset.pointCount)) ? `${Number(asset.pointCount).toLocaleString('zh-CN')} 点` : '点数待解析';
    return [asset.format || 'POTREE 2.0', points, formatSize(asset.totalSize)].filter(Boolean).join(' · ');
  }
  return [asset.type || 'OCTREE', formatDate(asset.uploadTime || asset.createTime)].filter(Boolean).join(' · ');
};
const assetStatus = asset => asset.status || 'READY';
const assetStatusType = asset => ['READY', 'SUCCESS'].includes(assetStatus(asset)) ? 'success' : ['FAILED', 'ERROR'].includes(assetStatus(asset)) ? 'danger' : 'warning';
const assetStatusLabel = asset => ({ READY: '可用', SUCCESS: '完成', FAILED: '失败', ERROR: '异常', PROCESSING: '处理中', QUEUED: '排队中', PENDING: '待处理' }[assetStatus(asset)] || assetStatus(asset));

watch(category, () => {
  window.clearInterval(taskTimer);
  taskTimer = null;
  clearSelection();
  assets.value = [];
  loadAssets();
});

onMounted(loadAssets);
onBeforeUnmount(() => window.clearInterval(taskTimer));
</script>

<template>
  <main class="data-manager-page" :class="{ 'is-design': isDesign }">
    <section class="page-heading">
      <div><p class="section-kicker">ASSET GOVERNANCE / INGESTION</p><h1>数据管理</h1><p v-if="categoryDescription">{{ categoryDescription }}</p></div>
      <div class="heading-controls">
        <el-segmented v-model="category" :options="[{ label: '三维模型', value: 'MODEL' }, { label: '点云数据', value: 'POINT_CLOUD' }, { label: '设计图', value: 'DESIGN' }, { label: '高斯数据', value: 'GAUSSIAN' }]" :disabled="submitting" />
        <el-segmented v-if="!isDesign && !isGaussian" v-model="uploadMode" :options="[{ label: 'ZIP 压缩包', value: 'ARCHIVE' }, { label: '文件夹', value: 'FOLDER' }]" :disabled="submitting" />
      </div>
    </section>

    <DesignImageManager v-if="isDesign" />
    <GaussianManager v-else-if="isGaussian" />

    <section v-else class="manager-grid">
      <section class="upload-surface">
        <header class="surface-heading"><div><span>UPLOAD ASSET</span><h2>上传入库</h2></div><small>{{ isPointCloud ? 'POTREE 2.0' : 'OCTREE DATA' }}</small></header>
        <input ref="folderInput" type="file" webkitdirectory directory multiple hidden @change="onFolderSelected">
        <input ref="archiveInput" type="file" accept=".zip,application/zip" hidden @change="onArchiveSelected">
        <div v-if="uploadMode === 'ARCHIVE'" class="folder-dropzone" :class="{ selected: selectedArchive }" @click="openArchivePicker"><el-icon class="folder-icon"><UploadFilled /></el-icon><strong>{{ archiveDropTitle }}</strong><span>{{ archiveDropHint }}</span></div>
        <div v-else class="folder-dropzone" :class="{ selected: fileCount }" @click="openFolderPicker"><el-icon class="folder-icon"><FolderOpened /></el-icon><strong>{{ folderDropTitle }}</strong><span>{{ folderDropHint }}</span></div>
        <div class="upload-actions">
          <el-button v-if="uploadMode === 'ARCHIVE'" :icon="UploadFilled" @click="openArchivePicker">选择压缩包</el-button>
          <el-button v-else :icon="FolderOpened" @click="openFolderPicker">选择文件夹</el-button>
          <el-button :icon="RefreshRight" :disabled="(!fileCount && !selectedArchive) || submitting" @click="clearSelection">清空</el-button>
          <el-button type="primary" :icon="UploadFilled" :loading="submitting" :disabled="uploadMode === 'ARCHIVE' ? !selectedArchive : !fileCount" @click="submit">开始入库</el-button>
        </div>
        <el-progress v-if="submitting && uploadProgress < 100" class="upload-progress" :percentage="uploadProgress" :format="value => `上传 ${value}%`" />
      </section>

      <aside v-loading="assetsLoading" class="asset-surface">
        <header class="surface-heading"><div><span>ASSET LIBRARY</span><h2>已入库{{ categoryLabel }}</h2></div><div class="library-actions"><strong>{{ assets.length }}</strong><el-tooltip content="刷新资产列表" placement="bottom"><el-button circle :icon="RefreshRight" aria-label="刷新资产列表" @click="loadAssets(true)" /></el-tooltip></div></header>
        <div v-if="assets.length" class="asset-list">
          <article v-for="asset in assets" :key="asset.id" class="asset-row">
            <span class="asset-mark" :class="{ cloud: isPointCloud }"></span>
            <div class="asset-copy"><strong :title="asset.name">{{ asset.name }}</strong><small>{{ assetMeta(asset) }}</small></div>
            <el-tag size="small" :type="assetStatusType(asset)">{{ assetStatusLabel(asset) }}</el-tag>
            <el-tooltip content="删除数据" placement="left"><el-button circle text type="danger" :icon="Delete" :loading="deletingAssetId === asset.id" :disabled="Boolean(deletingAssetId)" :aria-label="`删除${asset.name}`" @click="deleteAsset(asset)" /></el-tooltip>
          </article>
        </div>
        <div v-else-if="!assetsLoading" class="asset-empty"><el-icon><FolderOpened /></el-icon><strong>暂无已入库{{ categoryLabel }}</strong><span>选择文件后即可在此管理和删除数据。</span></div>
      </aside>
    </section>

    <section v-if="!isDesign && !isGaussian && task" class="task-surface">
      <div class="task-title"><span>入库任务</span><el-tag :type="task.status === 'SUCCESS' ? 'success' : task.status === 'FAILED' ? 'danger' : 'warning'">{{ task.status }}</el-tag></div>
      <el-progress :percentage="task.totalFiles ? Math.min(100, Math.round((task.processedFiles || 0) / task.totalFiles * 100)) : 0" :status="task.status === 'SUCCESS' ? 'success' : task.status === 'FAILED' ? 'exception' : undefined" />
      <dl class="task-detail"><div><dt>模型名称</dt><dd>{{ task.modelName || '-' }}</dd></div><div><dt>处理文件</dt><dd>{{ task.processedFiles || 0 }} / {{ task.totalFiles || 0 }}</dd></div><div><dt>{{ taskNodeLabel }}</dt><dd>{{ task.processedNodes || 0 }} / {{ task.totalNodes || 0 }}</dd></div><div><dt>任务信息</dt><dd>{{ task.message || '-' }}</dd></div></dl>
    </section>
  </main>
</template>

<style scoped>
:deep(.el-segmented){--el-segmented-item-selected-color:#05232e}
.data-manager-page { display: flex; height: 100%; min-height: 0; flex-direction: column; overflow: auto; padding: 22px clamp(18px, 2.25vw, 54px); background: var(--ui-bg); color: var(--ui-text); }
.page-heading { display: flex; align-items: flex-end; justify-content: space-between; gap: 24px; margin-bottom: 16px; }.heading-controls { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 10px; }.section-kicker { margin: 0 0 9px !important; color: var(--ui-cyan) !important; font: 10px Consolas, monospace !important; letter-spacing: 1.5px; }.page-heading h1 { margin: 0; font-size: 25px; font-weight: 620; }.page-heading p:last-child { margin: 8px 0 0; color: var(--ui-muted); font-size: 13px; }
.manager-grid { display: grid; min-height: 438px; flex: 1; grid-template-columns: minmax(390px, 1.08fr) minmax(320px, .92fr); gap: 16px; }.upload-surface, .asset-surface, .task-surface { border: 1px solid var(--ui-border); border-radius: 6px; background: rgba(13,31,45,.82); }.upload-surface { display: flex; min-height: 0; flex-direction: column; padding: 20px; }.asset-surface { display: flex; min-height: 0; flex-direction: column; overflow: hidden; background: rgba(10,27,40,.92); }.surface-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; padding-bottom: 16px; border-bottom: 1px solid rgba(143,190,210,.14); }.upload-surface .surface-heading { margin-bottom: 16px; }.surface-heading span { display: block; color: #6f97a6; font: 9px Consolas, monospace; letter-spacing: 1.2px; }.surface-heading h2 { margin: 5px 0 0; color: #dceaf0; font-size: 15px; font-weight: 600; }.surface-heading > small { padding-top: 3px; color: #73909d; font: 10px Consolas, monospace; }.library-actions { display: flex; align-items: center; gap: 8px; }.library-actions strong { color: var(--ui-cyan); font: 600 21px Consolas, monospace; }.library-actions .el-button { width: 28px; height: 28px; }
.folder-dropzone { display: flex; min-height: 0; flex: 1; flex-direction: column; align-items: center; justify-content: center; gap: 11px; border: 1px dashed rgba(85,214,230,.34); background: rgba(5,20,30,.46); color: var(--ui-muted); cursor: pointer; transition: border-color .2s, background .2s; }.folder-dropzone:hover, .folder-dropzone.selected { border-color: var(--ui-cyan); background: rgba(85,214,230,.06); }.folder-dropzone strong { max-width: min(80%, 480px); overflow: hidden; color: var(--ui-text); font-size: 15px; font-weight: 550; text-overflow: ellipsis; white-space: nowrap; }.folder-dropzone span { max-width: 86%; color: #73909d; font-size: 12px; line-height: 1.55; text-align: center; }.folder-icon { font-size: 38px; color: var(--ui-cyan); }.upload-actions { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 9px; margin-top: 16px; }.upload-progress { margin-top: 14px; }
.asset-list { min-height: 0; flex: 1; padding: 8px; overflow-y: auto; }.asset-list::-webkit-scrollbar { width: 5px; }.asset-list::-webkit-scrollbar-thumb { background: rgba(143,190,210,.32); }.asset-row { display: grid; grid-template-columns: 8px minmax(0, 1fr) auto 30px; align-items: center; gap: 10px; min-height: 59px; padding: 8px 7px; border: 1px solid transparent; border-left: 2px solid transparent; transition: background .18s, border-color .18s; }.asset-row + .asset-row { margin-top: 3px; }.asset-row:hover { border-color: rgba(85,214,230,.18); border-left-color: var(--ui-cyan); background: rgba(85,214,230,.055); }.asset-mark { width: 7px; height: 7px; border: 1px solid var(--ui-cyan); background: rgba(85,214,230,.2); box-shadow: 0 0 8px rgba(85,214,230,.28); }.asset-mark.cloud { border-radius: 50%; border-color: #74aef4; background: rgba(116,174,244,.32); box-shadow: 0 0 8px rgba(116,174,244,.3); }.asset-copy { min-width: 0; }.asset-copy strong, .asset-copy small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.asset-copy strong { color: #d4e4e9; font-size: 12px; font-weight: 550; }.asset-copy small { margin-top: 5px; color: #73909d; font: 9px Consolas, monospace; }.asset-row .el-tag { max-width: 57px; padding: 0 5px; font-size: 10px; }.asset-row .el-button { width: 28px; height: 28px; }.asset-empty { display: flex; min-height: 210px; flex: 1; align-items: center; justify-content: center; flex-direction: column; gap: 9px; padding: 24px; color: #77939f; text-align: center; }.asset-empty .el-icon { color: #547d8e; font-size: 25px; }.asset-empty strong { color: #a9c0c8; font-size: 13px; font-weight: 550; }.asset-empty span { color: #6f8c98; font-size: 11px; }
.task-surface { margin-top: 16px; padding: 18px 20px; flex: 0 0 auto; }.task-title { display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px; color: var(--ui-text); font-weight: 600; }.task-detail { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 13px 30px; margin: 18px 0 0; }.task-detail div { display: flex; justify-content: space-between; gap: 16px; padding-bottom: 10px; border-bottom: 1px solid rgba(143,190,210,.12); }.task-detail dt { color: var(--ui-muted); }.task-detail dd { margin: 0; color: #d3e3e8; text-align: right; word-break: break-word; }
@media (max-width: 880px) { .data-manager-page { height: auto; }.page-heading { align-items: flex-start; flex-direction: column; }.heading-controls { justify-content: flex-start; }.manager-grid { min-height: 0; grid-template-columns: 1fr; }.upload-surface { min-height: 400px; }.asset-surface { min-height: 340px; }.task-detail { grid-template-columns: 1fr; } }
@media (max-width: 520px) { .data-manager-page { padding: 16px 12px; }.heading-controls { width: 100%; }.heading-controls :deep(.el-segmented) { max-width: 100%; overflow-x: auto; }.upload-actions { justify-content: flex-start; }.surface-heading > small { display: none; }.asset-row { grid-template-columns: 8px minmax(0, 1fr) 30px; }.asset-row .el-tag { display: none; } }
.data-manager-page.is-design { height: 100%; }
@media (max-width: 1100px) { .data-manager-page.is-design > .image-manager { flex: none; } }
</style>
