import { createApp } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import App from './App.vue'
import HomePage from './pages/HomePage.vue'
import ModelPage from './pages/ModelPage.vue'
import StatsPage from './pages/StatsPage.vue'
import DesignPage from './pages/DesignPage.vue'
import GaussianPage from './pages/GaussianPage.vue'
import PointCloudPage from './pages/PointCloudPage.vue'
import ManagePage from './pages/ManagePage.vue'
import PlaceholderPage from './pages/PlaceholderPage.vue'
import './styles/base.css'

const pages = [
  { path: '/', name: 'home', label: '首页' },
  { path: '/model', name: 'model', label: '模型展示' },
  { path: '/pointcloud', name: 'pointcloud', label: '点云展示' },
  { path: '/gaussian', name: 'gaussian', label: '高斯渲染' },
  { path: '/design', name: 'design', label: '设计图预览' },
  { path: '/manage', name: 'manage', label: '数据管理' },
  { path: '/stats', name: 'stats', label: '信息统计' },
  { path: '/:pathMatch(.*)*', name: 'not-found', label: '页面不存在' },
]

const router = createRouter({
  history: createWebHistory(),
  routes: pages.map((page) => ({
    ...page,
    component: page.name === 'home'
      ? HomePage
      : page.name === 'model'
        ? ModelPage
        : page.name === 'stats'
        ? StatsPage
          : page.name === 'design'
            ? DesignPage
            : page.name === 'gaussian'
              ? GaussianPage
              : page.name === 'pointcloud'
                ? PointCloudPage
                : page.name === 'manage'
                  ? ManagePage
            : PlaceholderPage,
    meta: { label: page.label },
  })),
  scrollBehavior: () => ({ top: 0 }),
})

createApp(App).use(router).mount('#app')
