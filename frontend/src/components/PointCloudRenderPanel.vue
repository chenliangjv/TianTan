<template>
  <section class="pointcloud-panel render-panel" aria-label="点云场景控制">
    <header class="panel-header">
      <div>
        <span class="panel-eyebrow">SCENE CONTROL</span>
        <strong>场景控制</strong>
      </div>
      <i class="panel-live-dot"></i>
    </header>

    <div class="panel-content">
      <label class="control-row">
        <span>点预算</span>
        <strong>{{ (pointBudget / 1_000_000).toFixed(1) }} M</strong>
      </label>
      <el-slider :model-value="pointBudget" :min="500_000" :max="12_000_000" :step="500_000" :show-tooltip="false" @update:model-value="emit('update:pointBudget', $event)" />

      <label class="control-row">
        <span>点大小</span>
        <strong>{{ pointSize.toFixed(1) }} px</strong>
      </label>
      <el-slider :model-value="pointSize" :min="0.4" :max="2.8" :step="0.1" :show-tooltip="false" @update:model-value="emit('update:pointSize', $event)" />

      <div class="switch-row"><span>EDL 细节增强</span><el-switch :model-value="edlEnabled" @update:model-value="emit('update:edlEnabled', $event)" /></div>
      <div class="switch-row"><span>节点包围盒</span><el-switch :model-value="showBoundingBox" @update:model-value="emit('update:showBoundingBox', $event)" /></div>

      <section class="color-controls" aria-label="点云颜色控制">
        <div class="color-heading">
          <strong>点云颜色</strong>
          <el-button link size="small" :disabled="colorDisabled" @click="emit('reset-color')">恢复默认</el-button>
        </div>
        <el-radio-group class="color-mode" :model-value="colorSettings.mode" :disabled="colorDisabled" aria-label="点云颜色模式" @update:model-value="setColor('mode', $event)">
          <el-radio-button value="rgb">原始 RGB</el-radio-button>
          <el-radio-button value="solid">单色显示</el-radio-button>
        </el-radio-group>
        <p v-if="colorHint" class="color-hint" role="status">{{ colorHint }}</p>

        <template v-if="colorSettings.mode === 'solid'">
          <div class="solid-color-row">
            <div><span>基础颜色</span><strong>{{ colorSettings.solid.toUpperCase() }}</strong></div>
            <el-color-picker :model-value="colorSettings.solid" :disabled="colorDisabled" :show-alpha="false" :clearable="false" color-format="hex" append-to=".potree-viewer" aria-label="选择点云单色" @update:model-value="setSolidColor" @active-change="setSolidColor" />
          </div>
          <div class="color-presets" aria-label="点云预设颜色">
            <button v-for="preset in colorPresets" :key="preset.value" type="button" :style="{ '--swatch-color': preset.value }" :class="{ 'is-selected': colorSettings.solid === preset.value }" :disabled="colorDisabled" :aria-label="preset.label" :aria-pressed="colorSettings.solid === preset.value" :title="preset.label" @click="setSolidColor(preset.value)"><i></i></button>
          </div>
        </template>
        <div v-for="control in colorAdjustments" :key="control.key" class="color-adjustment">
          <div class="control-row"><span>{{ control.label }}</span><strong>{{ colorSettings[control.key].toFixed(2) }}</strong></div>
          <el-slider :model-value="colorSettings[control.key]" v-bind="POINT_CLOUD_COLOR_LIMITS[control.key]" :disabled="colorDisabled" :aria-label="control.label" :show-tooltip="false" @update:model-value="setColor(control.key, $event)" />
        </div>
        <p class="color-note">{{ colorSettings.mode === 'solid' ? '调节作用于基础颜色，不修改原始点云数据。' : '默认参数保持原始 RGB。' }}两种模式共用调节参数，恢复默认不改变视角。</p>
      </section>
    </div>

    <footer class="panel-status"><i></i><span>GPU RENDER SETTINGS READY</span></footer>
  </section>
</template>

<script setup>
import { DEFAULT_POINT_CLOUD_COLOR, POINT_CLOUD_COLOR_LIMITS } from '@/utils/pointCloudColor.js';

defineProps({
  pointBudget: { type: Number, default: 2_000_000 },
  pointSize: { type: Number, default: 1.1 },
  edlEnabled: { type: Boolean, default: false },
  showBoundingBox: { type: Boolean, default: false },
  colorSettings: { type: Object, default: () => ({ ...DEFAULT_POINT_CLOUD_COLOR }) },
  colorDisabled: { type: Boolean, default: false },
  colorHint: { type: String, default: '' },
});

const emit = defineEmits([
  'update:pointBudget',
  'update:pointSize',
  'update:edlEnabled',
  'update:showBoundingBox',
  'update:colorSettings',
  'reset-color',
]);

