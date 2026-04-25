import { create } from 'zustand';
import { persist } from 'zustand/middleware';

export interface ReportTask {
  token: string;
  filename: string;
  status: 'PENDING' | 'RUNNING' | 'READY' | 'FAILED';
  createdAt: string; // ISO string
  error?: string | null;
  bytes?: number | null;
}

interface ReportTasksState {
  tasks: ReportTask[];
  addTask(task: ReportTask): void;
  updateTask(token: string, patch: Partial<Omit<ReportTask, 'token'>>): void;
  removeTask(token: string): void;
  pruneExpired(): void;
}

const TTL_MS = 30 * 60 * 1000; // 30 minutes

export const useReportTasksStore = create<ReportTasksState>()(
  persist(
    (set, get) => ({
      tasks: [],

      addTask(task) {
        set((s) => ({ tasks: [task, ...s.tasks] }));
      },

      updateTask(token, patch) {
        set((s) => ({
          tasks: s.tasks.map((t) => (t.token === token ? { ...t, ...patch } : t)),
        }));
      },

      removeTask(token) {
        set((s) => ({ tasks: s.tasks.filter((t) => t.token !== token) }));
      },

      pruneExpired() {
        const now = Date.now();
        const { tasks } = get();
        const kept = tasks.filter((t) => {
          const age = now - new Date(t.createdAt).getTime();
          return age < TTL_MS;
        });
        if (kept.length !== tasks.length) {
          set({ tasks: kept });
        }
      },
    }),
    { name: 'ems-report-tasks' }
  )
);
