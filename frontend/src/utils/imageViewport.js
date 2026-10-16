export function fitImage(image, viewport) {
  const scale = Math.max(0.001, Math.min(1, Math.max(1, viewport.width - 48) / image.width, Math.max(1, viewport.height - 48) / image.height));
  return { scale, x: (viewport.width - image.width * scale) / 2, y: (viewport.height - image.height * scale) / 2 };
}

export function zoomImage(view, scale, anchor) {
  const ratio = scale / view.scale;
  return { scale, x: anchor.x - (anchor.x - view.x) * ratio, y: anchor.y - (anchor.y - view.y) * ratio };
}
