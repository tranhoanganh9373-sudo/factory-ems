import { create } from 'zustand';

interface AppState {
  currentOrgNodeId: number | null;
  setCurrentOrgNodeId(id: number | null): void;
}

export const useAppStore = create<AppState>((set) => ({
  currentOrgNodeId: null,
  setCurrentOrgNodeId: (id) => set({ currentOrgNodeId: id }),
}));
