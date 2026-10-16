import test from 'node:test';
import assert from 'node:assert/strict';
import { fitImage, zoomImage } from '../src/utils/imageViewport.js';

test('wide and tall images fit the viewport with a margin and no distortion', () => {
  for (const image of [{ width: 1920, height: 802 }, { width: 400, height: 3200 }]) {
    const view = fitImage(image, { width: 800, height: 500 });
    assert.ok(view.x >= 24 && view.y >= 24);
    assert.ok(image.width * view.scale <= 752);
    assert.ok(image.height * view.scale <= 452);
  }
});
test('small images stay at original resolution centered', () => {
  assert.deepEqual(fitImage({ width: 100, height: 50 }, { width: 800, height: 500 }), { scale: 1, x: 350, y: 225 });
});
test('zoom keeps the image coordinate under the cursor stationary', () => {
  const old = { x: 30, y: -20, scale: .5 };
  const anchor = { x: 200, y: 110 };
  const next = zoomImage(old, 1.25, anchor);
  assert.equal((anchor.x - old.x) / old.scale, (anchor.x - next.x) / next.scale);
  assert.equal((anchor.y - old.y) / old.scale, (anchor.y - next.y) / next.scale);
  assert.deepEqual(zoomImage(next, .5, anchor), old);
});
