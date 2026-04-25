import { apiClient } from './client';
import { UserInfo } from '@/stores/authStore';

interface LoginResp {
  accessToken: string;
  expiresIn: number;
  user: UserInfo;
}

export const authApi = {
  login: (username: string, password: string) =>
    apiClient
      .post<LoginResp>('/auth/login', { username, password })
      .then((r) => r.data as unknown as LoginResp),
  logout: () => apiClient.post('/auth/logout'),
  me: () => apiClient.get<UserInfo>('/auth/me').then((r) => r.data as unknown as UserInfo),
  changePassword: (oldPassword: string, newPassword: string) =>
    apiClient.put('/users/me/password', { oldPassword, newPassword }),
};