const colorAdjustments = [
  { key: 'brightness', label: '亮度' },
  { key: 'contrast', label: '对比度' },
  { key: 'gamma', label: 'Gamma' },
];
const colorPresets = [
  { value: '#ffffff', label: '白色' },
  { value: '#55d6e6', label: '青色' },
  { value: '#70a5ff', label: '蓝色' },
  { value: '#56d69a', label: '绿色' },
  { value: '#f3c969', label: '金色' },
  { value: '#ef767a', label: '珊瑚色' },
];
const setColor = (key, value) => emit('update:colorSettings', { [key]: value });
const setSolidColor = value => {
  if (/^#[\da-f]{6}$/i.test(value)) setColor('solid', value);
};
</script>

<style scoped>
.pointcloud-panel { width: 100%; height: 100%; box-sizing: border-box; display: flex; flex-direction: column; overflow: hidden; color: var(--ui-text); border: 1px solid rgba(85,214,230,.3); background: rgba(6,20,31,.96); box-shadow: 18px 0 42px rgba(0,0,0,.28), 0 0 24px rgba(85,214,230,.08); backdrop-filter: blur(16px); }
.panel-header { display: flex; align-items: center; justify-content: space-between; min-height: 64px; padding: 0 14px 0 16px; border-bottom: 1px solid var(--ui-border); background: linear-gradient(90deg, rgba(85,214,230,.09), transparent 62%); }.panel-eyebrow { display: block; margin-bottom: 4px; color: var(--ui-cyan); font: 9px Consolas, monospace; letter-spacing: 1.2px; }.panel-header strong { color: #e5f4f6; font-size: 14px; font-weight: 600; }.panel-live-dot { width: 7px; height: 7px; border-radius: 50%; background: var(--ui-cyan); box-shadow: 0 0 10px var(--ui-cyan); }
.panel-content { flex: 1; min-height: 0; overflow-y: auto; padding: 14px 15px 15px; }.panel-content::-webkit-scrollbar { width: 4px; }.panel-content::-webkit-scrollbar-thumb { background: rgba(143,190,210,.34); }
.control-row, .switch-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; color: #93acb6; font-size: 11px; }.control-row strong { color: #d4ecf0; font: 600 11px Consolas, monospace; }.render-panel :deep(.el-slider) { margin: 7px 0 18px; }.render-panel :deep(.el-slider__runway) { height: 3px; background: rgba(143,190,210,.16); }.render-panel :deep(.el-slider__bar) { height: 3px; background: var(--ui-cyan); }.render-panel :deep(.el-slider__button) { width: 11px; height: 11px; border-color: var(--ui-cyan); background: #102733; }
.control-block { display: none; }.switch-row { min-height: 37px; padding: 0 0 0 2px; border-top: 1px solid rgba(143,190,210,.12); }.render-panel :deep(.el-switch) { --el-switch-on-color: var(--ui-cyan); --el-switch-off-color: rgba(143,190,210,.25); }
.panel-status { display: flex; align-items: center; gap: 7px; min-height: 34px; padding: 0 15px; border-top: 1px solid rgba(143,190,210,.14); color: #66828e; font: 9px Consolas, monospace; letter-spacing: .8px; }.panel-status i { width: 5px; height: 5px; border-radius: 50%; background: var(--ui-success); box-shadow: 0 0 7px rgba(86,214,154,.8); }
.color-controls { margin-top: 14px; padding-top: 15px; border-top: 1px solid rgba(143,190,210,.2); }
.color-heading { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }.color-heading > strong { color: #d4ecf0; font-size: 12px; font-weight: 600; }.color-heading :deep(.el-button) { color: var(--ui-cyan); font-size: 11px; }.color-heading :deep(.el-button.is-disabled) { color: #526a75; }
.color-mode { display: flex; width: 100%; margin-bottom: 20px; --el-color-primary: #55d6e6; }.color-mode :deep(.el-radio-button) { flex: 1; }.color-mode :deep(.el-radio-button__inner) { width: 100%; padding: 9px 12px; border-color: rgba(143,190,210,.22); color: #93acb6; background: rgba(143,190,210,.06); box-shadow: none; font-size: 11px; }.color-mode :deep(.el-radio-button.is-active .el-radio-button__inner) { color: #ccf8fc; border-color: rgba(85,214,230,.5); background: rgba(85,214,230,.16); }.color-mode :deep(.el-radio-button.is-disabled .el-radio-button__inner) { color: #526a75; background: rgba(143,190,210,.03); }
.color-hint, .color-note { margin: 0 0 14px; color: #7895a0; font-size: 11px; line-height: 1.65; }.color-hint { color: #dfbc78; }.color-note { margin-bottom: 0; }.solid-color-row { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }.solid-color-row span { display: block; margin-bottom: 5px; color: #93acb6; font-size: 11px; }.solid-color-row strong { color: #d4ecf0; font: 11px Consolas, monospace; }.solid-color-row :deep(.el-color-picker__trigger) { border-color: rgba(143,190,210,.3); }
.color-presets { display: flex; gap: 8px; margin-bottom: 14px; }.color-presets button { display: grid; place-items: center; flex: 1; height: 30px; padding: 4px; border: 1px solid rgba(143,190,210,.2); border-radius: 4px; background: transparent; cursor: pointer; }.color-presets button:hover, .color-presets button.is-selected { border-color: var(--swatch-color); background: rgba(143,190,210,.1); }.color-presets button:disabled { cursor: not-allowed; opacity: .4; }.color-presets i { display: block; width: 100%; height: 100%; border-radius: 2px; background: var(--swatch-color); }
</style>
