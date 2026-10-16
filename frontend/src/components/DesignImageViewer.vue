<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { FullScreen, Picture, RefreshRight, ZoomIn, ZoomOut } from '@element-plus/icons-vue';
import { designImageUrl, formatImageSize } from '@/api/designImages.js';
import { fitImage, zoomImage } from '@/utils/imageViewport.js';

const props = defineProps({ image: { type: Object, default: null } });
const root = ref(null);
const stage = ref(null);
const loaded = ref(false);
const failed = ref(false);
const reloadKey = ref(0);
const fullscreen = ref(false);
const dragging = ref(false);
const view = ref({ x: 0, y: 0, scale: 1 });
const fitted = ref(true);
let observer;
let drag;
let size = { width: 0, height: 0 };
const imageStyle = computed(() => ({ width: `${props.image?.width || 1}px`, height: `${props.image?.height || 1}px`, transform: `translate(${view.value.x}px, ${view.value.y}px) scale(${view.value.scale})`, visibility: loaded.value ? 'visible' : 'hidden' }));
const percentage = computed(() => `${Math.round(view.value.scale * 100)}%`);

function fit() {
  if (!props.image || !stage.value) return;
  view.value = fitImage(props.image, { width: stage.value.clientWidth, height: stage.value.clientHeight });
  fitted.value = true;
}
function zoomTo(scale, anchor) {
  if (!loaded.value || !stage.value || !props.image) return;
  const viewport = { width: stage.value.clientWidth, height: stage.value.clientHeight };
  const min = fitImage(props.image, viewport).scale / 4;
  view.value = zoomImage(view.value, Math.max(min, Math.min(8, scale)), anchor || { x: viewport.width / 2, y: viewport.height / 2 });
  fitted.value = false;
}
function wheel(event) {
  const bounds = stage.value.getBoundingClientRect();
  const delta = event.deltaY * (event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? bounds.height : 1);
  zoomTo(view.value.scale * Math.exp(-Math.max(-240, Math.min(240, delta)) * 0.002), { x: event.clientX - bounds.left, y: event.clientY - bounds.top });
}
function startDrag(event) {
  if (!loaded.value || event.button !== 0 || drag) return;
  event.preventDefault();
  stage.value.focus({ preventScroll: true });
  stage.value.setPointerCapture(event.pointerId);
  drag = { id: event.pointerId, x: event.clientX, y: event.clientY, start: { ...view.value } };
  dragging.value = true;
}
function moveDrag(event) {
  if (!drag || drag.id !== event.pointerId) return;
  view.value = { ...view.value, x: drag.start.x + event.clientX - drag.x, y: drag.start.y + event.clientY - drag.y };
  fitted.value = false;
}
function stopDrag() {
  const id = drag?.id;
  drag = null;
  dragging.value = false;
  if (id !== undefined && stage.value?.hasPointerCapture(id)) stage.value.releasePointerCapture(id);
}
function keydown(event) {
  if (event.key === '+' || event.key === '=') { event.preventDefault(); zoomTo(view.value.scale * 1.25); }
  else if (event.key === '-') { event.preventDefault(); zoomTo(view.value.scale / 1.25); }
  else if (event.key === '0') { event.preventDefault(); fit(); }
}
async function toggleFullscreen() {
  try {
    if (document.fullscreenElement === root.value) await document.exitFullscreen();
    else await root.value.requestFullscreen();
  } catch { ElMessage.warning('当前浏览器无法进入全屏'); }
}
function syncFullscreen() { fullscreen.value = document.fullscreenElement === root.value; }
function imageLoaded() { loaded.value = true; failed.value = false; fit(); }
function retry() { failed.value = false; loaded.value = false; reloadKey.value++; }

watch(() => props.image?.id, async () => { stopDrag(); loaded.value = false; failed.value = false; fitted.value = true; await nextTick(); fit(); });
onMounted(() => {
  document.addEventListener('fullscreenchange', syncFullscreen);
  observer = new ResizeObserver(() => {
    const next = { width: stage.value.clientWidth, height: stage.value.clientHeight };
    if (fitted.value) fit();
    else view.value = { ...view.value, x: view.value.x + (next.width - size.width) / 2, y: view.value.y + (next.height - size.height) / 2 };
    size = next;
  });
  observer.observe(stage.value);
});
onBeforeUnmount(() => { stopDrag(); observer?.disconnect(); document.removeEventListener('fullscreenchange', syncFullscreen); });
</script>

