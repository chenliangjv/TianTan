<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { RouterLink } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Box, Delete, FolderOpened, RefreshRight, UploadFilled } from '@element-plus/icons-vue';
import { formatBytes, gaussianRequest, gaussianUrl, listGaussians } from '@/api/gaussians.js';

const mode = ref('FOLDER'), name = ref(''), files = ref([]), archive = ref(null);
const folderInput = ref(), zipInput = ref(), scenes = ref([]), task = ref(null);
const submitting = ref(false), progress = ref(0), loading = ref(false), error = ref(''), deleting = ref(null);
let xhr, timer, listController, disposed = false;
const busy = computed(() => submitting.value || ['PENDING', 'RUNNING'].includes(task.value?.status));
const bytes = computed(() => mode.value === 'ARCHIVE' ? archive.value?.size || 0 : files.value.reduce((sum, file) => sum + file.size, 0));
const mainFile = computed(() => files.value.find(file => /\.rad$/i.test(file.name)));
const skyFile = computed(() => files.value.find(file => /^environment\.ply$/i.test(file.name)));
const selection = computed(() => mode.value === 'ARCHIVE' ? archive.value?.name : files.value[0]?.webkitRelativePath.split('/')[0]);
const statusLabel = computed(() => ({ PENDING: '等待处理', RUNNING: '校验与归档', SUCCESS: '入库完成', FAILED: '入库失败' })[task.value?.status] || '');
function selected(event, zip) {
  if (busy.value) return;
  task.value = null; error.value = ''; progress.value = 0;
  if (zip) {
    const file = event.target.files?.[0];
    archive.value = file && /\.zip$/i.test(file.name) ? file : null;
    if (file && !archive.value) error.value = '请选择 ZIP 压缩包。';
    if (archive.value) name.value = archive.value.name.replace(/\.zip$/i, '');
  } else {
    files.value = Array.from(event.target.files || []);
    if (selection.value) name.value = selection.value;
  }
  event.target.value = '';
}
async function refresh() {
  listController?.abort(); listController = new AbortController(); const current = listController;
  loading.value = true;
  try { const values = await listGaussians(current.signal); if (!disposed) scenes.value = values; }
  catch (failure) { if (failure.name !== 'AbortError' && !disposed) error.value = failure.message; }
  finally { if (listController === current) loading.value = false; }
}
function poll() {
  clearTimeout(timer);
  const id = task.value?.taskId;
  if (!id || disposed) return;
  gaussianRequest(`/api/data/tasks/${encodeURIComponent(id)}`).then(async value => {
    if (disposed || task.value?.taskId !== id) return;
    task.value = value;
    if (['SUCCESS', 'FAILED'].includes(value.status)) {
      localStorage.removeItem('gaussian-ingestion-task');
      if (value.status === 'SUCCESS') { ElMessage.success('高斯场景已入库'); await refresh(); }
    } else timer = setTimeout(poll, 1500);
  }).catch(failure => { if (!disposed) { error.value = `任务状态暂不可用：${failure.message}`; timer = setTimeout(poll, 4000); } });
}
async function submit() {
  if (busy.value) return;
  error.value = '';
  if (!name.value.trim()) { error.value = '请填写场景名称。'; return; }
  if (mode.value === 'ARCHIVE' && !archive.value) { error.value = '请先选择 ZIP 压缩包。'; return; }
  if (mode.value === 'FOLDER' && files.value.filter(file => /\.rad$/i.test(file.name)).length !== 1) {
    error.value = '文件夹内必须有且仅有一个 RAD 主体。'; return;
  }
  submitting.value = true; progress.value = 0; task.value = null;
  const form = new FormData(); form.append('name', name.value.trim());
  if (mode.value === 'ARCHIVE') form.append('archive', archive.value);
  else files.value.forEach(file => form.append('files', file, file.webkitRelativePath || file.name));
  try {
    const result = await new Promise((resolve, reject) => {
      xhr = new XMLHttpRequest();
      xhr.open('POST', gaussianUrl(`/api/data/gaussians/upload-${mode.value === 'ARCHIVE' ? 'archive' : 'folder'}`));
      xhr.upload.onprogress = event => { if (event.lengthComputable) progress.value = Math.round(event.loaded / event.total * 100); };
      xhr.onload = () => {
        let body; try { body = JSON.parse(xhr.responseText); } catch { reject(new Error(`服务响应异常：${xhr.status}`)); return; }
        if (xhr.status >= 200 && xhr.status < 300) resolve(body);
        else reject(new Error(body.message || '上传失败'));
      };
      xhr.onerror = () => reject(new Error('上传连接失败，请检查网络后重试。'));
      xhr.onabort = () => reject(new Error('上传已取消'));
      xhr.send(form);
    });
    if (disposed) return;
    task.value = result; localStorage.setItem('gaussian-ingestion-task', result.taskId); poll();
  } catch (failure) { if (!disposed) error.value = failure.message; }
  finally { submitting.value = false; xhr = null; }
}
async function remove(scene) {
  try { await ElMessageBox.confirm(`删除“${scene.name}”及其归档主体和天空文件？原始成果不受影响。`, '删除高斯场景', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }); }
  catch { return; }
  deleting.value = scene.id;
  try { await gaussianRequest(`/api/data/gaussians/${scene.id}`, { method: 'DELETE' }); await refresh(); }
  catch (failure) { error.value = failure.message; }
  finally { deleting.value = null; }
}
onMounted(() => {
  refresh(); const id = localStorage.getItem('gaussian-ingestion-task');
  if (id) { task.value = { taskId: id, status: 'PENDING' }; poll(); }
});
onBeforeUnmount(() => { disposed = true; clearTimeout(timer); listController?.abort(); xhr?.abort(); });
</script>

