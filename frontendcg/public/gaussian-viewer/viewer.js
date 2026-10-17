import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import { SparkRenderer, SplatMesh } from '@sparkjsdev/spark';
import { createKeyboardRoaming } from './keyboardRoaming.js';

const origin = location.origin;
const send = (type, payload = {}) => parent.postMessage({ channel: 'tiantan-gaussian', type, ...payload }, origin);
let renderer, camera, scene, controls, spark, group, main, sky, roaming, observer;
let destroyed = false, initialized = false, wantedSky = true, skyLoading = false, skyController;
let model, radius = 1, speed = 1, lastStats = performance.now(), frames = 0, lastRendered = false;
const nativeFetch = window.fetch.bind(window);
// A proxy returning 200 for a Range request would otherwise download the whole scene.
window.fetch = async (resource, options) => {
  const request = new Request(resource, options);
  const response = await nativeFetch(request);
  if (request.headers.has('Range') && new URL(request.url).pathname.endsWith('.rad') && response.status !== 206) {
    await response.body?.cancel();
    const message = `主体分段读取失败（HTTP ${response.status}），请检查文件服务与网关。`;
    if (!destroyed) send('error', { message });
    throw new Error(message);
  }
  return response;
};
function fail(error) {
  if (destroyed) return;
  const message = error?.message || String(error);
  send('error', { message });
  const box = document.getElementById('error'); box.textContent = message; box.style.display = 'block';
}
function boundsBox(bounds) {
  if (!bounds || !Array.isArray(bounds.min) || !Array.isArray(bounds.max) || ![...bounds.min, ...bounds.max].every(Number.isFinite)) throw new Error('场景范围无效');
  return new THREE.Box3(new THREE.Vector3(...bounds.min), new THREE.Vector3(...bounds.max));
}
function resetView() {
  if (!model || !group) return;
  group.updateMatrixWorld(true);
  const box = boundsBox(model.bounds).applyMatrix4(group.matrixWorld);
  const center = box.getCenter(new THREE.Vector3()); radius = Math.max(box.getSize(new THREE.Vector3()).length() * .5, .01);
  const halfFov = Math.atan(Math.tan(THREE.MathUtils.degToRad(camera.fov / 2)) * Math.min(1, camera.aspect));
  const distance = radius / Math.sin(halfFov) * 1.12;
  camera.up.set(0, 0, 1);
  camera.position.copy(center).add(new THREE.Vector3(1, -1.3, .85).normalize().multiplyScalar(distance));
  controls.target.copy(center); controls.minDistance = radius * .002; controls.maxDistance = radius * 30;
  camera.near = Math.max(.001, radius / 10000);
  let extent = radius;
  if (model.skyBounds) extent = Math.max(extent, boundsBox(model.skyBounds).applyMatrix4(group.matrixWorld).getSize(new THREE.Vector3()).length());
  camera.far = Math.max(distance * 10, extent * 4); camera.updateProjectionMatrix(); controls.update();
}
function resize() {
  if (!renderer || destroyed) return;
  const w = Math.max(1, innerWidth), h = Math.max(1, innerHeight);
  renderer.setSize(w, h, false); camera.aspect = w / h; camera.updateProjectionMatrix();
}
async function loadSky() {
  if (!model?.skyUrl || sky || skyLoading || !wantedSky || destroyed) return;
  skyLoading = true; skyController = new AbortController();
  send('sky', { status: 'loading' });
  let candidate;
  try {
    const response = await nativeFetch(model.skyUrl, { signal: skyController.signal });
    if (!response.ok) throw new Error(`天空加载失败：${response.status}`);
    const bytes = await response.arrayBuffer();
    if (destroyed) return;
    candidate = new SplatMesh({ fileBytes: bytes, fileName: 'environment.ply', extSplats: true, lod: false, raycastable: false });
    await candidate.initialized;
    if (destroyed) { candidate.dispose(); return; }
    sky = candidate; sky.visible = wantedSky; group.add(sky); spark.setDirty();
    send('sky', { status: wantedSky ? 'on' : 'off' });
  } catch (error) {
    candidate?.dispose();
    if (!destroyed && error.name !== 'AbortError') send('sky', { status: 'error', message: error.message });
  } finally { skyLoading = false; }
}
async function load(payload) {
  if (initialized || destroyed) return;
  initialized = true; model = payload.model; wantedSky = payload.sky !== false;
  try {
    renderer = new THREE.WebGLRenderer({ antialias: false, alpha: false, powerPreference: 'high-performance' });
    renderer.setPixelRatio(Math.min(devicePixelRatio, 1.5)); renderer.setClearColor('#07131f');
    renderer.domElement.tabIndex = 0; renderer.domElement.setAttribute('aria-label', '高斯场景，鼠标拖动浏览，WASDQE 漫游');
    document.body.appendChild(renderer.domElement);
    renderer.domElement.addEventListener('webglcontextlost', event => { event.preventDefault(); fail(new Error('显卡上下文已丢失，请卸载后重新加载场景。')); });
    renderer.domElement.addEventListener('pointerdown', () => renderer.domElement.focus());
    scene = new THREE.Scene(); camera = new THREE.PerspectiveCamera(55, 1, .01, 10000);
    controls = new OrbitControls(camera, renderer.domElement); controls.enableDamping = true;
    const low = payload.quality === 'low';
    spark = new SparkRenderer({ renderer, pagedExtSplats: true, maxPagedSplats: (low ? 32 : 64) * 65536,
      lodSplatCount: low ? 500000 : 1000000, numLodFetchers: 2 });
    scene.add(spark); group = new THREE.Group(); scene.add(group);
    if (model.transform) {
      group.position.fromArray(model.transform.position); group.quaternion.fromArray(model.transform.quaternion); group.scale.fromArray(model.transform.scale);
    }
    resize(); resetView();
    observer = new ResizeObserver(resize); observer.observe(document.body);
    roaming = createKeyboardRoaming({ camera, controls, isEnabled: () => !destroyed, getScale: () => radius * 2, getSpeed: () => speed });
    main = new SplatMesh({ url: model.mainUrl, paged: true, raycastable: false });
    group.add(main);
    renderer.setAnimationLoop(time => {
      if (destroyed || document.hidden) return;
      roaming.update(time); controls.update(); renderer.render(scene, camera); frames++;
      const selected = main?.paged?.numSplats || 0;
      if (selected > 0 && !lastRendered) { lastRendered = true; send('loaded'); }
      if (time - lastStats > 1000) {
        send('stats', { fps: Math.round(frames * 1000 / (time - lastStats)), splats: selected,
          pages: spark.pager?.pageToSplatsChunk.filter(Boolean).length || 0 });
        frames = 0; lastStats = time;
      }
    });
    await main.paged.getRadMeta();
    if (!destroyed) { send('indexed'); loadSky(); }
  } catch (error) { fail(error); }
}
function dispose() {
  if (destroyed) return; destroyed = true;
  skyController?.abort(); renderer?.setAnimationLoop(null); observer?.disconnect(); roaming?.dispose(); controls?.dispose();
  main?.dispose(); sky?.dispose(); spark?.dispose(); scene?.clear(); renderer?.dispose(); renderer?.forceContextLoss();
}
addEventListener('message', event => {
  if (event.origin !== origin || event.source !== parent || event.data?.channel !== 'tiantan-gaussian') return;
  const data = event.data;
  if (data.type === 'load') load(data);
  if (data.type === 'reset') resetView();
  if (data.type === 'sky') {
    wantedSky = Boolean(data.enabled);
    if (sky) { sky.visible = wantedSky; spark?.setDirty(); send('sky', { status: wantedSky ? 'on' : 'off' }); }
    else if (wantedSky) loadSky();
    else send('sky', { status: 'off' });
  }
  if (data.type === 'background' && /^#[0-9a-f]{6}$/i.test(data.color)) renderer?.setClearColor(data.color);
  if (data.type === 'speed' && Number.isFinite(data.value)) speed = Math.max(.1, Math.min(5, data.value));
  if (data.type === 'dispose') dispose();
});
addEventListener('pagehide', dispose);
addEventListener('unhandledrejection', event => { if (event.reason?.name !== 'AbortError') fail(event.reason); });
send('ready');
