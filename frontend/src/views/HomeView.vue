<template>
  <main class="home-page">
    <section class="home-inner">
      <header class="hero-section">
        <div class="hero-copy">
          <p class="eyebrow"><span></span> CULTURAL HERITAGE DIGITAL ASSET PLATFORM</p>
          <h1>天坛三维可视化系统</h1>
          <p class="hero-subtitle">汇聚三维模型、点云、高斯实景与设计图，连接建筑遗产的数字成果与保护信息。</p>
        </div>
        <div class="hero-status">
          <span class="status-label">运行状态</span>
          <strong><i></i>系统在线</strong>
          <small>LOCAL SERVICE / READY</small>
        </div>
      </header>

      <section class="workspace-grid" aria-label="功能入口">
        <RouterLink v-for="item in workspaceItems" :key="item.path" :to="item.path" class="workspace-card">
          <div class="card-index">{{ item.index }}</div>
          <div class="card-icon"><el-icon><component :is="item.icon" /></el-icon></div>
          <div class="card-content">
            <h2>{{ item.title }}</h2>
            <p>{{ item.description }}</p>
          </div>
          <div class="card-footer"><span>{{ item.meta }}</span><el-icon><ArrowRight /></el-icon></div>
        </RouterLink>
      </section>

      <section class="capability-bar">
        <div><span class="capability-index">01</span><strong>多层级模型调度</strong><small>LOD STREAMING</small></div>
        <div><span class="capability-index">02</span><strong>病害空间标注</strong><small>SPATIAL ANNOTATION</small></div>
        <div><span class="capability-index">03</span><strong>数据管理与统计</strong><small>ASSET GOVERNANCE</small></div>
      </section>
    </section>
  </main>
</template>

<script setup>
import { ArrowRight, Box, DataAnalysis, Files, Picture, Upload, Connection } from '@element-plus/icons-vue'

const workspaceItems = [
  { index: '01', title: '模型展示', description: '模型层级加载、后端 LOD 调度、构件信息和病害框选。', meta: 'MODEL WORKSPACE', path: '/lod2', icon: Box },
  { index: '02', title: '点云展示', description: '查看点云扫描成果与建筑表面数据覆盖情况。', meta: 'POINT CLOUD REVIEW', path: '/pointcloud', icon: Picture },
  { index: '03', title: '高斯渲染', description: '浏览高斯实景，支持 RAD 层级流式加载、自由漫游与天空切换。', meta: 'GAUSSIAN SPLATTING', path: '/gaussian', icon: Connection },
  { index: '04', title: '设计图预览', description: '查看已归档设计图像，支持自适应预览、缩放与拖拽。', meta: 'DRAWING REVIEW', path: '/design', icon: Files },
  { index: '05', title: '数据管理', description: '统一导入、查看和删除模型、点云、高斯场景与设计图。', meta: 'ASSET MANAGEMENT', path: '/data-import', icon: Upload },
  { index: '06', title: '信息统计', description: '可视化掌握四类资产、归档容量、入库任务与病害信息。', meta: 'ASSET ANALYTICS', path: '/data-stats', icon: DataAnalysis },
]
</script>

