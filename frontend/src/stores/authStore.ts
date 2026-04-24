import { create } from 'zustand';
import { persist } from 'zustand/middleware';

export interface UserInfo {
  id: number;
  username: string;
  displayName?: string;
  roles: string[];
}

interface AuthState {
  accessToken: string | null;
  user: UserInfo | null;
  expiresAt: number | null;
  setAuth(p: { accessToken: string; user: UserInfo; expiresIn: number }): void;
  clear(): void;
  hasRole(role: string): boolean;
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set, get) => ({
      accessToken: null,
      user: null,
      expiresAt: null,
      setAuth: ({ accessToken, user, expiresIn }) =>
        set({ accessToken, user, expiresAt: Date.now() + expiresIn * 1000 }),
      clear: () => set({ accessToken: null, user: null, expiresAt: null }),
      hasRole: (role) => !!get().user?.roles.includes(role),
    }),
    { name: 'ems-auth', partialize: (s) => ({ user: s.user }) } // 只持久化 user，token 留内存
  )
);
