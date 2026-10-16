<template>
  <section class="backend-model-panel" aria-label="模型加载">
    <header class="panel-header">
      <div>
        <span class="panel-eyebrow">MODEL LOADING</span>
        <strong>模型加载</strong>
      </div>
      <button class="refresh-button" type="button" :disabled="loading" @click="$emit('refresh')">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20 11a8.1 8.1 0 0 0-15.4-2.6L3 10" /><path d="M3 4v6h6" /><path d="M4 13a8.1 8.1 0 0 0 15.4 2.6L21 14" /><path d="M21 20v-6h-6" /></svg>
        {{ loading ? '更新中' : '刷新' }}
      </button>
    </header>

    <div class="local-load-row">
      <button class="local-load-button" type="button" @click="$emit('localLoad')">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3v12" /><path d="m7 10 5 5 5-5" /><path d="M4 21h16" /></svg>
        本地上传模型目录
      </button>
      <span>离线加载本地八叉树文件</span>
    </div>

    <div class="model-summary">
      <span>可调度模型</span>
      <strong>{{ models.length }}</strong>
      <i></i>
      <span>八叉树节点</span>
      <strong>{{ octreeCount }}</strong>
    </div>

    <div v-if="models.length" class="model-list">
      <article v-for="model in models" :key="model.id" class="model-row" :class="{ 'is-active': matches(activeModelId, model.id) }">
        <span class="model-node" aria-hidden="true"></span>
        <span class="model-name" :title="model.name">{{ model.name }}</span>
        <button
          class="load-button"
          :class="{ 'is-unload': matches(activeModelId, model.id) }"
          type="button"
          :disabled="matches(loadingModelId, model.id)"
          @click="$emit(matches(activeModelId, model.id) ? 'unload' : 'load', model)"
        >{{ matches(loadingModelId, model.id) ? '加载中' : (matches(activeModelId, model.id) ? '卸载' : '加载') }}</button>
      </article>
    </div>
    <div v-else class="empty-state">
      <span class="empty-mark">+</span>
      <p>暂无可加载模型</p>
      <small>请先在数据管理页面导入模型文件</small>
    </div>
  <footer class="panel-status"><i></i><span>MODEL LOADING SERVICE ONLINE</span></footer>
  </section>
</template>

<script setup>
defineProps({
  models: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  loadingModelId: { type: [String, Number], default: null },
  activeModelId: { type: [String, Number], default: null },
  octreeCount: { type: Number, default: 0 },
})

defineEmits(['refresh', 'load', 'unload', 'localLoad'])
const matches = (left, right) => left != null && right != null && String(left) === String(right)
</script>

