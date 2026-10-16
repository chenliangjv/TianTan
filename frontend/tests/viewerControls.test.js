import test from 'node:test';
import assert from 'node:assert/strict';
import * as THREE from 'three';
import * as LegacyThree from '../src/assets/modelLOD/build/three.module.js';
import { createKeyboardRoaming } from '../src/utils/keyboardRoaming.js';
import { disposePointCloudBounds } from '../src/utils/pointCloudBounds.js';

function setup(three = THREE, getSpeed = () => 1) {
  const eventTarget = new EventTarget();
  const documentTarget = new EventTarget();
  const camera = new three.PerspectiveCamera();
  camera.position.set(0, 0, 10);
  camera.lookAt(0, 0, 0);
  const controls = { target: new three.Vector3(), enabled: true };
  let enabled = true;
  const roaming = createKeyboardRoaming({ camera, controls, eventTarget, documentTarget,
    isEnabled: () => enabled, getScale: () => 10, getSpeed });
  const key = (code, type = 'keydown', extra = {}) => {
    const event = new Event(type, { cancelable: true });
    Object.assign(event, { code, ...extra });
    eventTarget.dispatchEvent(event);
    return event;
  };
  const move = (keys, hz = 60) => {
    keys.forEach(code => key(code));
    roaming.update(0);
    for (let i = 1; i <= hz; i++) roaming.update(i * 1000 / hz);
    keys.forEach(code => key(code, 'keyup'));
  };
  return { camera, controls, roaming, key, move, eventTarget, documentTarget,
    disable: () => { enabled = false; }, enable: () => { enabled = true; } };
}

for (const [label, three] of [['point cloud', THREE], ['model', LegacyThree]]) {
  test(`${label}: all six keys translate the camera and target together`, () => {
    for (const [code, axis, sign] of [['KeyW', 'z', -1], ['KeyS', 'z', 1], ['KeyA', 'x', -1],
      ['KeyD', 'x', 1], ['KeyQ', 'y', 1], ['KeyE', 'y', -1]]) {
      const f = setup(three);
      const start = f.camera.position.clone();
      f.move([code]);
      const delta = f.camera.position.clone().sub(start);
      assert.ok(delta[axis] * sign > 0, code);
      assert.ok(delta.clone().sub(f.controls.target).length() < 1e-10);
      assert.ok(Math.abs(f.camera.position.distanceTo(f.controls.target) - 10) < 1e-10);
      f.roaming.dispose();
    }
  });
}

test('movement is frame-rate independent, normalized diagonally and stops on keyup', () => {
  const distances = [];
  for (const [keys, hz] of [[['KeyW'], 30], [['KeyW'], 120], [['KeyW', 'KeyD'], 60]]) {
    const f = setup();
    f.move(keys, hz);
    distances.push(f.controls.target.length());
    const stopped = f.camera.position.clone();
    f.roaming.update(1050);
    assert.ok(stopped.equals(f.camera.position));
    f.roaming.dispose();
  }
  assert.ok(Math.max(...distances) - Math.min(...distances) < 1e-10);
});

test('roaming speed multiplier scales movement without changing camera orientation or target distance', () => {
  const normal = setup(), faster = setup(THREE, () => 2);
  normal.move(['KeyW']); faster.move(['KeyW']);
  assert.ok(Math.abs(faster.controls.target.length() - normal.controls.target.length() * 2) < 1e-10);
  assert.ok(Math.abs(faster.camera.position.distanceTo(faster.controls.target) - 10) < 1e-10);
  assert.ok(faster.camera.quaternion.equals(normal.camera.quaternion));
  normal.roaming.dispose(); faster.roaming.dispose();
});

test('opposing keys cancel and long frames do not cause large jumps', () => {
  const f = setup();
  f.move(['KeyW', 'KeyS', 'KeyA', 'KeyD', 'KeyQ', 'KeyE']);
  assert.equal(f.controls.target.length(), 0);
  f.key('KeyW');
  f.roaming.update(10_000);
  assert.ok(f.controls.target.length() <= 0.176);
  f.roaming.dispose();
});

