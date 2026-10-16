import test from 'node:test';
import assert from 'node:assert/strict';
import { PointCloudMaterial, PointShape } from 'potree-core';
import { attachPointCloudColor, DEFAULT_POINT_CLOUD_COLOR, normalizePointCloudColor } from '../src/utils/pointCloudColor.js';

// Only texture construction needs a canvas. Shader compatibility is tested
// against the installed Potree material, not a hand-maintained shader fixture.
globalThis.document = {
  createElement: () => ({
    width: 64, height: 64,
    getContext: () => ({ createLinearGradient: () => ({ addColorStop() {} }), fillRect() {} }),
  }),
};
const compile = material => {
  const shader = { vertexShader: material.vertexShader, fragmentShader: material.fragmentShader, uniforms: material.uniforms };
  material.onBeforeCompile(shader, {});
  return shader;
};

test('normalizes defaults and clamps unsupported inputs', () => {
  assert.deepEqual(normalizePointCloudColor(), DEFAULT_POINT_CLOUD_COLOR);
  assert.deepEqual(normalizePointCloudColor({ mode: 'unknown', solid: '#55D6E6', brightness: -100, contrast: 100, gamma: NaN }), {
    mode: 'rgb', solid: '#55d6e6', brightness: -1, contrast: 0.9, gamma: 1,
  });
  assert.equal(normalizePointCloudColor({ solid: 'bad', gamma: 0 }).gamma, 0.2);
  assert.equal(normalizePointCloudColor({ solid: 'bad' }).solid, '#ffffff');
});

test('patches actual Potree 2 shader while retaining native RGB and fragment paths', () => {
  const material = new PointCloudMaterial({ newFormat: true });
  const source = material.vertexShader;
  const fragment = material.fragmentShader;
  const controller = attachPointCloudColor(material);
  const shader = compile(material);
  assert.equal(material.vertexShader, source);
  assert.equal(shader.fragmentShader, fragment);
  assert.match(shader.vertexShader, /vColor = rgba;/);
  assert.match(shader.vertexShader, /else if \(tiantanColorMode == 2\)/);
  assert.match(shader.vertexShader, /vColor\.rgb = tiantanAdjustColor\(tiantanSolidColor\);/);
  assert.match(shader.vertexShader, /vColor\.rgb = tiantanAdjustColor\(rgba\.rgb\);/);
  assert.doesNotMatch(shader.vertexShader, /vColor\.a\s*=/);
  assert.equal(shader.uniforms.tiantanColorMode.value, 0);
  controller.dispose();
  material.dispose();
});

test('rapid changes only update uniforms, never source or material version', () => {
  const material = new PointCloudMaterial({ newFormat: true });
  const controller = attachPointCloudColor(material);
  const shader = compile(material);
  const version = material.version;
  const source = material.vertexShader;
  const key = material.customProgramCacheKey();
  for (let i = 0; i < 100; i++) controller.update({ ...DEFAULT_POINT_CLOUD_COLOR, brightness: i / 100 });
  assert.equal(material.version, version);
  assert.equal(material.vertexShader, source);
  assert.equal(material.customProgramCacheKey(), key);
  assert.equal(shader.uniforms.tiantanColorMode.value, 2);
  assert.equal(shader.uniforms.tiantanRgbAdjustments.value.x, 0.99);
  assert.equal(material.rgbBrightness, 0);
  controller.update({ ...DEFAULT_POINT_CLOUD_COLOR, mode: 'solid', solid: '#55d6e6' });
  assert.equal(shader.uniforms.tiantanColorMode.value, 1);
  assert.deepEqual(shader.uniforms.tiantanSolidColor.value.toArray(), [85 / 255, 214 / 255, 230 / 255]);
  controller.update(DEFAULT_POINT_CLOUD_COLOR);
  assert.equal(shader.uniforms.tiantanColorMode.value, 0);
  assert.equal(material.version, version);
  controller.dispose();
  material.dispose();
});

