<script setup>
defineProps({
  name: { type: String, required: true },
  size: { type: Number, default: 18 },
})

const paths = {
  arrow: ['M4 12h15', 'm13 6 6 6-6 6'],
  model: ['m12 3 9 5v8l-9 5-9-5V8z', 'm3 8 9 5 9-5', 'M12 13v8'],
  points: ['M5 5h.01', 'M12 5h.01', 'M19 5h.01', 'M5 12h.01', 'M12 12h.01', 'M19 12h.01', 'M5 19h.01', 'M12 19h.01', 'M19 19h.01'],
  gaussian: ['m12 2 2.5 7.5L22 12l-7.5 2.5L12 22l-2.5-7.5L2 12l7.5-2.5z'],
  design: ['M5 3h10l4 4v14H5z', 'M14 3v5h5', 'M8 13h8', 'M8 17h8'],
  manage: ['M3 6h7l2 3h9v11H3z', 'M3 6V4h8l2 2'],
  stats: ['M4 20V10h4v10z', 'M10 20V4h4v16z', 'M16 20v-8h4v8z'],
  help: ['M9 9a3 3 0 1 1 5.8 1c-.7 1.4-2.8 1.6-2.8 4', 'M12 18h.01', 'M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0'],
  spark: ['m12 3 1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8z', 'm19 15 .8 2.2L22 18l-2.2.8L19 21l-.8-2.2L16 18l2.2-.8z'],
  document: ['M5 3h10l4 4v14H5z', 'M14 3v5h5', 'M8 13h8', 'M8 17h6'],
  grid: ['M4 4h6v6H4z', 'M14 4h6v6h-6z', 'M4 14h6v6H4z', 'M14 14h6v6h-6z'],
}
</script>

<template>
  <svg
    class="icon-mark"
    :width="size"
    :height="size"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="1.5"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    <path v-for="(path, index) in paths[name]" :key="index" :d="path" />
  </svg>
</template>
