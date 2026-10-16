<script setup>
import PotreePointCloudViewer from '@/components/PotreePointCloudViewer.vue';
</script>

<template>
  <main class="pointcloud-page">
    <PotreePointCloudViewer />
  </main>
</template>

<style scoped>
.pointcloud-page { height: 100%; min-height: 0; overflow: hidden; background: var(--ui-bg); }
</style>
