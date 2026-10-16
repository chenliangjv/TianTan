<template>
  <div class="app-shell">
    <aside class="app-sidebar" :class="{ 'is-collapsed': sidebarCollapsed }">
      <div class="brand-block">
        <div class="brand-mark"><span></span><span></span><span></span></div>
        <div v-if="!sidebarCollapsed" class="brand-copy">
          <strong>天坛数字孪生</strong>
          <span>HERITAGE DIGITAL TWIN</span>
        </div>
      </div>

      <nav class="app-nav" aria-label="主导航">
        <div v-if="!sidebarCollapsed" class="nav-caption">工作台</div>
        <RouterLink v-for="item in navItems" :key="item.path" :to="item.path" class="nav-item">
          <el-icon><component :is="item.icon" /></el-icon>
          <span v-if="!sidebarCollapsed">{{ item.label }}</span>
          <span v-if="sidebarCollapsed" class="nav-tooltip">{{ item.label }}</span>
        </RouterLink>
      </nav>

      <div class="sidebar-footer">
        <div class="system-state"><i></i><span v-if="!sidebarCollapsed">系统在线</span></div>
        <span v-if="!sidebarCollapsed" class="version">V1.0.0 · LOCAL</span>
      </div>
    </aside>

    <section class="app-main">
      <header class="app-header">
        <div class="header-left">
          <button class="collapse-button" type="button" :aria-label="sidebarCollapsed ? '展开导航' : '收起导航'" @click="sidebarCollapsed = !sidebarCollapsed">
            <el-icon><Expand v-if="sidebarCollapsed" /><Fold v-else /></el-icon>
          </button>
          <div class="breadcrumb">
            <span>数字资产平台</span><el-icon><ArrowRight /></el-icon><strong>{{ currentPage.label }}</strong>
          </div>
        </div>
        <div class="header-right">
          <div class="header-status"><i></i><span>服务正常</span></div>
          <span class="header-date">{{ currentDate }}</span>
        </div>
      </header>
      <main class="app-content"><RouterView /></main>
    </section>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ArrowRight, Box, DataAnalysis, Expand, Fold, Histogram, Picture, Upload } from '@element-plus/icons-vue'

const route = useRoute()
const sidebarCollapsed = ref(false)
const navItems = [
  { path: '/', label: '首页', icon: DataAnalysis },
  { path: '/lod2', label: '模型展示', icon: Box },
  { path: '/pointcloud', label: '点云展示', icon: Picture },
  { path: '/gaussian', label: '高斯渲染', icon: Box },
  { path: '/design', label: '设计图预览', icon: Picture },
  { path: '/data-import', label: '数据管理', icon: Upload },
  { path: '/data-stats', label: '信息统计', icon: Histogram },
]
const currentPage = computed(() => navItems.find(item => item.path === route.path) || navItems[0])
const currentDate = new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date()).replaceAll('/', '.')
</script>