test('EDL and shape recompilation retain settings and uniform identity', () => {
  const material = new PointCloudMaterial({ newFormat: true });
  const controller = attachPointCloudColor(material);
  const first = compile(material);
  controller.update({ ...DEFAULT_POINT_CLOUD_COLOR, gamma: 1.4 });
  material.useEDL = true;
  material.shape = PointShape.CIRCLE;
  const second = compile(material);
  assert.match(second.vertexShader, /#define use_edl/);
  assert.match(second.vertexShader, /uniform int tiantanColorMode/);
  assert.equal(second.uniforms.tiantanRgbAdjustments, first.uniforms.tiantanRgbAdjustments);
  assert.equal(second.uniforms.tiantanRgbAdjustments.value.z, 1.4);
  controller.dispose();
  material.dispose();
});

test('solid adjustments preserve base color, avoid recompilation and survive EDL', () => {
  const material = new PointCloudMaterial({ newFormat: true });
  const controller = attachPointCloudColor(material);
  const shader = compile(material);
  const version = material.version;
  const key = material.customProgramCacheKey();
  const settings = { ...DEFAULT_POINT_CLOUD_COLOR, mode: 'solid', solid: '#55d6e6', brightness: -0.1, contrast: 0.2, gamma: 1.4 };
  for (let i = 0; i < 100; i++) controller.update({ ...settings, brightness: i / 100 });
  controller.update(settings);
  assert.equal(material.version, version);
  assert.equal(material.customProgramCacheKey(), key);
  assert.equal(shader.uniforms.tiantanColorMode.value, 1);
  assert.deepEqual(shader.uniforms.tiantanSolidColor.value.toArray(), [85 / 255, 214 / 255, 230 / 255]);
  assert.deepEqual(shader.uniforms.tiantanRgbAdjustments.value.toArray(), [-0.1, 0.2, 1.4]);
  material.useEDL = true;
  const edl = compile(material);
  assert.equal(edl.uniforms.tiantanRgbAdjustments, shader.uniforms.tiantanRgbAdjustments);
  assert.deepEqual(edl.uniforms.tiantanRgbAdjustments.value.toArray(), [-0.1, 0.2, 1.4]);
  controller.update({ ...settings, mode: 'rgb' });
  assert.equal(edl.uniforms.tiantanColorMode.value, 2);
  assert.deepEqual(edl.uniforms.tiantanRgbAdjustments.value.toArray(), [-0.1, 0.2, 1.4]);
  controller.update({ ...DEFAULT_POINT_CLOUD_COLOR, mode: 'solid', solid: settings.solid });
  assert.equal(edl.uniforms.tiantanColorMode.value, 1);
  assert.deepEqual(edl.uniforms.tiantanRgbAdjustments.value.toArray(), [0, 0, 1]);
  controller.dispose(); material.dispose();
});

test('materials have separate uniform state even when sharing a program key', () => {
  const a = new PointCloudMaterial({ newFormat: true });
  const b = new PointCloudMaterial({ newFormat: true });
  const ca = attachPointCloudColor(a);
  const cb = attachPointCloudColor(b);
  const sa = compile(a);
  const sb = compile(b);
  ca.update({ ...DEFAULT_POINT_CLOUD_COLOR, mode: 'solid' });
  assert.equal(sa.uniforms.tiantanColorMode.value, 1);
  assert.equal(sb.uniforms.tiantanColorMode.value, 0);
  assert.notEqual(sa.uniforms.tiantanRgbAdjustments, sb.uniforms.tiantanRgbAdjustments);
  ca.dispose(); cb.dispose(); a.dispose(); b.dispose();
});

test('incompatible material fails before altering working hooks', () => {
  const material = new PointCloudMaterial({ newFormat: true });
  const originalHook = material.onBeforeCompile;
  const key = material.customProgramCacheKey;
  material.vertexShader = 'incompatible';
  assert.throws(() => attachPointCloudColor(material), /Potree/);
  assert.equal(material.onBeforeCompile, originalHook);
  assert.equal(material.customProgramCacheKey, key);
  material.dispose();
});

test('compile-time incompatibility reports once and preserves native shader', () => {
  const material = new PointCloudMaterial({ newFormat: true });
  const errors = [];
  const controller = attachPointCloudColor(material, { onError: error => errors.push(error) });
  material.vertexShader = 'changed by a future library update';
  assert.equal(compile(material).vertexShader, material.vertexShader);
  compile(material);
  assert.equal(errors.length, 1);
  controller.dispose(); material.dispose();
});

test('dispose restores previous hooks and ignores subsequent updates', () => {
  const material = new PointCloudMaterial({ newFormat: true });
  let calls = 0;
  material.onBeforeCompile = () => { calls++; };
  const originalHook = material.onBeforeCompile;
  const originalKey = material.customProgramCacheKey;
  const controller = attachPointCloudColor(material);
  const shader = compile(material);
  const mode = shader.uniforms.tiantanColorMode;
  assert.equal(calls, 1);
  controller.dispose();
  const version = material.version;
  controller.dispose();
  controller.update({ ...DEFAULT_POINT_CLOUD_COLOR, mode: 'solid' });
  assert.equal(material.version, version);
  assert.equal(mode.value, 0);
  assert.equal(material.onBeforeCompile, originalHook);
  assert.equal(material.customProgramCacheKey, originalKey);
  assert.equal(material.uniforms.tiantanColorMode, undefined);
  material.dispose();
});
