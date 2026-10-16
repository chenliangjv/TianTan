export function statisticsFixture(days = 30, empty = false) {
  const multiplier = empty ? 0 : 1
  const counts = [18, 6, 3, 24].map(n => n * multiplier)
  const sizes = [18468359373, 30172145254, 2023818684, 578813952].map(n => n * multiplier)
  const keys = ['MODEL', 'POINT_CLOUD', 'GAUSSIAN', 'DESIGN']
  const labels = ['三维模型', '点云', '高斯场景', '设计图']
  const items = Array.from({ length: days }, (_, index) => {
    const date = new Date(Date.UTC(2026, 8, 22 - days + 1 + index)).toISOString().slice(0, 10)
    const age = days - index - 1
    return { date, MODEL: age < 18 ? multiplier : 0, POINT_CLOUD: age < 18 && age % 3 === 0 ? multiplier : 0, GAUSSIAN: age < 3 ? multiplier : 0, DESIGN: age < 12 ? 2 * multiplier : 0 }
  })
  return {
    categories: keys.map((key, index) => ({ key, label: labels[index], count: counts[index], fileSizeBytes: sizes[index] })),
    models: { totalModels: counts[0], octreeInfoCount: 32 * multiplier, octreeNodes: 8624 * multiplier, nodeFiles: 15289 * multiplier, fileSizeBytes: sizes[0] },
    pointClouds: { totalPointCloudModels: counts[1], datasets: counts[1], format: 'POTREE_2', totalPoints: 845726109 * multiplier, fileSizeBytes: sizes[1] },
    gaussians: { totalScenes: counts[2], sourceSplatCount: 7109842 * multiplier, totalSplatCount: 9830143 * multiplier, chunkCount: 158 * multiplier, fileSizeBytes: sizes[2], sourceCountKnownScenes: counts[2], scenesWithSky: 2 * multiplier, mainBytes: 2021650100 * multiplier, skyBytes: 2168584 * multiplier },
    designs: { totalImages: counts[3], fileSizeBytes: sizes[3], formats: { PNG: 20 * multiplier, JPEG: 4 * multiplier } },
    ingestionTasks: { total: 32 * multiplier, pending: 2 * multiplier, running: multiplier, success: 27 * multiplier, failed: 2 * multiplier },
    diseases: { total: 135 * multiplier, active: 128 * multiplier },
    totals: { total3DModels: counts[0], totalPointCloudModels: counts[1], totalGaussianScenes: counts[2], totalDesignImages: counts[3], totalAssets: counts.reduce((a, b) => a + b, 0), totalBytesAll: sizes.reduce((a, b) => a + b, 0) },
    trend: { days, startDate: items[0].date, endDate: items.at(-1).date, items },
    updatedAt: '2026-09-22T10:24:36+08:00', timezone: 'Asia/Shanghai',
  }
}