<style scoped>
.home-page { height: 100%; overflow: auto; background: linear-gradient(115deg, #07131f 0%, #0a1a27 52%, #07131f 100%); }
.home-inner { display: flex; width: 100%; min-height: 100%; margin: 0 auto; padding: 46px clamp(28px, 3vw, 72px) 34px; flex-direction: column; }
.hero-section { display: flex; justify-content: space-between; gap: 32px; padding: 0 0 35px; border-bottom: 1px solid var(--ui-border); }.eyebrow { display: flex; align-items: center; gap: 8px; margin: 0 0 12px; color: var(--ui-cyan); font: 10px Consolas, monospace; letter-spacing: 1.6px; }.eyebrow span { width: 22px; height: 1px; background: var(--ui-cyan); }.hero-copy h1 { margin: 0; color: var(--ui-text); font-size: 32px; font-weight: 650; letter-spacing: 1px; }.hero-subtitle { max-width: 580px; margin: 14px 0 0; color: var(--ui-muted); font-size: 14px; line-height: 1.8; }.hero-status { align-self: flex-end; min-width: 158px; padding: 13px 0 0 18px; border-left: 2px solid var(--ui-cyan); }.status-label,.hero-status small { display: block; color: #6f8995; font-size: 10px; letter-spacing: 1px; }.hero-status strong { display: flex; align-items: center; gap: 8px; margin: 6px 0; color: #ccefe3; font-size: 15px; font-weight: 550; }.hero-status strong i { width: 7px; height: 7px; border-radius: 50%; background: var(--ui-success); box-shadow: 0 0 10px var(--ui-success); }
.workspace-grid { display: grid; min-height: clamp(500px, 60vh, 780px); flex: 1; grid-template-columns: repeat(3, minmax(0, 1fr)); grid-template-rows: repeat(2, minmax(0, 1fr)); gap: 16px; margin-top: 30px; }.workspace-card { position: relative; display: flex; min-height: 0; text-decoration: none; flex-direction: column; padding: 24px; overflow: hidden; border: 1px solid var(--ui-border); border-radius: 6px; background: rgba(13, 31, 45, .76); cursor: pointer; transition: border-color .2s ease, transform .2s ease, background .2s ease; }.workspace-card::after { content: ''; position: absolute; right: -30px; bottom: -36px; width: 130px; height: 130px; border: 1px solid rgba(85,214,230,.12); border-radius: 50%; transition: .2s ease; }.workspace-card:hover { border-color: var(--ui-border-strong); background: rgba(18, 42, 57, .9); transform: translateY(-3px); }.workspace-card:hover::after { border-color: rgba(85,214,230,.32); transform: scale(1.15); }.card-index { position: absolute; top: 18px; right: 18px; color: #5c7885; font: 11px Consolas, monospace; }.card-icon { display: grid; width: 42px; height: 42px; place-items: center; border: 1px solid rgba(85,214,230,.3); border-radius: 4px; color: var(--ui-cyan); background: rgba(85,214,230,.07); }.card-icon .el-icon { font-size: 21px; }.card-content { margin-top: 20px; }.card-content h2 { margin: 0; color: var(--ui-text); font-size: 18px; font-weight: 580; }.card-content p { min-height: 42px; margin: 10px 0 0; color: var(--ui-muted); font-size: 13px; line-height: 1.75; }.card-footer { display: flex; align-items: center; justify-content: space-between; margin-top: auto; padding-top: 16px; border-top: 1px solid rgba(143,190,210,.12); color: #6f8d99; font: 10px Consolas, monospace; letter-spacing: .6px; }.card-footer .el-icon { color: var(--ui-cyan); font-size: 15px; }
.capability-bar { display: grid; grid-template-columns: repeat(3, 1fr); margin-top: 28px; border-top: 1px solid var(--ui-border); border-bottom: 1px solid var(--ui-border); }.capability-bar > div { display: grid; grid-template-columns: 30px 1fr; padding: 17px 18px; border-right: 1px solid var(--ui-border); }.capability-bar > div:last-child { border-right: 0; }.capability-index { grid-row: span 2; color: var(--ui-cyan); font: 11px Consolas, monospace; }.capability-bar strong { color: #cbdce2; font-size: 12px; font-weight: 550; }.capability-bar small { margin-top: 3px; color: #66818e; font: 9px Consolas, monospace; letter-spacing: .8px; }
@media (max-width: 1100px) { .workspace-grid { min-height: 0; flex: 0 0 auto; grid-template-columns: repeat(2, minmax(0, 1fr)); grid-template-rows: none; grid-auto-rows: minmax(220px, auto); }.workspace-card { grid-column: auto; }.home-inner { padding: 36px 28px; }.workspace-card { min-height: 220px; } }
@media (max-width: 640px) { .home-inner { padding: 28px 18px; }.hero-section { flex-direction: column; }.hero-status { align-self: stretch; }.workspace-grid { grid-template-columns: 1fr; }.capability-bar { grid-template-columns: 1fr; }.capability-bar > div { border-right: 0; border-bottom: 1px solid var(--ui-border); }.capability-bar > div:last-child { border-bottom: 0; }.hero-copy h1 { font-size: 25px; } }
</style>
