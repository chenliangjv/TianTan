<template>
  <div class="color-picker-container">
    <div class="color-item">
      <span class="label">实体颜色</span>
      <el-color-picker v-model="solidColor" @change="onSolidColorChange" show-alpha />
    </div>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue'
const props = defineProps({ solidColor: { type: String, default: '#ffffff' } })
const emit = defineEmits(['update:solidColor'])
const solidColor = ref(props.solidColor)
watch(() => props.solidColor, value => { solidColor.value = value })
const onSolidColorChange = value => emit('update:solidColor', value || solidColor.value)
</script>

<style scoped>
.color-picker-container { display: flex; flex-direction: column; gap: 9px; margin: 8px 0 9px; padding: 11px 2px 10px; border-top: 1px solid rgba(143,190,210,.12); border-bottom: 1px solid rgba(143,190,210,.12); }.color-item { display: flex; align-items: center; justify-content: space-between; }.label { color: var(--ui-muted); font-size: 12px; }.color-item :deep(.el-color-picker__trigger) { width: 30px; height: 22px; padding: 2px; border-color: rgba(143,190,210,.32); border-radius: 3px; }.color-item :deep(.el-color-picker__color) { border: 0; border-radius: 2px; }
</style>
