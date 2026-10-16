<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue';
import { RouterLink } from 'vue-router';
import { Delete, Picture, RefreshRight, UploadFilled, View } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { deleteDesignImage, designImageUrl, formatImageSize, listDesignImages, uploadDesignImages } from '@/api/designImages.js';

const images = ref([]);
const files = ref([]);
const input = ref(null);
const busy = ref(false);
const loading = ref(false);
const error = ref('');
const progress = ref(0);
const results = ref([]);
const deleting = ref(null);
const samples = ['yq1.png', 'yq2.png', 'yq3.png', 'yq4.png'];
const sampleUrl = name => `${import.meta.env.BASE_URL}${name}`;
let listController;
let uploadController;
let disposed = false;

async function refresh() {
  listController?.abort();
  const controller = new AbortController();
  listController = controller;
  loading.value = true;
  error.value = '';
  try { images.value = await listDesignImages(controller.signal); }
  catch (failure) { if (failure.name !== 'AbortError' && !disposed) error.value = failure.message; }
  finally { if (listController === controller) loading.value = false; }
}

function selectFiles(event) {
  const selected = Array.from(event.target.files || []);
  event.target.value = '';
  if (selected.length > 20) return ElMessage.warning('每批最多选择 20 张图片');
  if (selected.some(file => !/\.(png|jpe?g)$/i.test(file.name) || file.size > 20 * 1024 * 1024 || !file.size)) {
    return ElMessage.warning('请选择非空的 PNG / JPG 图片，单张最大 20 MB');
  }
  files.value = selected;
  results.value = [];
}

async function ingest(useSamples = false) {
  if (busy.value || (!useSamples && !files.value.length)) return;
  busy.value = true;
  progress.value = 0;
  results.value = [];
  uploadController = new AbortController();
  try {
    let batch = files.value;
    if (useSamples) {
      batch = await Promise.all(samples.map(async name => {
        const response = await fetch(sampleUrl(name), { signal: uploadController.signal });
        if (!response.ok) throw new Error(`示例图片 ${name} 不存在`);
        return new File([await response.blob()], name, { type: 'image/png' });
      }));
    }
    results.value = await uploadDesignImages(batch, value => { progress.value = value; }, uploadController.signal);
    if (disposed) return;
    const failed = results.value.filter(item => item.status === 'FAILED').length;
    const imported = results.value.filter(item => item.status === 'IMPORTED').length;
    const existing = results.value.filter(item => item.status === 'EXISTS').length;
    const message = `新增 ${imported} 张，已存在 ${existing} 张${failed ? `，失败 ${failed} 张` : ''}`;
    if (failed) ElMessage.warning(message); else ElMessage.success(message);
    if (!useSamples) files.value = files.value.filter(file => results.value.some(result => result.originalName === file.name && result.status === 'FAILED'));
    await refresh();
  } catch (failure) { if (failure.name !== 'AbortError' && !disposed) ElMessage.error(failure.message); }
  finally { busy.value = false; }
}

async function remove(image) {
  try {
    await ElMessageBox.confirm(`删除“${image.name}”将移除入库记录及后端图像文件。public 中的示例原件不受影响。`, '删除设计图', {
      confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning', confirmButtonClass: 'el-button--danger',
    });
  } catch { return; }
  deleting.value = image.id;
  try { await deleteDesignImage(image.id); if (!disposed) { ElMessage.success('图像已删除'); await refresh(); } }
  catch (failure) { if (!disposed) ElMessage.error(failure.message); }
  finally { deleting.value = null; }
}

onMounted(refresh);
onBeforeUnmount(() => { disposed = true; listController?.abort(); uploadController?.abort(); });
</script>

