<template>
  <section class="pointcloud-panel dataset-panel" aria-label="点云加载">
    <header class="panel-header">
      <div>
        <span class="panel-eyebrow">POINT CLOUD LOADING</span>
        <strong>点云加载</strong>
      </div>
      <button class="panel-icon-button" type="button" :disabled="loadingList" title="刷新点云数据集" @click="$emit('refresh')">
        <RefreshRight />
      </button>
    </header>

    <div class="panel-content">
      <div class="dataset-summary">
        <span>可用点云</span>
        <strong>{{ datasets.length }}</strong>
      </div>

      <div v-if="datasets.length" class="dataset-list">
        <article v-for="item in datasets" :key="item.id" class="dataset-row" :class="{ 'is-active': String(selectedId) === String(item.id) }">
          <div class="dataset-meta">
            <span class="dataset-node" aria-hidden="true"></span>
            <div class="dataset-copy">
              <strong :title="item.name">{{ item.name }}</strong>
              <small>{{ formatPoints(item.pointCount) }} 点 · POTREE 2.0</small>
            </div>
          </div>
          <button
            v-if="String(selectedId) === String(item.id) && loaded"
            class="load-button unload-button"
            type="button"
            :disabled="loadingDataset"
            @click="$emit('unload')"
          >卸载</button>
          <button v-else class="load-button" type="button" :disabled="loadingDataset" @click="$emit('load', String(item.id))">
            {{ String(selectedId) === String(item.id) && loadingDataset ? '加载中' : '加载' }}
          </button>
        </article>
      </div>
      <div v-else class="empty-state">
        <span class="empty-mark">+</span>
        <p>暂无可加载点云</p>
        <small>请先在数据管理页面导入 Potree 2.0 数据</small>
      </div>

      <div class="dataset-hint">
        <i></i>
        <span>{{ loadingDataset ? '正在建立点云索引' : (loaded ? '当前点云已加载，可随时卸载并释放资源' : '点击“加载”后按视角流式请求数据') }}</span>
      </div>
    </div>

    <footer class="panel-status"><i></i><span>POINT CLOUD SERVICE ONLINE</span></footer>
  </section>
</template>

<script setup>
import { RefreshRight } from '@element-plus/icons-vue';

defineProps({
  datasets: { type: Array, default: () => [] },
  selectedId: { type: [String, Number], default: '' },
  loadingList: { type: Boolean, default: false },
  loadingDataset: { type: Boolean, default: false },
  loaded: { type: Boolean, default: false },
  hasDataset: { type: Boolean, default: false },
});

defineEmits(['refresh', 'load', 'unload']);

const formatPoints = value => Number(value || 0).toLocaleString('zh-CN');
</script>

