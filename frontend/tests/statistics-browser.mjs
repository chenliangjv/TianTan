// Run against an isolated fixture server, never against the user's database/services.
// node tests/statistics-browser.mjs <playwright module path> [screenshot directory]
import fs from 'node:fs'
import path from 'node:path'
import http from 'node:http'
import assert from 'node:assert/strict'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { statisticsFixture } from './fixtures/statistics.js'

const dist = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../dist')
const { chromium } = await import(process.argv[2] ? pathToFileURL(path.resolve(process.argv[2])).href : 'playwright')
let mode = 'normal'
const periods = []
const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://localhost')
  if (url.pathname === '/api/data/statistics') {
    const days = Number(url.searchParams.get('days')) || 30
    periods.push(days)
    res.setHeader('Content-Type', 'application/json')
    if (mode === 'error') { res.writeHead(503).end('{"message":"offline"}'); return }
    res.end(JSON.stringify(statisticsFixture(days, mode === 'empty')))
    return
  }
  if (url.pathname.startsWith('/api/')) { res.setHeader('Content-Type', 'application/json'); res.end('[]'); return }
  let file = path.resolve(dist, '.' + decodeURIComponent(url.pathname))
  if (!file.startsWith(dist + path.sep) && file !== dist) { res.writeHead(403).end(); return }
  if (!fs.existsSync(file) || fs.statSync(file).isDirectory()) file = path.join(dist, 'index.html')
  res.setHeader('Content-Type', ({ '.js': 'text/javascript', '.html': 'text/html', '.css': 'text/css', '.png': 'image/png', '.svg': 'image/svg+xml', '.jpg': 'image/jpeg' })[path.extname(file)] || 'application/octet-stream')
  const stream = fs.createReadStream(file)
  stream.on('error', () => res.destroy()); res.on('close', () => stream.destroy()); stream.pipe(res)
})
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
let browser
const errors = [], assets = []
try {
  browser = await chromium.launch({ channel: 'chrome', headless: true })
  const page = await browser.newPage({ viewport: { width: 1600, height: 1150 } })
  page.on('pageerror', error => errors.push(error.message))
  page.on('request', request => assets.push(request.url()))
  const origin = `http://127.0.0.1:${server.address().port}`
  const screenshot = async name => {
    // Let ECharts finish its update animation before capturing visual assertions.
    await page.waitForTimeout(450)
    if (process.argv[3]) await page.screenshot({ path: path.join(process.argv[3], `${name}.png`) })
  }
  const ready = async () => {
    await page.locator('.data-stats-page[aria-busy="false"] .summary-grid').waitFor()
    assert.equal(await page.locator('.statistics-chart canvas').count(), 4)
  }
  const noOverflow = async () => assert.equal(await page.locator('.data-stats-page').evaluate(el => el.scrollWidth <= el.clientWidth + 1), true, 'Statistics page must not overflow horizontally')
  await page.goto(origin)
  await page.locator('.workspace-card').first().waitFor()
  assert.equal(await page.locator('.workspace-card').count(), 6)
  assert.ok(!assets.some(url => /DataStats-/.test(url)), 'Home must not download the statistics / ECharts chunk')
  assert.equal(await page.locator('.workspace-card', { hasText: '高斯渲染' }).getAttribute('href'), '/gaussian')
  const boxes = await page.locator('.workspace-card').evaluateAll(elements => elements.map(el => ({ top: el.offsetTop, left: el.offsetLeft })))
  assert.equal(boxes[0].top, boxes[2].top)
  assert.equal(boxes[3].top, boxes[5].top)
  assert.ok(boxes[3].top > boxes[0].top)
  await screenshot('asset-home')
  await page.locator('.workspace-card', { hasText: '信息统计' }).click()
  await ready()
  await noOverflow()
  assert.equal(await page.locator('.metric-card').count(), 6)
  await screenshot('asset-statistics')
  for (const days of [7, 90, 30]) {
    await page.getByRole('button', { name: `${days} 天`, exact: true }).click()
    await ready()
    assert.equal(periods.at(-1), days)
    assert.equal(await page.locator('.metric-value strong').nth(2).textContent(), '3')
  }
  await page.locator('.category-tabs').getByRole('button', { name: '高斯场景', exact: true }).click()
  assert.equal(await page.locator('.detail-metrics > div').count(), 8)
  assert.ok((await page.locator('.detail-metrics').textContent()).includes('7,109,842'))
  assert.ok((await page.locator('.detail-metrics').textContent()).includes('9,830,143'))
  await page.locator('.asset-details').scrollIntoViewIfNeeded()
  await screenshot('asset-statistics-gaussian')
  await page.locator('.category-tabs').getByRole('button', { name: '设计图', exact: true }).click()
  assert.ok((await page.locator('.detail-metrics').textContent()).includes('PNG 图像'))
  await page.locator('.detail-links').getByRole('link', { name: '查看数据' }).click()
  assert.ok(page.url().endsWith('/data-import?category=DESIGN'))
  await page.getByRole('link', { name: '信息统计', exact: true }).click()
  await ready()
  await page.locator('.distribution-legend').getByRole('button', { name: /高斯场景/ }).click()
  assert.ok(page.url().endsWith('/data-import?category=GAUSSIAN'))
  await page.getByRole('link', { name: '信息统计', exact: true }).click()
  await ready()
  const before = await page.locator('.trend-chart').evaluate(el => el.clientWidth)
  await page.getByRole('button', { name: '收起导航' }).click()
  await page.waitForFunction(width => document.querySelector('.trend-chart').clientWidth > width, before)
  await page.waitForFunction(() => { const el = document.querySelector('.trend-chart'); return Math.abs(parseFloat(el.querySelector('canvas').style.width) - el.clientWidth) < 2 })
  await page.setViewportSize({ width: 390, height: 844 })
  await noOverflow()
  await screenshot('asset-statistics-mobile')
  await page.setViewportSize({ width: 1600, height: 1150 })
  mode = 'error'
  await page.getByRole('button', { name: '刷新数据' }).click()
  await page.getByRole('alert').waitFor()
  assert.equal(await page.locator('.metric-value strong').nth(2).textContent(), '3', 'Refresh error must retain previous snapshot')
  await page.reload()
  await page.getByRole('alert').waitFor()
  assert.equal(await page.locator('.metric-card').count(), 0, 'Initial failure must not claim zero assets')
  await screenshot('asset-statistics-error')
  mode = 'empty'
  await page.getByRole('button', { name: '重试', exact: true }).click()
  await ready()
  assert.equal(await page.locator('.metric-value strong').nth(2).textContent(), '0')
  await page.getByText('此期间暂无现存资产入库记录', { exact: true }).waitFor()
  await screenshot('asset-statistics-empty')
  mode = 'normal'
  await page.getByRole('button', { name: '刷新数据' }).click()
  await ready()
  assert.equal(await page.locator('.metric-value strong').nth(2).textContent(), '3')
  assert.deepEqual(errors, [])
  console.log(JSON.stringify({ passed: true, periods, checks: ['home-six-cards', 'lazy-charts', 'periods', 'four-categories', 'detail-links', 'sidebar-resize', 'mobile', 'error-retry', 'empty', 'snapshot-refresh'], errors }))
} finally {
  await browser?.close()
  await new Promise(resolve => server.close(resolve))
}
