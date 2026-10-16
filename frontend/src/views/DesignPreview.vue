<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { RouterLink, useRoute, useRouter } from 'vue-router';
import { Document, Picture, RefreshRight, Search, Upload } from '@element-plus/icons-vue';
import DesignImageViewer from '@/components/DesignImageViewer.vue';
import { designImageUrl, formatImageSize, listDesignImages } from '@/api/designImages.js';

const route = useRoute();
const router = useRouter();
const images = ref([]);
const selectedId = ref(null);
const search = ref('');
const loading = ref(false);
const error = ref('');
let controller;
const filtered = computed(() => images.value.filter(image => `${image.name} ${image.originalName}`.toLowerCase().includes(search.value.trim().toLowerCase())));
const selected = computed(() => images.value.find(image => String(image.id) === String(selectedId.value)) || null);
const dateLabel = computed(() => selected.value?.createTime?.slice(0, 10).replaceAll('-', '.') || '—');
function select(image) { selectedId.value = image.id; router.replace({ query: { ...route.query, image: image.id } }); }
async function refresh() {
  controller?.abort();
  const request = new AbortController();
  controller = request;
  loading.value = true;
  error.value = '';
  try {
    images.value = await listDesignImages(request.signal);
    const id = route.query.image || selectedId.value;
    selectedId.value = images.value.find(image => String(image.id) === String(id))?.id || images.value[0]?.id || null;
  } catch (failure) { if (failure.name !== 'AbortError') error.value = failure.message; }
  finally { if (controller === request) loading.value = false; }
}
watch(() => route.query.image, id => { if (images.value.some(image => String(image.id) === String(id))) selectedId.value = id; });
onMounted(refresh);
onBeforeUnmount(() => controller?.abort());
</script>

<template>
  <main class="design-page">
    <header class="page-heading"><div><p class="page-kicker">DRAWING ARCHIVE / VISUAL WORKSPACE</p><h1>设计图预览 <span>图像档案</span></h1><p class="page-description">从整体到细节，浏览与探索每一张建筑图纸。</p></div><RouterLink class="manage-link" :to="{ path: '/data-import', query: { category: 'DESIGN' } }"><Upload />图像入库<span>↗</span></RouterLink></header>
    <div class="design-workspace">
      <aside class="drawing-catalog" aria-label="图纸目录">
        <header><div><Document /><strong>图纸目录</strong><span>{{ images.length.toString().padStart(2, '0') }}</span></div><button type="button" :disabled="loading" aria-label="刷新图纸目录" title="刷新目录" @click="refresh"><RefreshRight /></button></header>
        <div class="catalog-search"><Search /><input v-model="search" placeholder="搜索图纸名称" aria-label="搜索图纸名称"></div>
        <div v-if="error" class="catalog-message" role="alert"><strong>图像库暂不可用</strong><span>{{ error }}</span><el-button size="small" @click="refresh">重新连接</el-button></div>
        <div v-else-if="loading && !images.length" class="catalog-message">正在读取图像档案…</div>
        <div v-else-if="!filtered.length" class="catalog-message"><Picture /><strong>{{ images.length ? '没有匹配的图纸' : '尚未入库图像' }}</strong><span>{{ images.length ? '试试其他关键词。' : '前往图像入库，导入 yq1–yq4 示例或上传图片。' }}</span><RouterLink v-if="!images.length" :to="{ path: '/data-import', query: { category: 'DESIGN' } }">导入第一张图纸 →</RouterLink></div>
        <div v-else class="catalog-list"><button v-for="(image, index) in filtered" :key="image.id" type="button" class="drawing-card" :class="{ selected: image.id === selected?.id }" :aria-pressed="image.id === selected?.id" :aria-label="`查看${image.name}`" @click="select(image)"><div class="drawing-thumbnail"><img :src="designImageUrl(image.thumbnailUrl)" :alt="image.name" loading="lazy"><span>{{ String(index + 1).padStart(2, '0') }}</span><i v-if="image.id === selected?.id">正在预览</i></div><div class="drawing-card-copy"><strong>{{ image.name }}</strong><span>{{ image.format }}</span></div><small>{{ image.width }} × {{ image.height }} <b>·</b> {{ formatImageSize(image.fileSize) }}</small></button></div>
        <footer><i></i><span>IMAGE PREVIEW</span><small>原色显示</small></footer>
      </aside>
      <section class="drawing-main"><DesignImageViewer :image="selected" /><div class="drawing-metadata"><div><span>文件名称</span><strong :title="selected?.originalName">{{ selected?.originalName || '—' }}</strong></div><div><span>入库日期</span><strong>{{ dateLabel }}</strong></div><div><span>预览类型</span><strong>{{ selected ? `${selected.format} / 栅格图像` : '—' }}</strong></div><span class="phase-label">图像预览阶段 <i></i> CAD 转换待接入</span></div></section>
    </div>
  </main>