<template>
  <section class="image-manager" aria-label="设计图图像管理">
    <section class="image-upload">
      <header class="surface-title"><div><span>IMAGE INGESTION</span><h2>让图纸进入数字档案</h2></div><el-icon><Picture /></el-icon></header>
      <p class="surface-description">保存原图、生成缩略图，入库后即可在设计图工作台中浏览。</p>
      <input ref="input" type="file" accept=".png,.jpg,.jpeg,image/png,image/jpeg" multiple hidden @change="selectFiles">
      <button class="image-drop" type="button" :disabled="busy" @click="input?.click()">
        <span class="upload-symbol"><UploadFilled /></span><strong>{{ files.length ? `已选择 ${files.length} 张图像` : '选择需要归档的设计图' }}</strong><span>PNG / JPG · 支持多选 · 单张最大 20 MB / 4000 万像素</span>
      </button>
      <div v-if="files.length" class="selected-files"><span v-for="(file, index) in files" :key="index">{{ file.name }} <small>{{ formatImageSize(file.size) }}</small></span></div>
      <div class="ingest-actions"><el-button :disabled="busy || !files.length" @click="files = []">清空选择</el-button><el-button type="primary" :icon="UploadFilled" :disabled="!files.length" :loading="busy" @click="ingest()">上传入库</el-button></div>
      <el-progress v-if="busy" :percentage="progress" :format="value => value === 100 ? '正在入库' : `${value}%`" />
      <section class="sample-section"><header><div><strong>从示例图纸开始</strong><p>现有 yq1–yq4 · 重复导入自动跳过</p></div><el-button size="small" :disabled="busy" @click="ingest(true)">导入 4 张示例图</el-button></header><div class="sample-grid"><figure v-for="name in samples" :key="name"><img :src="sampleUrl(name)" :alt="name" loading="lazy"><figcaption>{{ name }}</figcaption></figure></div></section>
      <div v-if="results.length" class="ingest-results" aria-live="polite"><div v-for="(item, index) in results" :key="index" :class="{ failed: item.status === 'FAILED' }"><span>{{ item.originalName }}</span><small>{{ item.message }}</small></div></div>
      <p class="phase-note"><i></i>当前为图像预览阶段，暂不解析或转换 DWG。</p>
    </section>
    <section class="image-library" aria-label="已入库设计图">
      <header class="surface-title"><div><span>DRAWING ARCHIVE</span><h2>已入库设计图 <em>{{ images.length }}</em></h2></div><el-button circle :icon="RefreshRight" :loading="loading" aria-label="刷新设计图列表" @click="refresh" /></header>
      <div v-if="error" class="library-empty" role="alert"><strong>暂时无法读取图像库</strong><span>{{ error }}</span><el-button @click="refresh">重试</el-button></div>
      <div v-else-if="!images.length" class="library-empty"><el-icon><Picture /></el-icon><strong>{{ loading ? '正在读取图像库…' : '图像档案，等待第一张图纸' }}</strong><span>上传图片，或从左侧导入 4 张示例图。</span></div>
      <div v-else class="library-list"><article v-for="item in images" :key="item.id" class="library-image"><img :src="designImageUrl(item.thumbnailUrl)" :alt="item.name" loading="lazy"><div><strong :title="item.name">{{ item.name }}</strong><small>{{ item.format }} · {{ item.width }} × {{ item.height }}</small><small>{{ formatImageSize(item.fileSize) }} <span class="ready-dot">已归档</span></small></div><div class="row-actions"><RouterLink :to="{ path: '/design', query: { image: item.id } }" :aria-label="`预览${item.name}`" title="预览图像"><View /></RouterLink><el-button circle text type="danger" :icon="Delete" :loading="deleting === item.id" :disabled="deleting !== null || busy" :aria-label="`删除${item.name}`" @click="remove(item)" /></div></article></div>
      <footer><RouterLink to="/design">前往设计图预览 <span>↗</span></RouterLink><span>原图存储 · 内容去重</span></footer>
    </section>
  </section>
</template>

