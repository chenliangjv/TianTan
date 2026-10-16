// Visual smoke test for the production build. It serves isolated fixture data and
// never connects to the user's database or development server.
import fs from 'node:fs'
import path from 'node:path'
import http from 'node:http'
import assert from 'node:assert/strict'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { statisticsFixture } from './fixtures/statistics.js'

const dist = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../dist')
const screenshotDir = process.argv[3] && path.resolve(process.argv[3])
const { chromium } = await import(process.argv[2] ? pathToFileURL(path.resolve(process.argv[2])).href : 'playwright')

if (screenshotDir) fs.mkdirSync(screenshotDir, { recursive: true })

const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://localhost')
  if (url.pathname === '/api/data/statistics') {
    res.setHeader('Content-Type', 'application/json')
    res.end(JSON.stringify(statisticsFixture(Number(url.searchParams.get('days')) || 30)))
    return
  }
  if (url.pathname.startsWith('/api/')) {
    res.setHeader('Content-Type', 'application/json')
    res.end('[]')
    return
  }

  let file = path.resolve(dist, `.${decodeURIComponent(url.pathname)}`)
  if (!file.startsWith(`${dist}${path.sep}`) && file !== dist) {
    res.writeHead(403).end()
    return
  }
  if (!fs.existsSync(file) || fs.statSync(file).isDirectory()) file = path.join(dist, 'index.html')
  res.setHeader('Content-Type', ({
    '.css': 'text/css', '.html': 'text/html', '.js': 'text/javascript', '.jpg': 'image/jpeg',
    '.jpeg': 'image/jpeg', '.png': 'image/png', '.svg': 'image/svg+xml', '.wasm': 'application/wasm',
  })[path.extname(file).toLowerCase()] || 'application/octet-stream')
  fs.createReadStream(file).pipe(res)
})

