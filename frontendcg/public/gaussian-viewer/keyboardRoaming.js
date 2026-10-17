const MOVEMENT_KEYS = new Set(['KeyW', 'KeyA', 'KeyS', 'KeyD', 'KeyQ', 'KeyE']);

export const isTypingTarget = target => Boolean(target?.isContentEditable
  || target?.closest?.('input, textarea, select, [contenteditable="true"], [role="textbox"], [role="slider"], [role="spinbutton"]'));

// Uses the viewer's existing frame loop and vector implementation (both Three
// versions are supported). No scene traversal or additional animation loop.
export function createKeyboardRoaming({ camera, controls, isEnabled, getScale = () => 0, getSpeed = () => 1,
  eventTarget = window, documentTarget = document }) {
  const keys = new Set();
  const forward = camera.position.clone();
  const right = camera.position.clone();
  const cameraUp = camera.position.clone();
  const movement = camera.position.clone();
  let previousTime = null;

  const reset = () => { keys.clear(); previousTime = null; };
  const blocked = () => !isEnabled() || documentTarget.hidden || isTypingTarget(documentTarget.activeElement);
  const keydown = event => {
    if (event.ctrlKey || event.metaKey || event.altKey || blocked() || isTypingTarget(event.target)) {
      reset();
      return;
    }
    if (event.defaultPrevented || event.isComposing || !MOVEMENT_KEYS.has(event.code)) return;
    event.preventDefault();
    keys.add(event.code);
  };
  const keyup = event => { keys.delete(event.code); };
  const focusin = event => { if (isTypingTarget(event.target)) reset(); };
  eventTarget.addEventListener('keydown', keydown);
  eventTarget.addEventListener('keyup', keyup);
  eventTarget.addEventListener('blur', reset);
  documentTarget.addEventListener('visibilitychange', reset);
  documentTarget.addEventListener('focusin', focusin);

  return {
    reset,
    update(time = performance.now()) {
      if (blocked() || !controls.enabled) { reset(); return false; }
      const dt = previousTime === null ? 0 : Math.min(0.05, Math.max(0, (time - previousTime) / 1000));
      previousTime = time;
      if (!keys.size || !dt) return false;
      // Build all three axes from the camera orientation. The data can be
      // rotated relative to world Y, so a world-horizontal projection would
      // make W/A/S/D drift away from the direction shown on screen.
      forward.set(0, 0, -1).applyQuaternion(camera.quaternion).normalize();
      right.set(1, 0, 0).applyQuaternion(camera.quaternion).normalize();
      cameraUp.set(0, 1, 0).applyQuaternion(camera.quaternion).normalize();
      movement.set(0, 0, 0)
        .addScaledVector(forward, Number(keys.has('KeyW')) - Number(keys.has('KeyS')))
        .addScaledVector(right, Number(keys.has('KeyD')) - Number(keys.has('KeyA')))
        .addScaledVector(cameraUp, Number(keys.has('KeyQ')) - Number(keys.has('KeyE')));
      if (!movement.lengthSq()) return false;
      const scale = Math.max(Number(getScale()) || 0, 0.01);
      const distance = camera.position.distanceTo(controls.target);
      const speed = Math.max(scale * 0.01, Math.min(scale * 2, distance * 0.35));
      const multiplier = Math.max(0.1, Math.min(5, Number(getSpeed()) || 1));
      movement.normalize().multiplyScalar(speed * dt * multiplier);
      camera.position.add(movement);
      controls.target.add(movement);
      return true;
    },
    dispose() {
      reset();
      eventTarget.removeEventListener('keydown', keydown);
      eventTarget.removeEventListener('keyup', keyup);
      eventTarget.removeEventListener('blur', reset);
      documentTarget.removeEventListener('visibilitychange', reset);
      documentTarget.removeEventListener('focusin', focusin);
    },
  };
}
