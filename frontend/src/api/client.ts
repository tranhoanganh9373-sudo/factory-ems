import axios, { AxiosError, AxiosRequestConfig } from 'axios';
import { message } from 'antd';
import { useAuthStore } from '@/stores/authStore';

export const apiClient = axios.create({
  baseURL: '/api/v1',
  timeout: 15_000,
  withCredentials: true, // refresh cookie
});

let isRefreshing = false;
let pendingQueue: Array<(t: string | null) => void> = [];

apiClient.interceptors.request.use((config) => {
  const token = useAuthStore.getState().accessToken;
  if (token && config.headers) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

apiClient.interceptors.response.use(
  (res) => {
    const body = res.data;
    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code === 0) return { ...res, data: body.data };
      throw new BizError(body.code, body.message || 'unknown', body.traceId);
    }
    return res;
  },
  async (err: AxiosError<{ code?: number; message?: string; traceId?: string }>) => {
    const original = err.config as AxiosRequestConfig & { _retry?: boolean };
    const status = err.response?.status;
    const body = err.response?.data;
    const code = body?.code;

    // token 过期 → 刷新（排除登录/刷新接口本身，避免错误密码触发刷新流）
    const url = original.url || '';
    const isAuthEndpoint = url.includes('/auth/login') || url.includes('/auth/refresh');
    if (status === 401 && code === 40001 && !original._retry && !isAuthEndpoint) {
      original._retry = true;
      if (isRefreshing) {
        return new Promise((resolve, reject) => {
          pendingQueue.push((token) => {
            if (token) {
              (original.headers as Record<string, string>).Authorization = `Bearer ${token}`;
              resolve(apiClient(original));
            } else {
              reject(err);
            }
          });
        });
      }
      isRefreshing = true;
      try {
        const r = await axios.post('/api/v1/auth/refresh', null, { withCredentials: true });
        const newToken = r.data.data.accessToken;
        useAuthStore.getState().setAuth({
          accessToken: newToken,
          user: r.data.data.user,
          expiresIn: r.data.data.expiresIn,
        });
        pendingQueue.forEach((fn) => fn(newToken));
        pendingQueue = [];
        (original.headers as Record<string, string>).Authorization = `Bearer ${newToken}`;
        return apiClient(original);
      } catch {
        pendingQueue.forEach((fn) => fn(null));
        pendingQueue = [];
        useAuthStore.getState().clear();
        window.location.href = '/login';
        return Promise.reject(err);
      } finally {
        isRefreshing = false;
      }
    }

    // 403
    if (status === 403) {
      message.error('无权访问');
      return Promise.reject(err);
    }
    // 5xx
    if (status && status >= 500) {
      message.error(`服务器错误: ${body?.message ?? 'internal'} (${body?.traceId ?? '-'})`);
      return Promise.reject(err);
    }
    // 业务错误
    if (code !== undefined) {
      message.error(body?.message ?? '操作失败');
    }
    return Promise.reject(err);
  }
);

export class BizError extends Error {
  constructor(
    public code: number,
    message: string,
    public traceId?: string
  ) {
    super(message);
  }
}
