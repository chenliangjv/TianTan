// Opt-in browser integration check. Uses an isolated local file server and never
// starts/stops the user's application services or writes to their database.
// node tests/gaussian-browser.mjs <playwright module path> [screenshot directory]
import fs from 'node:fs';
import path from 'node:path';
import http from 'node:http';
import assert from 'node:assert/strict';
import { fileURLToPath, pathToFileURL } from 'node:url';

const frontend = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const root = path.dirname(frontend);
const output = path.join(root, '3DGS/圜丘坛高斯格式/ply-result/point_cloud/output');
const validation = JSON.parse(fs.readFileSync(path.join(root, 'backend/data-service/target/gaussian-validation.json')));
const { chromium } = await import(process.argv[2] ? pathToFileURL(path.resolve(process.argv[2])).href : 'playwright');
const scene = { id: 1, name: '圜丘坛 · 高斯实景', status: 'READY', sourceSplatCount: validation.rad.leafCount,
  totalSplatCount: validation.rad.totalCount, chunkCount: validation.rad.chunks, totalSize: 674606228,
  hasSky: true, skyDefaultEnabled: true, bounds: validation.rad.bounds, skyBounds: validation.sky.bounds,
  transform: { position: [0,0,0], quaternion: [0,0,0,1], scale: [1,1,1] },
  mainUrl: '/api/data/gaussians/1/files/scene.rad', skyUrl: '/api/data/gaussians/1/files/environment.ply' };
