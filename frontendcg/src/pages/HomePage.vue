<script setup>
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import IconMark from '../components/IconMark.vue'
import heroImage from '../assets/Q7Lqq7F0KI.jpg'

const navigation = [
  { label: '首页', to: '/', name: 'home' },
  { label: '模型展示', to: '/model', name: 'model' },
  { label: '点云展示', to: '/pointcloud', name: 'points' },
  { label: '高斯渲染', to: '/gaussian', name: 'gaussian' },
  { label: '设计图预览', to: '/design', name: 'design' },
  { label: '数据管理', to: '/manage', name: 'manage' },
  { label: '信息统计', to: '/stats', name: 'stats' },
]

const API_BASE = (import.meta.env.VITE_API_BASE_URL || '').replace(/\/$/, '')
const stats = ref(null)
const statsLoading = ref(false)
const statsError = ref('')

const number = value => Number(value || 0).toLocaleString('zh-CN')

function formatBytes(value) {
  const bytes = Number(value || 0)
  if (bytes <= 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  const index = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1)
  return `${(bytes / 1024 ** index).toFixed(index ? 2 : 0)} ${units[index]}`
}

function displayCount(value) {
  const count = Number(value || 0)
  return count < 10 ? `0${count}` : number(count)
}

const entries = computed(() => [
  { title: '三维模型', subtitle: '层级调度与构件信息', count: displayCount(stats.value?.totals?.total3DModels), en: 'MODEL EXPLORER', to: '/model' },
  { title: '点云扫描', subtitle: '从亿万个点，读懂细节', count: displayCount(stats.value?.totals?.totalPointCloudModels), en: 'POINT CLOUD', to: '/pointcloud' },
  { title: '高斯实景', subtitle: '以真实光影，重返现场', count: displayCount(stats.value?.totals?.totalGaussianScenes), en: 'GAUSSIAN SCENE', to: '/gaussian' },
  { title: '设计图档案', subtitle: '图纸与建筑，彼此印证', count: displayCount(stats.value?.totals?.totalDesignImages), en: 'DRAWING ARCHIVE', to: '/design' },
])

const totalAssets = computed(() => stats.value ? number(stats.value.totals?.totalAssets) : '—')
const totalBytes = computed(() => stats.value ? formatBytes(stats.value.totals?.totalBytesAll) : '—')
const activeDiseases = computed(() => stats.value ? number(stats.value.diseases?.active) : '—')

async function loadStats() {
  statsLoading.value = true
  statsError.value = ''
  try {
    const response = await fetch(`${API_BASE}/api/data/statistics?days=30`, {
      cache: 'no-store',
    })
    if (!response.ok) throw new Error(`统计服务返回 ${response.status}`)
    const result = await response.json()
    if (!result.totals) throw new Error('统计数据格式不正确')
    stats.value = result
  } catch (error) {
    statsError.value = error.message || '统计数据加载失败'
    stats.value = null
  } finally {
    statsLoading.value = false
  }
}

onMounted(loadStats)
</script>

<template>
  <div class="heritage-shell">
    <aside class="heritage-spine" aria-label="天坛数字遗产">
      <RouterLink class="seal" to="/" aria-label="返回首页">天<br />坛</RouterLink>
      <p>让遗产在数字中延续</p>
      <span>TIANTAN<br />1530 — 2026</span>
    </aside>

    <div class="heritage-body">
      <header class="global-top">
        <RouterLink class="brand" to="/">
          <strong>天坛 · 数字遗产</strong>
          <small>TEMPLE OF HEAVEN</small>
        </RouterLink>
        <nav aria-label="主导航">
          <RouterLink v-for="item in navigation" :key="item.name" :to="item.to" class="nav-link" :class="{ active: item.name === 'home' }">
            {{ item.label }}
          </RouterLink>
        </nav>
        <button class="help-button" type="button" aria-label="帮助">
          <IconMark name="help" :size="16" />
        </button>
      </header>

      <main class="main-content">
        <section class="heritage-home">
          <div class="editorial-heading">
            <span>天坛数字遗产档案</span>
            <span>卷一 · 圜丘坛 / SEPTEMBER 2026</span>
          </div>

          <section class="home-hero" aria-labelledby="home-title">
            <div class="hero-copy">
              <p class="eyebrow">THE LIVING ARCHIVE</p>
              <h1 id="home-title">见天地，<br />见细微。</h1>
              <p class="hero-description">以数字之眼，留住建筑的岁月。<br />从一座坛，到每一道石纹。</p>
              <RouterLink class="primary-link" to="/model">
                开启数字探索 <IconMark name="arrow" :size="17" />
              </RouterLink>
              <div class="home-numbers">
                <div><strong>{{ totalAssets }}<span v-if="stats"> 项</span></strong><small>已归档数字资产</small></div>
                <div><strong>{{ totalBytes }}</strong><small>数字成果容量</small></div>
                <div><strong>{{ activeDiseases }}<span v-if="stats"> 处</span></strong><small>有效病害记录</small></div>
              </div>
              <small v-if="statsError" class="stats-note">统计数据暂时无法读取</small>
            </div>
            <div class="hero-scene">
              <img class="hero-image" :src="heroImage" alt="天坛祈年殿数字档案主视觉" />
              <!-- <span class="vertical-note">圜丘坛 · 始建于明嘉靖九年</span> -->
              <span class="hero-art-index">01 / CIRCULAR MOUND ALTAR</span>
            </div>
          </section>

          <div class="section-label">
            <h2>四种视角，一座天坛</h2>
            <span>EXPLORE THE COLLECTION</span>
          </div>

          <section class="feature-grid" aria-label="数字资产入口">
            <RouterLink v-for="entry in entries" :key="entry.to" :to="entry.to" class="feature-card">
              <div class="feature-body">
                <span class="feature-index">{{ entry.en }}</span>
                <div class="feature-title-row">
                  <h3>{{ entry.title }}</h3>
                  <span>{{ entry.count }}</span>
                </div>
                <p>{{ entry.subtitle }}</p>
              </div>
            </RouterLink>
          </section>

          <div class="home-bottom">
            <section class="activity">
              <div class="panel-heading">
                <div><p class="eyebrow">RECENT ACTIVITY</p><h2>最近动态</h2></div>
                <RouterLink class="text-link" to="/manage">查看全部 <IconMark name="arrow" :size="15" /></RouterLink>
              </div>
              <div class="activity-row">
                <span class="activity-icon"><IconMark name="spark" /></span>
                <div class="activity-copy"><strong>圜丘坛高斯实景已归档</strong><small>主体场景与天空资源 · 643.35 MB</small></div>
                <time>10:24</time><span class="status">已完成</span>
              </div>
              <div class="activity-row">
                <span class="activity-icon"><IconMark name="document" /></span>
                <div class="activity-copy"><strong>新增 4 张圜丘坛设计图</strong><small>平面、立面与节点详图</small></div>
                <time>09:48</time><span class="status">已完成</span>
              </div>
            </section>
            <section class="collection-note">
              <span class="seal small">守<br />望</span>
              <h3>让每一次记录，<br />成为保护的依据。</h3>
              <div class="quick-links">
                <RouterLink to="/manage">管理数据资产 <IconMark name="arrow" :size="15" /></RouterLink>
                <RouterLink to="/stats">查看信息统计 <IconMark name="arrow" :size="15" /></RouterLink>
              </div>
            </section>
          </div>
        </section>
      </main>
    </div>
  </div>
</template>