<style scoped>
.app-shell { display: flex; width: 100%; height: 100%; min-width: 0; background: var(--ui-bg); color: var(--ui-text); }
.app-sidebar { position: relative; z-index: 5; display: flex; width: 236px; flex: 0 0 236px; flex-direction: column; border-right: 1px solid var(--ui-border); background: var(--ui-surface); transition: width .25s ease, flex-basis .25s ease; }
.app-sidebar.is-collapsed { width: 68px; flex-basis: 68px; }
.brand-block { display: flex; align-items: center; min-height: 76px; padding: 0 22px; border-bottom: 1px solid var(--ui-border); gap: 12px; white-space: nowrap; overflow: hidden; }
.brand-mark { display: flex; align-items: flex-end; width: 25px; height: 26px; gap: 3px; flex: 0 0 auto; }
.brand-mark span { display: block; width: 6px; border: 1px solid var(--ui-cyan); background: rgba(85,214,230,.2); transform: skewY(-25deg); }
.brand-mark span:nth-child(1) { height: 13px; }.brand-mark span:nth-child(2) { height: 21px; background: rgba(85,214,230,.45); }.brand-mark span:nth-child(3) { height: 26px; background: var(--ui-cyan); }
.brand-copy { display: flex; flex-direction: column; gap: 3px; }.brand-copy strong { color: var(--ui-text); font-size: 15px; font-weight: 650; letter-spacing: 1px; }.brand-copy span { color: var(--ui-muted); font-size: 8px; letter-spacing: .9px; }
.app-nav { flex: 1; padding: 22px 12px; overflow-y: auto; }.nav-caption { padding: 0 12px 10px; color: #5f7d8b; font-size: 10px; letter-spacing: 2px; text-transform: uppercase; }
.nav-item { position: relative; display: flex; align-items: center; gap: 12px; min-height: 44px; margin: 3px 0; padding: 0 12px; border: 1px solid transparent; border-radius: 5px; color: #8da7b2; font-size: 13px; text-decoration: none; transition: .2s ease; }.nav-item:hover { color: var(--ui-text); background: rgba(85,214,230,.06); border-color: rgba(85,214,230,.13); }.nav-item.router-link-active { color: var(--ui-cyan); background: linear-gradient(90deg, rgba(85,214,230,.13), rgba(85,214,230,.03)); border-color: rgba(85,214,230,.2); }.nav-item.router-link-active::before { content: ''; position: absolute; left: -13px; width: 2px; height: 22px; background: var(--ui-cyan); box-shadow: 0 0 10px var(--ui-cyan); }.nav-item .el-icon { font-size: 17px; flex: 0 0 auto; }.app-sidebar.is-collapsed .nav-item { justify-content: center; padding: 0; }.app-sidebar.is-collapsed .nav-item.router-link-active::before { left: -12px; }.nav-tooltip { position: absolute; left: 54px; z-index: 20; display: none; padding: 6px 9px; border: 1px solid var(--ui-border); background: var(--ui-surface-2); color: var(--ui-text); font-size: 12px; white-space: nowrap; }.nav-item:hover .nav-tooltip { display: block; }
.sidebar-footer { padding: 16px 22px 20px; border-top: 1px solid var(--ui-border); }.system-state { display: flex; align-items: center; gap: 8px; color: #8da7b2; font-size: 11px; }.system-state i,.header-status i { width: 6px; height: 6px; border-radius: 50%; background: #56d69a; box-shadow: 0 0 8px #56d69a; }.version { display: block; margin-top: 11px; color: #52707d; font-size: 9px; letter-spacing: 1px; }.app-sidebar.is-collapsed .sidebar-footer { padding: 16px 0; }.app-sidebar.is-collapsed .system-state { justify-content: center; }
.app-main { display: flex; min-width: 0; min-height: 0; flex: 1; flex-direction: column; }.app-header { display: flex; align-items: center; justify-content: space-between; min-height: 58px; padding: 0 26px; border-bottom: 1px solid var(--ui-border); background: rgba(9,25,38,.86); }.header-left,.header-right,.breadcrumb,.header-status { display: flex; align-items: center; }.header-left { gap: 16px; }.collapse-button { display: grid; width: 30px; height: 30px; place-items: center; padding: 0; border: 1px solid var(--ui-border); border-radius: 4px; background: transparent; color: var(--ui-muted); cursor: pointer; }.collapse-button:hover { border-color: var(--ui-cyan); color: var(--ui-cyan); }.breadcrumb { gap: 9px; color: #66818e; font-size: 12px; }.breadcrumb .el-icon { color: #42606d; }.breadcrumb strong { color: var(--ui-text); font-weight: 500; }.header-right { gap: 20px; }.header-status { gap: 7px; color: #78b494; font-size: 11px; }.header-date { color: #5f7d8b; font: 11px 'JetBrains Mono', Consolas, monospace; letter-spacing: 1px; }.app-content { position: relative; min-width: 0; min-height: 0; flex: 1; overflow: hidden; }
@media (max-width: 720px) { .app-sidebar { width: 68px; flex-basis: 68px; }.brand-block { justify-content: center; padding: 0; }.brand-copy,.nav-caption,.nav-item > span:not(.nav-tooltip),.sidebar-footer .version,.sidebar-footer .system-state span { display: none; }.nav-item { justify-content: center; padding: 0; }.nav-item.router-link-active::before { left: -12px; }.sidebar-footer { padding: 16px 0; }.system-state { justify-content: center; }.app-header { padding: 0 14px; }.header-status,.header-date { display: none; } }
</style>