await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
let browser
const errors = []
try {
  browser = await chromium.launch({ channel: 'chrome', headless: true })
  const page = await browser.newPage({ viewport: { width: 1600, height: 1000 } })
  page.on('pageerror', error => errors.push(error.message))
  const origin = `http://127.0.0.1:${server.address().port}`
  const routes = [
    ['home', '/'],
    ['model', '/lod2'],
    ['pointcloud', '/pointcloud'],
    ['gaussian', '/gaussian'],
    ['design', '/design'],
    ['management', '/data-import'],
    ['statistics', '/data-stats'],
  ]
  const viewports = [
    ['desktop', 1600, 1000],
    ['compact', 1280, 800],
    ['mobile', 390, 844],
  ]
  const results = []

  for (const [size, width, height] of viewports) {
    await page.setViewportSize({ width, height })
    for (const [name, route] of routes) {
      await page.goto(`${origin}${route}`, { waitUntil: 'networkidle' })
      await page.waitForTimeout(300)
      const metrics = await page.evaluate(() => ({
        documentWidth: document.documentElement.scrollWidth,
        viewportWidth: document.documentElement.clientWidth,
        navLinks: document.querySelectorAll('.app-nav a').length,
        mainWidth: document.querySelector('.app-main')?.scrollWidth || 0,
        mainClientWidth: document.querySelector('.app-main')?.clientWidth || 0,
        headerRect: (() => { const rect = document.querySelector('.app-header')?.getBoundingClientRect(); return rect && { x: rect.x, y: rect.y, width: rect.width, height: rect.height } })(),
        identityRect: (() => { const rect = document.querySelector('.header-identity')?.getBoundingClientRect(); return rect && { x: rect.x, y: rect.y, width: rect.width, height: rect.height } })(),
        contentRect: (() => { const rect = document.querySelector('.app-content')?.getBoundingClientRect(); return rect && { x: rect.x, y: rect.y, width: rect.width, height: rect.height } })(),
        viewRect: (() => { const rect = document.querySelector('.main-content,.home-page,.gaussian-page,.design-page,.data-manager-page,.data-stats-page')?.getBoundingClientRect(); return rect && { x: rect.x, y: rect.y, width: rect.width, height: rect.height } })(),
        canvasRect: (() => { const rect = document.querySelector('.canvas-container')?.getBoundingClientRect(); return rect && { x: rect.x, y: rect.y, width: rect.width, height: rect.height } })(),
      }))
      assert.equal(metrics.navLinks, 7, `${name}/${size}: navigation links missing`)
      assert.ok(metrics.documentWidth <= metrics.viewportWidth + 1, `${name}/${size}: document overflows by ${metrics.documentWidth - metrics.viewportWidth}px`)
      results.push({ name, size, ...metrics })
      if (screenshotDir) await page.screenshot({ path: path.join(screenshotDir, `${size}-${name}.png`), fullPage: false })

      if (size === 'desktop' && name === 'home') {
        assert.equal(await page.locator('.workspace-card').count(), 6, 'Home entries changed')
        assert.equal(await page.locator('.heritage-archive-card').count(), 1, 'Home archive card missing')
        assert.equal(await page.locator('.workspace-card-lead').count(), 6, 'Home entry typography cards missing')
        assert.equal(await page.locator('.home-page img').count(), 0, 'Home should not rely on prepared preview images')
      }
      if (size === 'desktop' && name === 'model') {
        assert.equal(await page.locator('.viewer-toolbar .toolbar-button').count(), 4, 'Model toolbar entries changed')
        assert.equal(await page.locator('.spatial-page-heading').count(), 1, 'Model workbench heading missing')
        assert.equal(await page.locator('.model-library-panel').isVisible(), true, 'Model resource directory should be visible by default')
        assert.equal(await page.locator('.scene-workbench-panel').isVisible(), true, 'Model scene controls should be visible by default')
        assert.equal(await page.locator('.telemetry-workbench-panel').isVisible(), true, 'Model runtime info should be visible by default')
        assert.equal(await page.locator('.disease-records-panel').isVisible(), true, 'Model disease list should be visible by default')
        await page.getByRole('button', { name: '场景控制', exact: true }).click()
        assert.equal(await page.locator('.scene-workbench-panel').isVisible(), false)
        await page.getByRole('button', { name: '场景控制', exact: true }).click()
        assert.equal(await page.locator('.scene-workbench-panel').isVisible(), true)
        const sceneTools = await page.locator('.scene-workbench-panel').textContent()
        for (const label of ['病害框选', '节点包围盒', '对齐视角', '实体颜色']) assert.ok(sceneTools.includes(label), `Missing model control: ${label}`)
        assert.equal(await page.getByRole('button', { name: '图片输出', exact: true }).count(), 0, 'Model image export should stay removed')
        await page.getByRole('button', { name: '实时信息', exact: true }).click()
        assert.equal(await page.locator('.telemetry-workbench-panel').isVisible(), false)
        await page.getByRole('button', { name: '实时信息', exact: true }).click()
        assert.equal(await page.locator('.telemetry-workbench-panel').isVisible(), true)
        await page.getByRole('button', { name: '背景色', exact: true }).click()
        assert.equal(await page.locator('.background-workbench-panel').isVisible(), false)
        await page.getByRole('button', { name: '背景色', exact: true }).click()
        assert.equal(await page.locator('.background-workbench-panel').isVisible(), true)
        await page.getByRole('button', { name: '病害列表', exact: true }).click()
        assert.equal(await page.locator('.disease-workbench-panel').isVisible(), false)
        assert.equal(await page.getByRole('button', { name: '复位相机' }).count(), 1)
        assert.equal(await page.getByRole('button', { name: '全屏显示' }).count(), 1)
      }
      if (size === 'desktop' && name === 'pointcloud') {
        assert.equal(await page.locator('.viewer-toolbar .toolbar-button').count(), 3, 'Point-cloud toolbar entries changed')
        assert.equal(await page.locator('.spatial-page-heading').count(), 1, 'Point-cloud workbench heading missing')
        assert.equal(await page.locator('.dataset-workbench-panel').isVisible(), true, 'Point-cloud directory should be visible by default')
        assert.equal(await page.locator('.pointcloud-render-workbench-panel').isVisible(), true, 'Point-cloud controls should be visible by default')
        assert.equal(await page.locator('.pointcloud-telemetry-panel').isVisible(), true, 'Point-cloud runtime info should be visible by default')
        assert.equal(await page.getByRole('button', { name: '点云加载', exact: true }).count(), 0, 'Point-cloud load toolbar should be removed')
        await page.getByRole('button', { name: '场景控制', exact: true }).click()
        assert.equal(await page.locator('.pointcloud-render-workbench-panel').isVisible(), false)
        await page.getByRole('button', { name: '场景控制', exact: true }).click()
        const renderTools = await page.locator('.pointcloud-render-workbench-panel').textContent()
        for (const label of ['点预算', '点大小', 'EDL 细节增强', '节点包围盒', '原始 RGB', '单色显示']) assert.ok(renderTools.includes(label), `Missing point-cloud control: ${label}`)
        await page.getByRole('button', { name: '实时信息', exact: true }).click()
        assert.equal(await page.locator('.pointcloud-telemetry-panel').isVisible(), false)
        await page.getByRole('button', { name: '实时信息', exact: true }).click()
        assert.equal(await page.locator('.pointcloud-telemetry-panel').isVisible(), true)
        await page.getByRole('button', { name: '背景色', exact: true }).click()
        assert.equal(await page.locator('.pointcloud-right-rail .background-workbench-panel').isVisible(), false)
        await page.getByRole('button', { name: '背景色', exact: true }).click()
        assert.equal(await page.locator('.pointcloud-right-rail .background-workbench-panel').isVisible(), true)
        assert.equal(await page.getByRole('button', { name: '复位相机' }).count(), 1)
        assert.equal(await page.getByRole('button', { name: '全屏显示' }).count(), 1)
      }
      if (size === 'desktop' && name === 'gaussian') {
        assert.equal(await page.getByRole('switch', { name: '天空开关' }).count(), 1)
        await page.getByRole('button', { name: '显示设置' }).click()
        const settings = await page.locator('.settings-panel').textContent()
        for (const label of ['渲染质量', '漫游速度', '背景颜色', 'WASDQE']) assert.ok(settings.includes(label), `Missing Gaussian control: ${label}`)
        assert.equal(await page.getByRole('button', { name: '视角复位' }).count(), 1)
        assert.equal(await page.getByRole('button', { name: '全屏' }).count(), 1)
      }
      if (size === 'desktop' && name === 'design') {
        assert.equal(await page.locator('.drawing-catalog').getAttribute('aria-label'), '图纸目录')
        for (const label of ['适应窗口', '原始尺寸', '全屏查看']) assert.equal(await page.getByRole('button', { name: label }).count(), 1, `Missing drawing control: ${label}`)
        assert.equal(await page.locator('.drawing-metadata').count(), 1)
      }
      if (size === 'desktop' && name === 'management') {
        const categories = page.locator('.heading-controls .el-segmented').first()
        for (const label of ['三维模型', '点云数据', '设计图', '高斯数据']) assert.equal(await categories.getByText(label, { exact: true }).count(), 1, `Missing management category: ${label}`)
        for (const [label, selector] of [['设计图', '.image-manager'], ['高斯数据', '.gaussian-manager']]) {
          await categories.getByText(label, { exact: true }).click()
          await page.locator(selector).waitFor()
        }
        await categories.getByText('三维模型', { exact: true }).click()
        await page.locator('.manager-grid').waitFor()
        for (const label of ['选择压缩包', '清空', '开始入库']) assert.equal(await page.getByRole('button', { name: label, exact: true }).count(), 1, `Missing ingestion control: ${label}`)
      }
      if (size === 'desktop' && name === 'statistics') {
        await page.locator('.data-stats-page[aria-busy="false"] .summary-grid').waitFor()
        assert.equal(await page.locator('.statistics-chart canvas').count(), 4)
        for (const label of ['7 天', '30 天', '90 天']) assert.equal(await page.getByRole('button', { name: label, exact: true }).count(), 1, `Missing statistics period: ${label}`)
      }
    }
  }

  assert.deepEqual(errors, [])
  console.log(JSON.stringify({ passed: true, routes: routes.length, viewports: viewports.length, results, errors }))
} finally {
  await browser?.close()
  await new Promise(resolve => server.close(resolve))
}
