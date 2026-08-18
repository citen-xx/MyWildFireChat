export const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/$/, '');

export function apiUrl(path: string) {
  return `${API_BASE_URL}${path}`;
}

export function websocketUrl(path: string) {
  const configuredUrl = import.meta.env.VITE_WS_URL;
  if (configuredUrl) {
    return configuredUrl;
  }
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${protocol}//${window.location.host}${path}`;
}