</template>

<style scoped>
.design-page { display: flex; flex-direction: column; height: 100%; min-height: 0; padding: 24px 28px 20px; color: var(--ui-text); background: radial-gradient(ellipse at top right, rgba(24,86,109,.09), transparent 55%), var(--ui-bg); }.page-heading { display: flex; align-items: center; justify-content: space-between; flex: none; gap: 18px; margin-bottom: 22px; }.page-kicker { margin: 0 0 10px; color: var(--ui-cyan); font: 9px Consolas, monospace; letter-spacing: 1.8px; }.page-heading h1 { display: flex; align-items: center; gap: 13px; margin: 0; font-size: 25px; font-weight: 600; letter-spacing: .4px; }.page-heading h1 span { padding: 4px 7px; border: 1px solid rgba(85,214,230,.25); border-radius: 3px; font-size: 10px; font-weight: 400; color: #84bfc9; background: rgba(85,214,230,.05); }.page-description { margin: 9px 0 0; color: #7c98a7; font-size: 12px; }.manage-link { display: flex; align-items: center; gap: 8px; padding: 10px 14px; border: 1px solid rgba(85,214,230,.4); border-radius: 5px; color: var(--ui-cyan); background: rgba(85,214,230,.06); text-decoration: none; font-size: 12px; white-space: nowrap; }.manage-link:hover { background: rgba(85,214,230,.14); }.manage-link svg { width: 15px; }.manage-link span { margin-left: 9px; }
.design-workspace { display: grid; grid-template-columns: 252px minmax(0,1fr); gap: 18px; min-height: 0; flex: 1; }.drawing-catalog { display: flex; flex-direction: column; min-height: 0; border: 1px solid var(--ui-border); border-radius: 8px; background: rgba(11,26,39,.92); overflow: hidden; }.drawing-catalog > header { display: flex; align-items: center; justify-content: space-between; padding: 16px; border-bottom: 1px solid var(--ui-border); }.drawing-catalog header > div { display: flex; align-items: center; gap: 9px; }.drawing-catalog header svg { width: 15px; color: #83b5c6; }.drawing-catalog header strong { font-size: 12px; font-weight: 550; }.drawing-catalog header span { color: var(--ui-cyan); background: rgba(85,214,230,.09); padding: 3px 5px; font: 10px Consolas, monospace; border-radius: 3px; }.drawing-catalog header button { display: grid; place-items: center; padding: 3px; border: none; background: transparent; cursor: pointer; }.drawing-catalog header button:disabled { opacity: .4; }
.catalog-search { display: flex; align-items: center; gap: 8px; margin: 14px 14px 8px; padding: 9px 10px; border: 1px solid var(--ui-border); border-radius: 4px; background: #081520; }.catalog-search:focus-within { border-color: rgba(85,214,230,.6); }.catalog-search svg { width: 14px; color: #638799; flex: none; }.catalog-search input { min-width: 0; width: 100%; outline: none; border: 0; background: transparent; color: var(--ui-text); font-size: 11px; }.catalog-search input::placeholder { color: #5f7e8e; }.catalog-list { padding: 8px 13px 14px; overflow-y: auto; min-height: 0; flex: 1; }.drawing-card { display: block; width: 100%; padding: 8px; margin-bottom: 10px; border: 1px solid var(--ui-border); border-radius: 5px; color: var(--ui-text); background: rgba(7,19,31,.5); cursor: pointer; text-align: left; transition: border-color .2s, background .2s; }.drawing-card:hover { border-color: rgba(85,214,230,.45); }.drawing-card.selected { border-color: rgba(85,214,230,.75); background: rgba(85,214,230,.06); box-shadow: 0 0 16px rgba(85,214,230,.04); }.drawing-thumbnail { position: relative; height: 93px; background: #040a0f; border-radius: 3px; overflow: hidden; }.drawing-thumbnail img { display: block; width: 100%; height: 100%; object-fit: contain; }.drawing-thumbnail > span { position: absolute; top: 6px; left: 6px; padding: 2px 4px; color: #bbd2dc; background: rgba(6,20,31,.8); font: 9px Consolas, monospace; }.drawing-thumbnail i { position: absolute; right: 5px; bottom: 5px; font-size: 9px; font-style: normal; padding: 3px 5px; background: rgba(7,39,45,.9); color: var(--ui-cyan); border: 1px solid rgba(85,214,230,.3); border-radius: 2px; }.drawing-card-copy { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin: 10px 0 6px; }.drawing-card-copy strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 12px; font-weight: 550; }.drawing-card-copy > span { font: 9px Consolas, monospace; color: #6d919f; }.drawing-card > small { color: #6a8a99; font: 9px Consolas, monospace; }.drawing-card small b { font-weight: 400; margin: 0 5px; }
.drawing-catalog > footer { display: flex; align-items: center; gap: 7px; padding: 13px 15px; border-top: 1px solid var(--ui-border); font: 8px Consolas, monospace; color: #648795; letter-spacing: .7px; }.drawing-catalog > footer i { width: 4px; height: 4px; border-radius: 50%; background: var(--ui-cyan); }.drawing-catalog > footer small { margin-left: auto; font: 9px sans-serif; }.catalog-message { display: flex; flex: 1; flex-direction: column; align-items: center; justify-content: center; gap: 12px; padding: 24px 18px; font-size: 12px; text-align: center; color: var(--ui-muted); line-height: 1.7; }.catalog-message svg { width: 32px; color: #486a7b; }.catalog-message strong { font-weight: 500; color: #bad0da; }.catalog-message span { font-size: 11px; }.catalog-message a { color: var(--ui-cyan); text-decoration: none; }
.drawing-main { min-height: 0; min-width: 0; display: flex; flex-direction: column; gap: 12px; }.drawing-main > .drawing-viewer { flex: 1; }.drawing-metadata { display: flex; align-items: center; gap: 35px; padding: 15px 19px; border: 1px solid var(--ui-border); border-radius: 6px; background: rgba(11,26,39,.65); flex: none; }.drawing-metadata > div { min-width: 0; }.drawing-metadata div span { display: block; margin-bottom: 7px; color: #6c8d9b; font-size: 10px; }.drawing-metadata strong { display: block; max-width: 230px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font: 11px Consolas, 'Microsoft YaHei', sans-serif; color: #aac4d0; }.phase-label { display: flex; align-items: center; gap: 8px; margin-left: auto; color: #587887; font-size: 9px; }.phase-label i { width: 3px; height: 3px; border-radius: 50%; background: #557c8b; }
@media(max-width:1250px) { .design-page { padding: 20px; }.design-workspace { grid-template-columns: 220px minmax(0,1fr); gap: 13px; }.phase-label { display: none; }.drawing-metadata { gap: 22px; } }
@media(max-width:900px) { .design-page { padding: 16px; }.page-heading { margin-bottom: 16px; }.design-workspace { grid-template-columns: 180px minmax(0,1fr); gap: 10px; }.drawing-thumbnail { height: 75px; }.drawing-metadata { flex-wrap: wrap; gap: 15px; padding: 12px; }.drawing-metadata strong { max-width: 150px; }.page-heading h1 { font-size: 21px; }.page-kicker { font-size: 8px; letter-spacing: 1px; } }
@media(max-width:650px) { .design-page { padding: 12px; overflow: auto; }.page-heading { align-items: flex-start; }.page-kicker,.page-description,.page-heading h1 span { display: none; }.page-heading h1 { font-size: 19px; }.manage-link { padding: 7px 8px; font-size: 11px; }.manage-link span { display: none; }.design-workspace { display: flex; flex-direction: column; flex: 1 0 auto; }.drawing-catalog { flex: none; }.drawing-catalog > header { padding: 9px 12px; }.catalog-search { margin: 8px 12px 0; padding: 6px; }.catalog-list { display: flex; padding: 9px; gap: 8px; overflow-x: auto; }.drawing-card { flex: 0 0 133px; margin: 0; }.drawing-thumbnail { height: 55px; }.drawing-card > small,.drawing-catalog > footer { display: none; }.drawing-card-copy { margin-bottom: 0; }.drawing-main { height: 470px; flex: 1 0 470px; }.drawing-metadata { display: none; } }
</style>