<template>
  <section ref="root" class="drawing-viewer" aria-label="设计图查看器">
    <header class="viewer-heading"><div class="current-drawing"><span class="drawing-symbol"><Picture /></span><div><small>{{ image ? `DRAWING / ${String(image.id).padStart(4, '0')}` : 'DRAWING VIEWPORT' }}</small><h2 :title="image?.name">{{ image?.name || '选择一张设计图' }}</h2></div></div><div class="viewer-tools"><button type="button" :disabled="!loaded" title="适应窗口（0）" aria-label="适应窗口" @click="fit"><RefreshRight /></button><button type="button" :disabled="!loaded" title="原始尺寸" aria-label="原始尺寸" @click="zoomTo(1)">1:1</button><i></i><button type="button" :aria-label="fullscreen ? '退出全屏' : '全屏查看'" :title="fullscreen ? '退出全屏' : '全屏查看'" @click="toggleFullscreen"><FullScreen /></button></div></header>
    <div ref="stage" class="drawing-stage" :class="{ dragging, ready: loaded }" tabindex="0" role="region" aria-label="图纸画布，滚轮缩放，按住拖拽，按 0 适应窗口" @wheel.prevent="wheel" @pointerdown="startDrag" @pointermove="moveDrag" @pointerup="stopDrag" @pointercancel="stopDrag" @lostpointercapture="stopDrag" @dblclick="fit" @keydown="keydown">
      <span class="stage-corner top-left"></span><span class="stage-corner bottom-right"></span>
      <img v-if="image" :key="`${image.id}-${reloadKey}`" class="drawing-image" :src="designImageUrl(image.imageUrl)" :alt="image.name" :style="imageStyle" draggable="false" @load="imageLoaded" @error="failed = true; loaded = false">
      <div v-if="!image || !loaded" class="stage-message"><Picture v-if="!image" /><strong>{{ !image ? '在左侧选择图纸，开始浏览' : failed ? '图像加载失败' : '正在加载图纸…' }}</strong><span>{{ failed ? '文件可能已删除或网络暂时不可用。' : '保留图像原色，让每一处细节清晰可见。' }}</span><el-button v-if="failed" :icon="RefreshRight" @pointerdown.stop @click="retry">重新加载</el-button></div>
      <div v-if="loaded" class="canvas-label"><i></i>{{ image.format }} 图像预览</div>
    </div>
    <footer class="viewer-footer"><span class="interaction-hint">滚轮缩放 <b>·</b> 按住拖拽 <b>·</b> 双击复位</span><div class="zoom-controls"><button type="button" :disabled="!loaded" aria-label="缩小图纸" @click="zoomTo(view.scale / 1.25)"><ZoomOut /></button><span aria-live="polite">{{ loaded ? percentage : '—' }}</span><button type="button" :disabled="!loaded" aria-label="放大图纸" @click="zoomTo(view.scale * 1.25)"><ZoomIn /></button></div><span class="image-dimensions">{{ image ? `${image.width} × ${image.height} px · ${formatImageSize(image.fileSize)}` : 'IMAGE ARCHIVE' }}</span></footer>
  </section>
</template>

