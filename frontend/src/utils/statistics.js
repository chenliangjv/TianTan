export const ASSET_TYPES = [
  { key: 'MODEL', label: '三维模型', color: '#69a9ff', route: '/lod2', hint: '建筑模型 / LOD' },
  { key: 'POINT_CLOUD', label: '点云', color: '#55d6e6', route: '/pointcloud', hint: '扫描成果 / POTREE' },
  { key: 'GAUSSIAN', label: '高斯场景', color: '#ae98f6', route: '/gaussian', hint: '实景重建 / RAD + PLY' },
  { key: 'DESIGN', label: '设计图', color: '#f2b35b', route: '/design', hint: '图像归档 / PNG · JPEG' },
]

export const formatNumber = value => Number(value || 0).toLocaleString('zh-CN')
export function formatBytes(bytes) {
  const value = Number(bytes || 0)
  if (!Number.isFinite(value) || value <= 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB', 'PB']
  const index = Math.max(0, Math.min(Math.floor(Math.log(value) / Math.log(1024)), units.length - 1))
  return `${(value / 1024 ** index).toFixed(index ? 2 : 0)} ${units[index]}`
}

const axis = { axisLine: { show: false }, axisTick: { show: false }, axisLabel: { color: '#8fa8b5', fontSize: 11 } }
const splitLine = { lineStyle: { color: 'rgba(143,190,210,.09)', type: 'dashed' } }
const base = () => ({
  animationDuration: 350,
  textStyle: { fontFamily: 'Microsoft YaHei, sans-serif', color: '#8fa8b5' },
  tooltip: { trigger: 'item', confine: true, backgroundColor: '#142b3c', borderColor: '#355467', textStyle: { color: '#e8f1f5', fontSize: 12 } },
  aria: { enabled: true },
})

export function trendOption(trend) {
  const items = trend.items
  return {
    ...base(),
    legend: { bottom: 0, itemWidth: 9, itemHeight: 9, icon: 'roundRect', textStyle: { color: '#a6bac5', fontSize: 11 }, itemGap: 18 },
    grid: { left: 48, right: 20, top: 30, bottom: 60 },
    tooltip: { ...base().tooltip, trigger: 'axis', axisPointer: { type: 'shadow' } },
    xAxis: { ...axis, type: 'category', data: items.map(item => item.date), axisLabel: { ...axis.axisLabel, formatter: value => value.slice(5), hideOverlap: true } },
    yAxis: { ...axis, type: 'value', minInterval: 1, splitLine, name: '资产 / 项', nameTextStyle: { color: '#65818f', fontSize: 10 } },
    series: ASSET_TYPES.map(type => ({
      name: type.label, type: 'bar', stack: 'assets', barMaxWidth: 22,
      emphasis: { focus: 'series' }, itemStyle: { color: type.color },
      data: items.map(item => item[type.key]),
    })),
  }
}

export function distributionOption(categories, total) {
  return {
    ...base(),
    title: { text: formatNumber(total), subtext: '归档资产 / 项', left: 'center', top: '36%', textStyle: { color: '#e8f1f5', fontSize: 30, fontFamily: 'Consolas, monospace' }, subtextStyle: { color: '#8fa8b5', fontSize: 11 } },
    series: [{ type: 'pie', radius: ['66%', '82%'], center: ['50%', '48%'],
      label: { show: false }, emphasis: { scaleSize: 5 }, stillShowZeroSum: false,
      itemStyle: { borderColor: '#0d1f2d', borderWidth: 4, borderRadius: 5 },
      data: ASSET_TYPES.map(type => ({ name: type.label, value: categories.find(c => c.key === type.key)?.count || 0, category: type.key, itemStyle: { color: type.color } })),
    }],
  }
}

export function capacityOption(categories) {
  return {
    ...base(),
    grid: { left: 72, right: 98, top: 12, bottom: 24 },
    xAxis: { ...axis, type: 'value', minInterval: 1, splitLine, axisLabel: { ...axis.axisLabel, formatter: formatBytes, hideOverlap: true } },
    yAxis: { ...axis, type: 'category', inverse: true, data: ASSET_TYPES.map(type => type.label) },
    tooltip: { ...base().tooltip, valueFormatter: formatBytes },
    series: [{ type: 'bar', barWidth: 14, showBackground: true,
      backgroundStyle: { color: 'rgba(143,190,210,.05)', borderRadius: 3 },
      label: { show: true, position: 'right', color: '#d0dfe6', fontFamily: 'Consolas, monospace', fontSize: 11, formatter: p => formatBytes(p.value) },
      data: ASSET_TYPES.map(type => ({ name: type.label, value: categories.find(c => c.key === type.key)?.fileSizeBytes || 0, itemStyle: { color: type.color, borderRadius: 3 } })),
    }],
  }
}

export const TASK_STATES = [
  { key: 'pending', label: '排队中', color: '#7c95a6' },
  { key: 'running', label: '运行中', color: '#55d6e6' },
  { key: 'success', label: '已完成', color: '#56d69a' },
  { key: 'failed', label: '失败', color: '#ef767a' },
]

export function tasksOption(tasks) {
  return {
    ...base(), grid: { left: 12, right: 12, top: 5, bottom: 5 },
    xAxis: { type: 'value', show: false, max: Math.max(1, tasks.total) },
    yAxis: { type: 'category', show: false, data: ['任务'] },
    series: TASK_STATES.map(state => ({ name: state.label, type: 'bar', stack: 'tasks', barWidth: 22,
      itemStyle: { color: state.color }, data: [tasks[state.key]],
    })),
  }
}