<style scoped>
.pointcloud-panel { width: 100%; height: 100%; box-sizing: border-box; display: flex; flex-direction: column; overflow: hidden; color: var(--ui-text); border: 1px solid rgba(85,214,230,.3); background: rgba(6,20,31,.96); box-shadow: 18px 0 42px rgba(0,0,0,.28), 0 0 24px rgba(85,214,230,.08); backdrop-filter: blur(16px); }
.panel-header { display: flex; align-items: center; justify-content: space-between; min-height: 64px; padding: 0 14px 0 16px; border-bottom: 1px solid var(--ui-border); background: linear-gradient(90deg, rgba(85,214,230,.09), transparent 62%); }
.panel-eyebrow { display: block; margin-bottom: 4px; color: var(--ui-cyan); font: 9px Consolas, monospace; letter-spacing: 1.2px; }.panel-header strong { color: #e5f4f6; font-size: 14px; font-weight: 600; }
.panel-icon-button { display: grid; width: 29px; height: 29px; place-items: center; border: 1px solid rgba(85,214,230,.28); border-radius: 3px; color: var(--ui-cyan); background: rgba(85,214,230,.07); cursor: pointer; }.panel-icon-button:hover:not(:disabled) { border-color: var(--ui-cyan); background: rgba(85,214,230,.15); }.panel-icon-button:disabled { opacity: .45; cursor: wait; }.panel-icon-button :deep(svg) { width: 15px; height: 15px; }
.panel-content { display: flex; flex: 1; min-height: 0; flex-direction: column; padding: 15px; }.dataset-list { min-height: 0; flex: 1; overflow-y: auto; margin-top: 8px; }.dataset-list::-webkit-scrollbar { width: 4px; }.dataset-list::-webkit-scrollbar-thumb { background: rgba(143,190,210,.34); }.dataset-row { display: flex; align-items: center; justify-content: space-between; min-height: 55px; gap: 9px; padding: 0 7px; border: 1px solid transparent; border-radius: 4px; }.dataset-row:hover { border-color: rgba(85,214,230,.16); background: rgba(85,214,230,.05); }.dataset-row.is-active { border-color: rgba(86,214,154,.26); background: rgba(86,214,154,.07); }.dataset-meta { display: flex; min-width: 0; align-items: center; gap: 10px; }.dataset-node { width: 7px; height: 7px; flex: 0 0 auto; border: 1px solid var(--ui-cyan); background: rgba(85,214,230,.18); box-shadow: 0 0 7px rgba(85,214,230,.38); transform: rotate(45deg); }.dataset-row.is-active .dataset-node { border-color: var(--ui-success); background: var(--ui-success); box-shadow: 0 0 8px rgba(86,214,154,.7); }.dataset-copy { display: grid; min-width: 0; gap: 4px; }.dataset-copy strong { overflow: hidden; color: #c5dbe1; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }.dataset-copy small { color: #66828e; font: 9px Consolas, monospace; white-space: nowrap; }.load-button { min-width: 48px; min-height: 25px; padding: 0 8px; border: 1px solid rgba(85,214,230,.25); border-radius: 3px; color: #a8dbe0; background: rgba(143,190,210,.07); font-size: 10px; cursor: pointer; transition: .2s ease; }.load-button:hover:not(:disabled) { border-color: var(--ui-cyan); color: #06202a; background: var(--ui-cyan); }.load-button:disabled { opacity: .55; cursor: wait; }.dataset-row.is-active .load-button { border-color: rgba(86,214,154,.52); color: #8de4b9; background: rgba(86,214,154,.1); }.dataset-row.is-active .unload-button { border-color: rgba(239,118,122,.48); color: #f39a9d; background: rgba(239,118,122,.1); }.dataset-row.is-active .unload-button:hover:not(:disabled) { border-color: var(--ui-danger); color: #fff; background: var(--ui-danger); }.empty-state { display: flex; min-height: 148px; flex: 1; flex-direction: column; align-items: center; justify-content: center; padding: 15px; color: #7f9aa5; text-align: center; }.empty-mark { display: grid; width: 26px; height: 26px; place-items: center; margin-bottom: 9px; border: 1px dashed rgba(85,214,230,.35); color: var(--ui-cyan); font-size: 18px; }.empty-state p { margin: 0 0 4px; color: #b5cbd2; font-size: 12px; }.empty-state small { color: #66828e; font-size: 10px; }
.dataset-summary { display: flex; align-items: center; justify-content: space-between; margin-top: 18px; padding: 11px 0; border-top: 1px solid rgba(143,190,210,.13); border-bottom: 1px solid rgba(143,190,210,.13); color: #7894a0; font-size: 11px; }.dataset-summary strong { color: var(--ui-cyan); font: 600 15px Consolas, monospace; }
.dataset-hint { display: flex; align-items: center; gap: 8px; margin-top: 14px; color: #66828e; font: 10px Consolas, monospace; line-height: 1.5; }.dataset-hint i { width: 5px; height: 5px; flex: 0 0 auto; border-radius: 50%; background: var(--ui-success); box-shadow: 0 0 7px rgba(86,214,154,.8); }
.panel-status { display: flex; align-items: center; gap: 7px; min-height: 34px; padding: 0 15px; border-top: 1px solid rgba(143,190,210,.14); color: #66828e; font: 9px Consolas, monospace; letter-spacing: .8px; }.panel-status i { width: 5px; height: 5px; border-radius: 50%; background: var(--ui-success); box-shadow: 0 0 7px rgba(86,214,154,.8); }
</style>