<style scoped>
.drawing-viewer { display: flex; flex-direction: column; height: 100%; min-width: 0; min-height: 0; overflow: hidden; border: 1px solid var(--ui-border); border-radius: 8px; background: #0b1a27; }.drawing-viewer:fullscreen { border: none; border-radius: 0; width: 100%; height: 100%; }
.viewer-heading { flex: none; display: flex; align-items: center; justify-content: space-between; gap: 16px; height: 72px; padding: 0 20px; border-bottom: 1px solid var(--ui-border); }.current-drawing { display: flex; align-items: center; gap: 12px; min-width: 0; }.current-drawing > div { min-width: 0; }.drawing-symbol { display: grid; place-items: center; width: 34px; height: 36px; border: 1px solid rgba(85,214,230,.25); border-radius: 5px; color: var(--ui-cyan); background: rgba(85,214,230,.06); flex: none; }.drawing-symbol svg { width: 18px; }.current-drawing small { font: 9px Consolas, monospace; letter-spacing: 1.2px; color: #6e929f; }.current-drawing h2 { max-width: 480px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 14px; font-weight: 550; margin: 5px 0 0; }.viewer-tools { display: flex; align-items: center; gap: 6px; }.viewer-tools i { height: 18px; width: 1px; background: var(--ui-border); margin: 0 6px; }
.viewer-tools button,.zoom-controls button { display: grid; place-items: center; width: 31px; height: 31px; padding: 6px; border: 1px solid transparent; border-radius: 4px; color: #a4bdc8; background: transparent; cursor: pointer; font: 11px Consolas, monospace; }.viewer-tools button { border-color: var(--ui-border); }.viewer-tools button:hover:not(:disabled),.zoom-controls button:hover:not(:disabled) { border-color: var(--ui-cyan); color: var(--ui-cyan); background: rgba(85,214,230,.08); }.viewer-tools button:disabled,.zoom-controls button:disabled { opacity: .35; cursor: default; }button svg { width: 17px; height: 17px; }
.drawing-stage { position: relative; flex: 1; min-height: 0; overflow: hidden; touch-action: none; outline-offset: -3px; background-color: #050e16; background-image: radial-gradient(rgba(132,173,193,.16) .7px, transparent .7px); background-size: 20px 20px; }.drawing-stage.ready { cursor: grab; }.drawing-stage.dragging { cursor: grabbing; }.drawing-image { position: absolute; left: 0; top: 0; max-width: none; transform-origin: 0 0; pointer-events: none; user-select: none; will-change: transform; box-shadow: 0 12px 55px rgba(0,0,0,.5); }.stage-corner { position: absolute; width: 18px; height: 18px; z-index: 1; opacity: .4; pointer-events: none; }.top-left { top: 16px; left: 16px; border-top: 1px solid var(--ui-cyan); border-left: 1px solid var(--ui-cyan); }.bottom-right { right: 16px; bottom: 16px; border-right: 1px solid var(--ui-cyan); border-bottom: 1px solid var(--ui-cyan); }.canvas-label { position: absolute; top: 14px; right: 15px; display: flex; align-items: center; gap: 6px; color: #86a6b4; background: rgba(7,19,31,.8); border: 1px solid var(--ui-border); border-radius: 3px; padding: 5px 8px; font: 9px Consolas, monospace; pointer-events: none; }.canvas-label i { width: 4px; height: 4px; background: var(--ui-success); border-radius: 50%; }.stage-message { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; flex-direction: column; gap: 12px; padding: 24px; text-align: center; }.stage-message svg { width: 38px; color: #426273; margin-bottom: 5px; }.stage-message strong { font-size: 14px; font-weight: 500; color: #a7c0cb; }.stage-message span { font-size: 11px; color: #63818e; }
.viewer-footer { display: flex; align-items: center; justify-content: space-between; gap: 10px; flex: none; min-height: 48px; padding: 6px 16px; border-top: 1px solid var(--ui-border); }.interaction-hint { color: #708d9b; font-size: 10px; }.interaction-hint b { color: #3e5b6a; margin: 0 6px; }.zoom-controls { display: flex; align-items: center; gap: 3px; border: 1px solid var(--ui-border); border-radius: 4px; background: rgba(4,15,24,.5); }.zoom-controls span { min-width: 48px; color: #d0e2e9; text-align: center; font: 11px Consolas, monospace; }.image-dimensions { color: #6e8b99; font: 10px Consolas, monospace; }
@media(max-width:1200px) { .interaction-hint { display: none; } }
@media(max-width:600px) { .viewer-heading { padding: 0 12px; height: 62px; }.drawing-symbol { display: none; }.current-drawing h2 { max-width: 170px; }.viewer-tools { gap: 4px; }.viewer-tools i { margin: 0 2px; }.image-dimensions { font-size: 9px; }.viewer-footer { padding: 6px 10px; } }
</style>
