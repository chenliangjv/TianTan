<template>
  <section class="background-color-panel" aria-label="场景背景颜色">
    <div class="panel-heading">
      <div>
        <span>SCENE APPEARANCE</span>
        <strong>背景颜色</strong>
      </div>
      <div class="color-value" :style="{ '--current-color': modelValue }">
        <i></i>
        <code>{{ modelValue.toUpperCase() }}</code>
      </div>
    </div>

    <div class="color-content">
      <div class="color-picker-row">
        <span>自定义颜色</span>
        <el-color-picker
          :model-value="modelValue"
          color-format="hex"
          :show-alpha="false"
          :clearable="false"
          @update:model-value="$emit('update:modelValue', $event)"
        />
      </div>

      <div class="preset-label">预设色板</div>
      <div class="preset-list">
        <button
          v-for="color in presets"
          :key="color.value"
          class="preset-color"
          :class="{ 'is-active': modelValue.toLowerCase() === color.value }"
          type="button"
          :style="{ '--swatch-color': color.value }"
          :title="color.label"
          :aria-label="`设为${color.label}`"
          @click="$emit('update:modelValue', color.value)"
        ></button>
      </div>
    </div>
  </section>
</template>

<script setup>
defineProps({
  modelValue: { type: String, default: '#07131f' },
})

defineEmits(['update:modelValue'])

const presets = [
  { value: '#07131f', label: '深海蓝' },
  { value: '#0d2430', label: '靛青蓝' },
  { value: '#17212b', label: '石墨灰' },
  { value: '#1b2633', label: '夜幕蓝' },
  { value: '#26323c', label: '雾灰色' },
  { value: '#101010', label: '曜石黑' },
]
</script>

<style scoped>
.background-color-panel { width: 280px; overflow: hidden; border: 1px solid rgba(85,214,230,.3); border-radius: 5px; color: var(--ui-text); background: rgba(6,20,31,.97); box-shadow: 0 16px 34px rgba(0,0,0,.36), 0 0 22px rgba(85,214,230,.08); backdrop-filter: blur(16px); }.panel-heading { display: flex; align-items: center; justify-content: space-between; min-height: 58px; padding: 0 14px; border-bottom: 1px solid rgba(143,190,210,.15); background: linear-gradient(90deg, rgba(85,214,230,.1), transparent 70%); }.panel-heading span { display: block; color: var(--ui-cyan); font: 9px Consolas, monospace; letter-spacing: 1.15px; }.panel-heading strong { display: block; margin-top: 4px; color: #e5f4f6; font-size: 13px; font-weight: 600; }.color-value { display: inline-flex; align-items: center; gap: 6px; color: #8ca6b0; }.color-value i { width: 14px; height: 14px; border: 1px solid rgba(229,244,246,.48); border-radius: 2px; background: var(--current-color); box-shadow: inset 0 0 0 2px rgba(0,0,0,.14); }.color-value code { font: 10px Consolas, monospace; }.color-content { padding: 12px 14px 14px; }.color-picker-row { display: flex; align-items: center; justify-content: space-between; min-height: 34px; color: #98b1bb; font-size: 12px; }.color-picker-row :deep(.el-color-picker__trigger) { width: 42px; height: 26px; padding: 3px; border-color: rgba(143,190,210,.32); border-radius: 3px; background: rgba(4,16,25,.72); }.color-picker-row :deep(.el-color-picker__color) { border: 0; border-radius: 2px; }.preset-label { margin: 12px 0 8px; padding-top: 10px; border-top: 1px solid rgba(143,190,210,.12); color: #6f8d99; font: 10px Consolas, monospace; letter-spacing: .85px; }.preset-list { display: grid; grid-template-columns: repeat(6, 1fr); gap: 7px; }.preset-color { position: relative; height: 24px; border: 1px solid rgba(143,190,210,.28); border-radius: 3px; background: var(--swatch-color); cursor: pointer; transition: transform .16s ease, border-color .16s ease; }.preset-color::after { position: absolute; inset: 3px; border: 1px solid transparent; border-radius: 1px; content: ''; }.preset-color:hover { border-color: var(--ui-cyan); transform: translateY(-1px); }.preset-color.is-active { border-color: var(--ui-cyan); box-shadow: 0 0 9px rgba(85,214,230,.36); }.preset-color.is-active::after { border-color: rgba(229,244,246,.75); }
</style>
