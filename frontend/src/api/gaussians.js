import config from '@/config/index.js';

export const gaussianUrl = path => `${config.API_BASE_URL}${path}`;
export async function gaussianRequest(path, options) {
  const response = await fetch(gaussianUrl(path), options);
  if (!response.ok) {
    const text = await response.text();
    let message = text;
    try { message = JSON.parse(text).message || text; } catch { /* Plain HTTP error. */ }
    throw new Error(message || `请求失败：${response.status}`);
  }
  return response.status === 204 ? null : response.json();
}
export const listGaussians = signal => gaussianRequest('/api/data/gaussians/list', { signal });
export const formatBytes = bytes => {
  if (!Number.isFinite(Number(bytes))) return '—';
  let value = Number(bytes), i = 0;
  const units = ['B', 'KiB', 'MiB', 'GiB'];
  while (value >= 1024 && i < units.length - 1) { value /= 1024; i++; }
  return `${value.toFixed(i ? 1 : 0)} ${units[i]}`;
};