<template>
  <section class="gaussian-manager">
    <div v-if="error" class="message error" role="alert">{{ error }}</div>
    <div class="workspace">
      <section class="upload-card">
        <header><div><small>GAUSSIAN ASSETS</small><h2>导入高斯场景</h2></div><el-segmented v-model="mode" :disabled="busy" :options="[{ label: '文件夹', value: 'FOLDER' }, { label: 'ZIP 压缩包', value: 'ARCHIVE' }]" /></header>
        <label class="name-label">场景名称<el-input v-model="name" maxlength="200" placeholder="例如：圜丘坛 · 高斯实景" :disabled="busy" /></label>
        <input ref="folderInput" type="file" webkitdirectory directory multiple hidden @change="selected($event, false)">
        <input ref="zipInput" type="file" accept=".zip,application/zip" hidden @change="selected($event, true)">
        <button class="dropzone" :class="{ selected: selection }" :disabled="busy" @click="mode === 'FOLDER' ? folderInput.click() : zipInput.click()">
          <FolderOpened v-if="mode === 'FOLDER'" /><UploadFilled v-else />
          <strong>{{ selection || (mode === 'FOLDER' ? '选择高斯成果文件夹' : '选择 ZIP 压缩包') }}</strong>
          <span>{{ selection ? `${formatBytes(bytes)} · 点击重新选择` : 'RAD 主体与 environment.ply 放在同一目录' }}</span>
          <small v-if="mode === 'ARCHIVE'">由服务器解压并识别场景，支持带外层目录的 ZIP</small>
        </button>
        <div class="file-summary"><div><i :class="{ ready: mainFile || archive }"></i><span>场景主体</span><strong>{{ mode === 'ARCHIVE' ? '解压后识别' : mainFile?.name || '等待选择 RAD' }}</strong></div><div><i :class="{ ready: skyFile }"></i><span>天空效果</span><strong>{{ mode === 'ARCHIVE' ? '自动匹配 environment.ply' : skyFile ? '已匹配 environment.ply' : '未选择，可选' }}</strong></div></div>
        <div class="submit-row"><span>支持已有 RAD 成果，无需重新转换</span><el-button type="primary" :icon="UploadFilled" :loading="submitting" :disabled="busy && !submitting" @click="submit">{{ submitting ? '正在上传' : '开始入库' }}</el-button></div>
        <el-progress v-if="submitting" :percentage="progress" :format="value => value === 100 ? '服务器接收中' : `${value}%`" />
        <div v-if="task" class="task" :class="{ failed: task.status === 'FAILED' }"><strong>{{ statusLabel }}</strong><span>{{ task.message || '正在读取任务状态…' }}</span><small v-if="task.totalFiles">{{ task.processedFiles || 0 }} / {{ task.totalFiles }} 个文件已校验</small></div>
      </section>
      <section class="library-card" v-loading="loading">
        <header><div><small>SCENE LIBRARY</small><h2>高斯场景库 <b>{{ scenes.length }}</b></h2></div><el-button :icon="RefreshRight" circle aria-label="刷新高斯场景库" @click="refresh" /></header>
        <div class="scene-list"><div v-if="!scenes.length" class="empty"><Box /><strong>建立你的高斯场景库</strong><span>入库完成后，可直接前往高斯渲染页面浏览。</span></div>
          <article v-for="scene in scenes" :key="scene.id" class="scene-card"><div class="scene-icon"><Box /></div><div class="scene-copy"><strong>{{ scene.name }}</strong><small>{{ formatBytes(scene.totalSize) }} · {{ scene.chunkCount }} 个数据块</small><span>{{ scene.hasSky ? '天空已关联' : '无天空文件' }}</span></div><div class="scene-actions"><RouterLink :to="{ path: '/gaussian', query: { scene: scene.id } }">查看 ↗</RouterLink><el-button :icon="Delete" text type="danger" :loading="deleting === scene.id" :disabled="Boolean(deleting)" :aria-label="`删除${scene.name}`" @click="remove(scene)" /></div></article>
        </div>
      </section>
    </div>
  </section>
