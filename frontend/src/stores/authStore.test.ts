import { describe, it, expect, beforeEach } from 'vitest';
import { useAuthStore } from './authStore';

describe('authStore', () => {
  beforeEach(() => useAuthStore.getState().clear());
  it('setAuth stores token and user', () => {
    useAuthStore.getState().setAuth({
      accessToken: 'tok',
      user: { id: 1, username: 'admin', roles: ['ADMIN'] },
      expiresIn: 900,
    });
    expect(useAuthStore.getState().accessToken).toBe('tok');
    expect(useAuthStore.getState().hasRole('ADMIN')).toBe(true);
    expect(useAuthStore.getState().hasRole('VIEWER')).toBe(false);
  });
});