test('top and bottom views follow the camera axes instead of world Y', () => {
  for (const sign of [-1, 1]) {
    const f = setup();
    f.camera.position.set(0, sign * 10, 0);
    f.camera.lookAt(0, 0, 0);
    const start = f.camera.position.clone();
    f.move(['KeyW']);
    assert.ok(Number.isFinite(f.camera.position.length()));
    assert.ok(f.controls.target.length() > 0);
    const direction = f.camera.position.clone().sub(start).normalize();
    const expected = f.camera.getWorldDirection(new THREE.Vector3());
    assert.ok(direction.dot(expected) > 0.99);
    f.roaming.dispose();
  }
});

test('camera roll rotates every roaming axis with the view', () => {
  const f = setup();
  f.camera.position.set(0, 0, 10);
  f.camera.lookAt(0, 0, 0);
  f.camera.rotateZ(Math.PI / 2);
  const start = f.camera.position.clone();
  f.move(['KeyD']);
  const direction = f.camera.position.clone().sub(start).normalize();
  assert.ok(direction.dot(new THREE.Vector3(0, 1, 0)) > 0.99);
  f.roaming.dispose();
});

test('typing, modifiers, composition and unloaded scenes do not trigger roaming', () => {
  const f = setup();
  const input = { closest: () => ({}) };
  for (const extra of [{ ctrlKey: true }, { altKey: true }, { metaKey: true }, { isComposing: true }]) {
    assert.equal(f.key('KeyW', 'keydown', extra).defaultPrevented, false);
  }
  f.documentTarget.activeElement = input;
  assert.equal(f.key('KeyW').defaultPrevented, false);
  f.documentTarget.activeElement = { isContentEditable: true };
  assert.equal(f.key('KeyW').defaultPrevented, false);
  f.documentTarget.activeElement = null;
  f.disable();
  assert.equal(f.key('KeyW').defaultPrevented, false);
  f.roaming.update(0);
  f.roaming.update(16);
  assert.equal(f.controls.target.length(), 0);
  f.roaming.dispose();
});

test('blur, visibility, input focus, reset and dispose release held keys', () => {
  for (const reset of [f => f.eventTarget.dispatchEvent(new Event('blur')),
    f => f.documentTarget.dispatchEvent(new Event('visibilitychange')),
    f => { f.documentTarget.activeElement = { isContentEditable: true }; f.roaming.update(8); f.documentTarget.activeElement = null; },
    f => f.roaming.reset(), f => f.roaming.dispose(),
    f => { f.disable(); f.roaming.update(8); f.enable(); }]) {
    const f = setup();
    f.key('KeyW');
    f.roaming.update(0);
    reset(f);
    f.roaming.update(16);
    f.roaming.update(32);
    assert.equal(f.controls.target.length(), 0);
    f.roaming.dispose();
    assert.equal(f.key('KeyW').defaultPrevented, false);
  }
});

test('unload disposes visible and cached Potree boxes even with missing parent pointers', () => {
  const scene = new THREE.Scene();
  const root = new THREE.Group();
  root.name = 'bbroot';
  scene.add(root);
  const unrelated = new THREE.Group();
  scene.add(unrelated);
  const geometry = new THREE.BufferGeometry();
  const material = new THREE.LineBasicMaterial();
  let geometryDisposals = 0;
  let materialDisposals = 0;
  geometry.addEventListener('dispose', () => geometryDisposals++);
  material.addEventListener('dispose', () => materialDisposals++);
  const visible = new THREE.LineSegments(geometry, material);
  const cached = new THREE.LineSegments(geometry, material);
  root.children = [visible];
  const cloud = { boundingBoxNodes: [visible, cached] };
  disposePointCloudBounds(cloud, scene);
  disposePointCloudBounds(cloud, scene);
  assert.equal(scene.getObjectByName('bbroot'), undefined);
  assert.deepEqual(scene.children, [unrelated]);
  assert.equal(root.children.length, 0);
  assert.equal(cloud.boundingBoxNodes.length, 0);
  assert.equal(geometryDisposals, 1);
  assert.equal(materialDisposals, 1);
});

test('empty scene cleanup is safe', () => {
  disposePointCloudBounds(null, new THREE.Scene());
  disposePointCloudBounds(undefined, undefined);
});
