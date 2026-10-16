import test from 'node:test'
import assert from 'node:assert/strict'
import { ASSET_TYPES, formatBytes, trendOption, distributionOption, capacityOption, tasksOption } from '../src/utils/statistics.js'
import { statisticsFixture } from './fixtures/statistics.js'

test('byte formatting handles zero, invalid values and large files', () => {
  assert.equal(formatBytes(0), '0 B')
  assert.equal(formatBytes(-1), '0 B')
  assert.equal(formatBytes(NaN), '0 B')
  assert.equal(formatBytes(0.2), '0 B')
  assert.equal(formatBytes(1024 ** 3 * 1.5), '1.50 GB')
})
test('trend plots four categories without combining sky and scene counts', () => {
  for (const days of [7, 30, 90]) {
    const s = statisticsFixture(days)
    const option = trendOption(s.trend)
    assert.equal(option.series.length, 4)
    assert.equal(option.xAxis.data.length, days)
    ASSET_TYPES.forEach((type, i) => assert.deepEqual(option.series[i].data, s.trend.items.map(item => item[type.key])))
  }
})
test('distribution preserves category keys for drilldown', () => {
  const s = statisticsFixture()
  const option = distributionOption(s.categories, s.totals.totalAssets)
  assert.deepEqual(option.series[0].data.map(d => d.category), ASSET_TYPES.map(t => t.key))
  assert.equal(option.series[0].data[2].value, 3)
  assert.equal(option.title.text, '51')
})
test('capacity uses actual bytes, including the existing combined Gaussian size only once', () => {
  const s = statisticsFixture()
  const option = capacityOption(s.categories)
  assert.deepEqual(option.series[0].data.map(d => d.value), s.categories.map(c => c.fileSizeBytes))
  assert.equal(option.series[0].label.formatter({ value: 1024 }), '1.00 KB')
})
test('task status chart uses asynchronous task counts only', () => {
  const s = statisticsFixture()
  assert.deepEqual(tasksOption(s.ingestionTasks).series.map(d => d.data[0]), [2, 1, 27, 2])
})
test('empty charts never invent counts or a full pie', () => {
  const s = statisticsFixture(7, true)
  const option = distributionOption(s.categories, 0)
  assert.equal(option.series[0].stillShowZeroSum, false)
  assert.ok(option.series[0].data.every(d => d.value === 0))
  assert.equal(tasksOption(s.ingestionTasks).xAxis.max, 1)
  assert.equal(capacityOption(s.categories).xAxis.minInterval, 1)
})
