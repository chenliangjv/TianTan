import config from '@/config/index.js';

export const designImageUrl = path => `${config.API_BASE_URL}${path}`;
export const formatImageSize = bytes => Number(bytes) >= 1024 * 1024
  ? `${(bytes / 1024 / 1024).toFixed(1)} MB` : `${Math.max(1, Math.round(bytes / 1024))} KB`;

async function parseResponse(response) {
  const text = await response.text();
  let body;
  try { body = JSON.parse(text); } catch { body = null; }
  if (!response.ok) throw new Error(body?.message || `请求失败（${response.status}），请确认数据服务已启动并更新。`);
  return body;
}

export async function listDesignImages(signal) {
  const data = await parseResponse(await fetch(designImageUrl('/api/data/design-images/list'), { signal }));
  if (!Array.isArray(data)) throw new Error('图像列表响应异常，请检查数据服务。');
  return data;
}

export async function deleteDesignImage(id) {
  await parseResponse(await fetch(designImageUrl(`/api/data/design-images/${encodeURIComponent(id)}`), { method: 'DELETE' }));
}

export function uploadDesignImages(files, onProgress, signal) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    const abort = () => xhr.abort();
    const done = () => signal?.removeEventListener('abort', abort);
    if (signal?.aborted) return reject(new DOMException('操作已取消', 'AbortError'));
    xhr.open('POST', designImageUrl('/api/data/design-images/upload'));
    xhr.upload.onprogress = event => { if (event.lengthComputable) onProgress?.(Math.round(event.loaded / event.total * 100)); };
    xhr.onload = () => {
      done();
      let data;
      try { data = JSON.parse(xhr.responseText); } catch { /* Report invalid responses below. */ }
      if (xhr.status >= 200 && xhr.status < 300 && Array.isArray(data?.results)) resolve(data.results);
      else reject(new Error(data?.message || `图像入库失败（${xhr.status}），请检查数据服务。`));
    };
    xhr.onerror = () => { done(); reject(new Error('网络错误，图片上传失败')); };
    xhr.onabort = () => { done(); reject(new DOMException('操作已取消', 'AbortError')); };
    signal?.addEventListener('abort', abort, { once: true });
    const form = new FormData();
    files.forEach(file => form.append('files', file, file.name));
    xhr.send(form);
  });
}