<style scoped>
.backend-model-panel { width: 100%; height: 100%; box-sizing: border-box; display: flex; flex-direction: column; border: 1px solid rgba(85, 214, 230, .3); border-radius: 0; overflow: hidden; color: var(--ui-text); background: rgba(6, 20, 31, .96); box-shadow: 18px 0 42px rgba(0, 0, 0, .28), 0 0 24px rgba(85, 214, 230, .08); backdrop-filter: blur(16px); }
.local-load-row { display: flex; align-items: center; gap: 9px; padding: 10px 14px; border-bottom: 1px solid rgba(143,190,210,.13); }.local-load-button { display: inline-flex; align-items: center; gap: 6px; min-height: 29px; padding: 0 10px; border: 1px solid rgba(86,214,154,.4); border-radius: 3px; color: #9ceac1; background: rgba(86,214,154,.09); font-size: 11px; cursor: pointer; transition: .2s ease; }.local-load-button:hover { border-color: var(--ui-success); background: rgba(86,214,154,.17); }.local-load-button svg { width: 14px; height: 14px; fill: none; stroke: currentColor; stroke-linecap: round; stroke-linejoin: round; stroke-width: 1.8; }.local-load-row > span { color: #66828e; font-size: 10px; }
.panel-header { display: flex; align-items: center; justify-content: space-between; min-height: 64px; padding: 0 14px 0 16px; border-bottom: 1px solid var(--ui-border); background: linear-gradient(90deg, rgba(85, 214, 230, .09), transparent 62%); }
.panel-eyebrow { display: block; margin-bottom: 4px; color: var(--ui-cyan); font: 9px Consolas, monospace; letter-spacing: 1.3px; }.panel-header strong { color: #e5f4f6; font-size: 14px; font-weight: 600; }
.refresh-button { display: inline-flex; align-items: center; gap: 5px; min-height: 28px; padding: 0 8px; border: 1px solid rgba(85, 214, 230, .28); border-radius: 3px; color: var(--ui-cyan); background: rgba(85, 214, 230, .07); font-size: 11px; cursor: pointer; transition: .2s ease; }.refresh-button:hover:not(:disabled) { border-color: var(--ui-cyan); background: rgba(85, 214, 230, .14); }.refresh-button:disabled { opacity: .5; cursor: wait; }.refresh-button svg { width: 13px; height: 13px; fill: none; stroke: currentColor; stroke-linecap: round; stroke-linejoin: round; stroke-width: 1.8; }
.model-summary { display: flex; align-items: center; gap: 6px; min-height: 36px; padding: 0 16px; border-bottom: 1px solid rgba(143, 190, 210, .13); color: #7894a0; font-size: 10px; }.model-summary strong { color: #c7e9ed; font: 600 12px Consolas, monospace; }.model-summary i { width: 1px; height: 12px; margin: 0 4px; background: rgba(143, 190, 210, .24); }
.model-list { min-height: 0; flex: 1; overflow-y: auto; padding: 7px; }.model-list::-webkit-scrollbar { width: 4px; }.model-list::-webkit-scrollbar-thumb { background: rgba(143, 190, 210, .34); }
.model-row { display: flex; align-items: center; min-height: 45px; gap: 9px; padding: 0 7px; border: 1px solid transparent; border-radius: 4px; }.model-row:hover { border-color: rgba(85, 214, 230, .16); background: rgba(85, 214, 230, .05); }.model-row.is-active { border-color: rgba(86, 214, 154, .26); background: rgba(86, 214, 154, .07); }
.model-node { width: 7px; height: 7px; flex: 0 0 auto; border: 1px solid var(--ui-cyan); background: rgba(85, 214, 230, .18); box-shadow: 0 0 7px rgba(85, 214, 230, .38); transform: rotate(45deg); }.model-row.is-active .model-node { border-color: var(--ui-success); background: var(--ui-success); box-shadow: 0 0 8px rgba(86, 214, 154, .7); }
.model-name { min-width: 0; flex: 1; overflow: hidden; color: #c5dbe1; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }.load-button { min-width: 48px; min-height: 25px; padding: 0 8px; border: 1px solid rgba(85, 214, 230, .25); border-radius: 3px; color: #a8dbe0; background: rgba(143, 190, 210, .07); font-size: 10px; cursor: pointer; transition: .2s ease; }.load-button:hover:not(:disabled) { border-color: var(--ui-cyan); color: #06202a; background: var(--ui-cyan); }.load-button:disabled { opacity: .55; cursor: wait; }.model-row.is-active .load-button { border-color: rgba(86, 214, 154, .52); color: #8de4b9; background: rgba(86, 214, 154, .1); }
.empty-state { display: flex; min-height: 148px; flex: 1; flex-direction: column; align-items: center; justify-content: center; padding: 15px; color: #7f9aa5; text-align: center; }.empty-mark { display: grid; width: 26px; height: 26px; place-items: center; margin-bottom: 9px; border: 1px dashed rgba(85, 214, 230, .35); color: var(--ui-cyan); font-size: 18px; }.empty-state p { margin: 0 0 4px; color: #b5cbd2; font-size: 12px; }.empty-state small { color: #66828e; font-size: 10px; }.panel-status { display: flex; align-items: center; gap: 7px; min-height: 34px; padding: 0 15px; border-top: 1px solid rgba(143,190,210,.14); color: #66828e; font: 9px Consolas, monospace; letter-spacing: .85px; }.panel-status i { width: 5px; height: 5px; border-radius: 50%; background: var(--ui-success); box-shadow: 0 0 7px rgba(86,214,154,.8); }
@media (max-width: 680px) { .backend-model-panel { width: 100%; } }
.model-row.is-active .load-button.is-unload { border-color: rgba(240, 132, 132, .45); color: #f0a3a3; background: rgba(240, 132, 132, .08); }
.model-row.is-active .load-button.is-unload:hover { border-color: #f08484; background: rgba(240, 132, 132, .18); }
</style>
