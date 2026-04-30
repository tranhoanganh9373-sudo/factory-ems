import { create } from 'zustand';
import { persist } from 'zustand/middleware';

export type ThemeMode = 'light' | 'dark';

interface ThemeStoreState {
  mode: ThemeMode;
  setMode: (mode: ThemeMode) => void;
  toggle: () => void;
}

function detectInitialMode(): ThemeMode {
  if (typeof window === 'undefined' || !window.matchMedia) return 'light';
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

export const useThemeStore = create<ThemeStoreState>()(
  persist(
    (set, get) => ({
      mode: detectInitialMode(),
      setMode: (mode) => set({ mode }),
      toggle: () => set({ mode: get().mode === 'light' ? 'dark' : 'light' }),
    }),
    { name: 'ems.theme.mode' },
  ),
);
