import { createRouter, createWebHistory } from 'vue-router'
import HomeView from '../views/HomeView.vue'
import TiantanDemo from '../views/TiantanDemo.vue'
import PointCloudDemo from '../views/PointCloudDemo.vue'
import DesignPreview from '../views/DesignPreview.vue'
import DataImport from '../views/DataImport.vue'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/', name: 'home', component: HomeView },
    { path: '/lod2', name: 'lod2', component: TiantanDemo },
    { path: '/pointcloud', name: 'pointcloud', component: PointCloudDemo },
    { path: '/gaussian', name: 'gaussian', component: () => import('../views/GaussianRender.vue') },
    { path: '/design', name: 'design', component: DesignPreview },
    { path: '/data-import', name: 'data-import', component: DataImport },
    { path: '/data-stats', name: 'data-stats', component: () => import('../views/DataStats.vue') },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

export default router
