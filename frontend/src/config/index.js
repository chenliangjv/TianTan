const apiBaseUrl = import.meta.env.VITE_API_BASE_URL || '';
const wsUrl = import.meta.env.VITE_WS_URL || (() => {
  if (typeof window === 'undefined') return 'ws://localhost:8080';
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${protocol}//${window.location.host}`;
})();

export default { API_BASE_URL: apiBaseUrl, WS_URL: wsUrl };
