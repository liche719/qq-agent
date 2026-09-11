import { createRouter, createWebHashHistory } from 'vue-router'
import { hasAuth } from './auth'
import LoginView from './views/LoginView.vue'
import DashboardView from './views/DashboardView.vue'

/**
 * 用 hash 路由：网关只放行固定的几个路径，
 * 这样不需要服务端 rewrite，暴露面也最小。
 */
const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', redirect: '/dashboard' },
    { path: '/login', name: 'login', component: LoginView },
    { path: '/dashboard', name: 'dashboard', component: DashboardView, meta: { auth: true } },
    { path: '/:pathMatch(.*)*', redirect: '/dashboard' }
  ]
})

router.beforeEach(to => {
  if (to.meta.auth && !hasAuth()) {
    return { name: 'login' }
  }
  if (to.name === 'login' && hasAuth()) {
    return { name: 'dashboard' }
  }
  return true
})

export default router