let radBytes = 0, radRequests = 0, skyRequests = 0, fullRadRequests = 0;
const server = http.createServer((req, res) => {
  try {
    const url = new URL(req.url, 'http://localhost');
    if (url.pathname === '/api/data/gaussians/list') { res.setHeader('Content-Type','application/json'); res.end(JSON.stringify([scene])); return; }
    if (url.pathname === '/api/data/models/list') { res.setHeader('Content-Type','application/json'); res.end('[]'); return; }
    let file;
    const isRad = url.pathname === scene.mainUrl, isSky = url.pathname === scene.skyUrl;
    if (isRad) file = path.join(output,'point_cloud-lod.rad');
    else if (isSky) { file = path.join(output,'environment.ply'); skyRequests++; }
    else {
      const dist = path.join(frontend,'dist');
      file = path.resolve(dist, '.' + decodeURIComponent(url.pathname));
      if (!file.startsWith(dist + path.sep) && file !== dist) { res.writeHead(403).end(); return; }
      if (!fs.existsSync(file) || fs.statSync(file).isDirectory()) file = path.join(dist,'index.html');
    }
    const length = fs.statSync(file).size;
    const match = /^bytes=(\d+)-(\d+)$/.exec(req.headers.range || '');
    let start = 0, end = length - 1;
    if (match) {
      start = Number(match[1]); end = Math.min(Number(match[2]), length - 1);
      if (start > end || start >= length) { res.writeHead(416).end(); return; }
      res.statusCode = 206; res.setHeader('Content-Range',`bytes ${start}-${end}/${length}`);
    }
    if (isRad) { if (!match) fullRadRequests++; radRequests++; radBytes += end - start + 1; }
    res.setHeader('Accept-Ranges','bytes'); res.setHeader('Content-Length',end-start+1);
    res.setHeader('Content-Type', ({ '.js':'text/javascript', '.html':'text/html', '.css':'text/css', '.json':'application/json', '.png':'image/png', '.jpg':'image/jpeg' })[path.extname(file)] || 'application/octet-stream');
    const stream = fs.createReadStream(file, { start, end }); stream.on('error', () => res.destroy()); res.on('close', () => stream.destroy()); stream.pipe(res);
  } catch (error) { res.writeHead(500).end(error.message); }
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
let browser, page;
const errors = [], messages = [];
try {
  browser = await chromium.launch({ channel: 'chrome', headless: true, args: ['--enable-unsafe-swiftshader'] });
  page = await browser.newPage({ viewport: { width: 1500, height: 950 } });
  page.on('pageerror', error => errors.push(error.message));
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
  await page.addInitScript(() => { window.gaussianMessages = []; window.addEventListener('message', e => { if (e.data?.channel === 'tiantan-gaussian') window.gaussianMessages.push(e.data); }); });
  const origin = `http://127.0.0.1:${server.address().port}`;
  await page.goto(origin + '/gaussian?scene=1');
  await page.waitForFunction(() => window.gaussianMessages.some(e => e.type === 'loaded'), null, { timeout: 60000 });
  await page.waitForFunction(() => window.gaussianMessages.some(e => e.type === 'sky' && e.status === 'on'));
  await page.waitForFunction(() => window.gaussianMessages.filter(e => e.type === 'stats' && e.pages > 0).length >= 3);
  assert.equal(fullRadRequests, 0, 'A whole-scene RAD download occurred');
  assert.ok(radBytes < 673521936 / 2, 'Initial view fetched too much of the scene');
  const firstView = { radRequests, radBytes, skyRequests };
  const images = process.argv[3] ? path.resolve(process.argv[3]) : null;
  if (images) { fs.mkdirSync(images, { recursive:true }); await page.screenshot({path:path.join(images,'gaussian-sky-on.png')}); }
  const skySwitch = page.locator('.sky-control .el-switch');
  await skySwitch.click();
  await page.waitForFunction(() => window.gaussianMessages.filter(e => e.type === 'sky').at(-1)?.status === 'off');
  assert.equal(await page.getByRole('switch', { name:'天空开关' }).isChecked(), false);
  const beforeOffFrames = await page.evaluate(() => window.gaussianMessages.filter(e => e.type === 'stats').length);
  await page.waitForFunction(n => window.gaussianMessages.filter(e => e.type === 'stats').length >= n + 2, beforeOffFrames);
  assert.equal(await page.getByRole('switch', { name:'天空开关' }).isChecked(), false, 'Sky toggle must remain off');
  if (images) await page.screenshot({path:path.join(images,'gaussian-sky-off.png')});
  const pagesBeforeZoom = radRequests;
  await page.frameLocator('iframe').locator('canvas').hover();
  await page.mouse.wheel(0, -1800);
  await page.waitForFunction(() => window.gaussianMessages.filter(e => e.type === 'stats').at(-1)?.splats > 200000);
  assert.ok(radRequests > pagesBeforeZoom, 'Moving closer should request additional RAD pages');
  if (images) await page.screenshot({path:path.join(images,'gaussian-close-view.png')});
  await skySwitch.click();
  await page.waitForFunction(() => window.gaussianMessages.filter(e => e.type === 'sky').at(-1)?.status === 'on');
  assert.equal(await page.getByRole('switch', { name:'天空开关' }).isChecked(), true);
  assert.equal(skyRequests, 1, 'Sky toggle downloaded the sky again');
  await page.getByRole('button', { name:'全屏', exact:true }).click();
  await page.waitForFunction(() => Boolean(document.fullscreenElement));
  await page.getByRole('button', { name:'退出全屏', exact:true }).click();
  await page.waitForFunction(() => !document.fullscreenElement);
  await page.getByRole('button', { name:'视角复位' }).click();
  await page.getByRole('button', { name:'卸载场景' }).click();
  assert.equal(await page.locator('iframe').count(), 0);
  await page.getByRole('button', { name:'加载场景' }).click();
  await page.waitForFunction(() => window.gaussianMessages.filter(e => e.type === 'loaded').length >= 2, null, { timeout:60000 });
  messages.push(...await page.evaluate(() => window.gaussianMessages || []));
  await page.getByRole('link', { name:'数据管理', exact:true }).click();
  await page.goto(origin + '/data-import?category=GAUSSIAN');
  await page.getByText('导入高斯场景', { exact:true }).waitFor();
  await page.locator('input[webkitdirectory]').setInputFiles(output);
  await page.getByText('已匹配 environment.ply', { exact:true }).waitFor();
  if (images) await page.screenshot({path:path.join(images,'gaussian-management.png')});
  messages.push(...await page.evaluate(() => window.gaussianMessages || []));
  assert.deepEqual(errors, [], 'Browser errors');
  console.log(JSON.stringify({ passed:true, firstView, skyRequests, fullRadRequests, lastStats:messages.filter(e=>e.type==='stats').at(-1), screenshots:images }, null, 2));
} catch (error) {
  console.error(JSON.stringify({ errors, messages: await page?.evaluate(() => window.gaussianMessages?.slice(-12)), radRequests, radBytes, skyRequests }));
  if (process.argv[3] && page) await page.screenshot({ path: path.join(path.resolve(process.argv[3]), 'gaussian-test-failure.png') });
  throw error;
} finally { await browser?.close(); await new Promise(resolve => server.close(resolve)); }