<style scoped>
.image-manager { display: grid; grid-template-columns: minmax(360px, 1.05fr) minmax(330px, 1fr); gap: 18px; flex: 1 0 auto; }
.image-upload,.image-library { border: 1px solid var(--ui-border); border-radius: 8px; background: linear-gradient(145deg, rgba(16,35,52,.85), rgba(9,24,36,.96)); }
.image-upload { padding: 22px; }.image-library { display: flex; flex-direction: column; min-height: 350px; overflow: hidden; }.image-library > header { padding: 22px; border-bottom: 1px solid var(--ui-border); }
.surface-title { display: flex; align-items: center; justify-content: space-between; gap: 12px; }.surface-title span { font: 9px Consolas, monospace; color: var(--ui-cyan); letter-spacing: 1.7px; }.surface-title h2 { margin: 7px 0 0; font-size: 17px; font-weight: 550; }.surface-title > .el-icon { font-size: 26px; color: var(--ui-cyan); }.surface-title em { margin-left: 8px; font: 17px Consolas, monospace; color: var(--ui-cyan); }.surface-description { color: var(--ui-muted); font-size: 12px; line-height: 1.7; margin: 13px 0 20px; }
.image-drop { display: flex; width: 100%; min-height: 196px; align-items: center; justify-content: center; flex-direction: column; gap: 12px; padding: 20px; border: 1px dashed rgba(85,214,230,.4); border-radius: 6px; background: radial-gradient(ellipse at top, rgba(85,214,230,.08), transparent 75%); color: var(--ui-text); cursor: pointer; }.image-drop:hover { border-color: var(--ui-cyan); background-color: rgba(85,214,230,.04); }.image-drop:disabled { cursor: wait; opacity: .6; }.image-drop strong { font-size: 14px; font-weight: 550; }.image-drop > span:last-child { color: var(--ui-muted); font-size: 11px; line-height: 1.7; }.upload-symbol { display: grid; place-items: center; width: 46px; height: 46px; border: 1px solid rgba(85,214,230,.22); border-radius: 12px; background: rgba(85,214,230,.08); }.upload-symbol svg { width: 25px; color: var(--ui-cyan); }.ingest-actions { display: flex; justify-content: flex-end; gap: 8px; margin: 16px 0; }.ingest-actions .el-button { margin: 0; }.selected-files { display: grid; gap: 6px; max-height: 120px; overflow: auto; padding-top: 12px; }.selected-files > span { display: flex; justify-content: space-between; gap: 12px; font-size: 12px; overflow-wrap: anywhere; }.selected-files small { flex: none; color: var(--ui-muted); }
.sample-section { border-top: 1px solid var(--ui-border); padding-top: 20px; margin-top: 22px; }.sample-section header { display: flex; align-items: center; justify-content: space-between; gap: 10px; }.sample-section strong { font-size: 12px; font-weight: 500; }.sample-section p { margin: 6px 0 0; font-size: 10px; color: var(--ui-muted); }.sample-grid { display: grid; grid-template-columns: repeat(4,1fr); gap: 9px; margin-top: 15px; }.sample-grid figure { min-width: 0; margin: 0; border: 1px solid var(--ui-border); border-radius: 4px; overflow: hidden; background: #060b11; }.sample-grid img { width: 100%; height: 65px; display: block; object-fit: contain; }.sample-grid figcaption { padding: 6px 8px; font: 10px Consolas, monospace; color: var(--ui-muted); border-top: 1px solid var(--ui-border); }.phase-note { display: flex; align-items: center; gap: 7px; font-size: 11px; color: #7893a0; margin: 20px 0 0; }.phase-note i { width: 5px; height: 5px; background: var(--ui-amber); border-radius: 50%; flex: none; }
.library-list { padding: 12px; overflow-y: auto; flex: 1; }.library-image { display: flex; align-items: center; gap: 15px; padding: 14px 10px; border-bottom: 1px solid rgba(143,190,210,.1); }.library-image > img { width: 90px; height: 64px; object-fit: contain; background: #050b10; border: 1px solid var(--ui-border); border-radius: 4px; }.library-image > div:not(.row-actions) { flex: 1; min-width: 0; }.library-image strong { display: block; font-size: 13px; font-weight: 550; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.library-image small { display: block; color: var(--ui-muted); font: 10px Consolas, monospace; margin-top: 7px; }.ready-dot { margin-left: 12px; color: var(--ui-success); }.row-actions { display: flex; align-items: center; gap: 3px; }.row-actions a { display: grid; place-items: center; width: 30px; height: 30px; color: var(--ui-cyan); border-radius: 4px; }.row-actions a:hover { background: rgba(85,214,230,.1); }.row-actions svg { width: 16px; }.image-library footer { display: flex; justify-content: space-between; gap: 8px; padding: 17px 22px; border-top: 1px solid var(--ui-border); font-size: 11px; color: #6e8a99; }.image-library footer a { color: var(--ui-cyan); text-decoration: none; }.image-library footer a span { margin-left: 12px; }.library-empty { flex: 1; display: flex; min-height: 250px; align-items: center; justify-content: center; text-align: center; flex-direction: column; gap: 12px; padding: 30px; }.library-empty .el-icon { font-size: 35px; color: #456474; }.library-empty strong { font-size: 13px; font-weight: 500; }.library-empty span { color: var(--ui-muted); font-size: 12px; line-height: 1.8; }
.ingest-results { margin-top: 16px; max-height: 180px; overflow: auto; }.ingest-results > div { display: flex; justify-content: space-between; gap: 12px; padding: 8px 0; font-size: 11px; color: var(--ui-success); }.ingest-results span { overflow-wrap: anywhere; }.ingest-results small { text-align: right; }.ingest-results .failed { color: var(--ui-danger); }
@media(max-width:1100px) { .image-manager { grid-template-columns: 1fr; }.image-library { min-height: 320px; }.library-list { max-height: 500px; } }
@media(max-width:520px) { .image-upload { padding: 16px; }.sample-section header { align-items: flex-start; flex-direction: column; }.sample-grid { grid-template-columns: repeat(2,1fr); }.library-image { gap: 9px; }.library-image > img { width: 60px; }.image-library footer > span { display: none; } }
</style>