</template>

<style scoped>
:deep(.el-segmented){--el-segmented-item-selected-color:#05232e}
.gaussian-manager{min-height:0;flex:1;display:flex;flex-direction:column;gap:12px}.workspace{display:grid;grid-template-columns:minmax(360px,1.05fr) minmax(300px,1fr);gap:18px;flex:1;min-height:490px}.upload-card,.library-card{border:1px solid var(--ui-border);border-radius:10px;background:var(--ui-surface);padding:22px;display:flex;flex-direction:column;min-height:0}header{display:flex;align-items:center;justify-content:space-between;gap:15px;margin-bottom:22px}header small{font:10px Consolas,monospace;letter-spacing:1.8px;color:var(--ui-cyan)}h2{font-size:17px;margin:7px 0 0;font-weight:550}h2 b{font:15px Consolas,monospace;color:var(--ui-cyan);margin-left:10px}.name-label{display:grid;gap:9px;font-size:12px;color:var(--ui-muted);margin-bottom:18px}.dropzone{display:flex;flex:1;min-height:175px;flex-direction:column;align-items:center;justify-content:center;gap:13px;padding:22px;border:1px dashed #315b6c;border-radius:8px;background:radial-gradient(ellipse at top,rgba(85,214,230,.08),transparent 70%),#081722;color:var(--ui-text);cursor:pointer}.dropzone:disabled{cursor:wait;opacity:.65}.dropzone:hover,.dropzone.selected{border-color:var(--ui-cyan)}.dropzone svg{width:38px;color:var(--ui-cyan)}.dropzone strong{max-width:100%;overflow-wrap:anywhere;font-weight:500}.dropzone span,.dropzone small{color:var(--ui-muted);font-size:12px;text-align:center}.file-summary{padding:16px 0}.file-summary>div{display:flex;align-items:center;gap:8px;font-size:11px;padding:7px 0}.file-summary span{color:var(--ui-muted);min-width:60px}.file-summary strong{font-weight:400;overflow-wrap:anywhere}.file-summary i{width:5px;height:5px;border-radius:50%;background:#557384}.file-summary i.ready{background:var(--ui-success)}.submit-row{display:flex;align-items:center;justify-content:space-between;gap:14px;margin:0 0 12px}.submit-row>span{font-size:11px;color:var(--ui-muted)}.scene-list{overflow:auto;flex:1;min-height:0}.scene-card{display:flex;gap:13px;align-items:center;padding:17px 10px;border-bottom:1px solid var(--ui-border)}.scene-icon{display:grid;place-items:center;width:45px;height:49px;border:1px solid #245062;border-radius:8px;background:linear-gradient(135deg,#153444,#0a1a29);color:var(--ui-cyan);flex:none}.scene-icon svg{width:23px}.scene-copy{display:flex;flex:1;min-width:0;flex-direction:column;gap:8px}.scene-copy strong{font-size:13px;overflow-wrap:anywhere}.scene-copy small{color:var(--ui-muted);font-size:11px}.scene-copy>span{font-size:10px;color:var(--ui-success)}.scene-actions{display:flex;align-items:center;gap:6px}.scene-actions a{font-size:11px;color:var(--ui-cyan);text-decoration:none;white-space:nowrap}.empty{height:100%;min-height:240px;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:14px;color:var(--ui-muted);text-align:center}.empty svg{width:40px;color:#355566}.empty strong{font-size:14px;color:#b4cad3}.empty span{font-size:12px;line-height:1.7}.message,.task{border:1px solid var(--ui-border);padding:13px 15px;border-radius:6px;font-size:12px}.error,.failed{color:var(--ui-danger);border-color:#734448}.task{display:flex;flex-direction:column;gap:8px;background:rgba(85,214,230,.04)}.task strong{color:var(--ui-cyan)}.task small{color:var(--ui-muted)}@media(max-width:1050px){.workspace{grid-template-columns:1fr}.library-card{min-height:260px}.upload-card{min-height:490px}}@media(max-width:650px){header{flex-wrap:wrap}.upload-card,.library-card{padding:15px}.submit-row>span{display:none}}
</style>
