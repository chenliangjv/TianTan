// potree-core puts helpers in a sibling "bbroot", and assigns its children
// directly. Their parent pointers cannot be relied upon for removal.
export function disposePointCloudBounds(pointCloud, scene) {
  const root = scene?.getObjectByName('bbroot');
  const helpers = new Set([...(pointCloud?.boundingBoxNodes || []), ...(root?.children || [])]);
  const geometries = new Set();
  const materials = new Set();
  for (const helper of helpers) {
    helper.traverse(object => {
      if (object.geometry) geometries.add(object.geometry);
      const list = Array.isArray(object.material) ? object.material : [object.material];
      for (const material of list) if (material) materials.add(material);
    });
    helper.parent?.remove(helper);
  }
  for (const geometry of geometries) geometry.dispose();
  for (const material of materials) material.dispose();
  if (root) {
    root.children.length = 0;
    root.parent?.remove(root);
  }
  if (pointCloud) pointCloud.boundingBoxNodes.length = 0;
}
