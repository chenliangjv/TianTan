import { Vector3 } from 'three';

export const DEFAULT_POINT_CLOUD_COLOR = Object.freeze({
  mode: 'rgb',
  solid: '#ffffff',
  brightness: 0,
  contrast: 0,
  gamma: 1,
});

export const POINT_CLOUD_COLOR_LIMITS = Object.freeze({
  brightness: { min: -1, max: 1, step: 0.01 },
  contrast: { min: -0.9, max: 0.9, step: 0.01 },
  gamma: { min: 0.2, max: 3, step: 0.01 },
});

const clampParameter = (name, value) => {
  const number = Number(value);
  const { min, max } = POINT_CLOUD_COLOR_LIMITS[name];
  return Number.isFinite(number) ? Math.min(max, Math.max(min, number)) : DEFAULT_POINT_CLOUD_COLOR[name];
};

export function normalizePointCloudColor(settings = {}) {
  return {
    mode: settings.mode === 'solid' ? 'solid' : 'rgb',
    solid: /^#[\da-f]{6}$/i.test(settings.solid) ? settings.solid.toLowerCase() : DEFAULT_POINT_CLOUD_COLOR.solid,
    brightness: clampParameter('brightness', settings.brightness),
    contrast: clampParameter('contrast', settings.contrast),
    gamma: clampParameter('gamma', settings.gamma),
  };
}

// potree-core 2.0.15 bypasses getRGB() for new_format / rgba data. Patch only
// that branch at compilation, leaving node decoding and LOD scheduling native.
const MAIN = 'void main() {';
const RGBA = 'vColor = rgba;';
const ENCODINGS = ['vColor = toLinear(vColor);', 'vColor = fromLinear(vColor);'];
const DECLARATIONS = `
uniform int tiantanColorMode;
uniform vec3 tiantanSolidColor;
uniform vec3 tiantanRgbAdjustments;

vec3 tiantanAdjustColor(vec3 baseColor) {
  // Neutral settings preserve the exact original/swatches without pow rounding.
  if (all(equal(tiantanRgbAdjustments, vec3(0.0, 0.0, 1.0)))) return baseColor;
  vec3 rgb = pow(clamp(baseColor, 0.0, 1.0), vec3(tiantanRgbAdjustments.z));
  rgb += tiantanRgbAdjustments.x;
  float contrast = tiantanRgbAdjustments.y;
  float factor = 1.0158730158730156 * (contrast + 1.0) / (1.0158730158730156 - contrast);
  return clamp((rgb - 0.5) * factor + 0.5, 0.0, 1.0);
}
`;
const COLOR_SELECTION = `${RGBA}
#if defined(color_type_rgb)
  if (tiantanColorMode == 1) {
    vColor.rgb = tiantanAdjustColor(tiantanSolidColor);
  } else if (tiantanColorMode == 2) {
    vColor.rgb = tiantanAdjustColor(rgba.rgb);
  }
#endif
`;

function patchVertexShader(source) {
  for (const anchor of [MAIN, RGBA, ...ENCODINGS]) {
    if (source.split(anchor).length !== 2) {
      throw new Error('当前点云材质不支持颜色控制，请检查 Potree 版本');
    }
  }
  let shader = source.replace(MAIN, `${DECLARATIONS}\n${MAIN}`).replace(RGBA, COLOR_SELECTION);
  for (const encoding of ENCODINGS) {
    // Hex swatches are already display sRGB. Keep native RGB conversion exactly
    // as-is, but avoid converting the chosen swatch a second time. Preserve alpha
    // and the clipping/highlighting operations between selection and encoding.
    shader = shader.replace(encoding, `
#if defined(new_format) && defined(color_type_rgb)
    if (tiantanColorMode != 1) { ${encoding} }
#else
    ${encoding}
#endif
`);
  }
  return shader;
}

export function attachPointCloudColor(material, { onError = () => {} } = {}) {
  if (!material.newFormat) throw new Error('当前点云格式不支持颜色控制');
  patchVertexShader(material.vertexShader); // Fail before touching a working material.

  const previousCompile = material.onBeforeCompile;
  const previousCacheKey = material.customProgramCacheKey;
  const baseCacheKey = previousCacheKey.call(material);
  const uniforms = {
    tiantanColorMode: { value: 0 },
    tiantanSolidColor: { value: new Vector3(1, 1, 1) },
    tiantanRgbAdjustments: { value: new Vector3(0, 0, 1) },
  };
  let disposed = false;
  let failed = false;

  material.onBeforeCompile = function (shader, renderer) {
    previousCompile.call(this, shader, renderer);
    if (failed) return;
    try {
      shader.vertexShader = patchVertexShader(shader.vertexShader);
      Object.assign(shader.uniforms, uniforms);
    } catch (error) {
      failed = true;
      onError(error);
    }
  };
  material.customProgramCacheKey = () => `${baseCacheKey}|tiantan-point-color-v2`;
  material.needsUpdate = true;

  return {
    update(settings) {
      if (disposed || failed) return;
      const next = normalizePointCloudColor(settings);
      const adjusted = next.brightness !== 0 || next.contrast !== 0 || next.gamma !== 1;
      uniforms.tiantanColorMode.value = next.mode === 'solid' ? 1 : adjusted ? 2 : 0;
      const hex = Number.parseInt(next.solid.slice(1), 16);
      uniforms.tiantanSolidColor.value.set((hex >> 16) / 255, ((hex >> 8) & 255) / 255, (hex & 255) / 255);
      uniforms.tiantanRgbAdjustments.value.set(next.brightness, next.contrast, next.gamma);
      // Do not use the native rgb* setters: they rebuild shader source on every
      // change. The same uniform objects survive EDL/point-size recompilation.
      material.uniformsNeedUpdate = true;
    },
    dispose() {
      if (disposed) return;
      disposed = true;
      material.onBeforeCompile = previousCompile;
      material.customProgramCacheKey = previousCacheKey;
      for (const name of Object.keys(uniforms)) delete material.uniforms[name];
      material.needsUpdate = true;
    },
  };
}
