import { describe, it, expect, beforeEach } from 'vitest';
import { act } from '@testing-library/react';
import { useThemeStore } from './themeStore';

describe('themeStore', () => {
  beforeEach(() => {
    localStorage.clear();
    useThemeStore.setState({ mode: 'light' });
  });

  it('default mode is light when no preference', () => {
    expect(useThemeStore.getState().mode).toBe('light');
  });

  it('toggle flips between light and dark', () => {
    act(() => useThemeStore.getState().toggle());
    expect(useThemeStore.getState().mode).toBe('dark');
    act(() => useThemeStore.getState().toggle());
    expect(useThemeStore.getState().mode).toBe('light');
  });

  it('setMode writes through and persists to localStorage', () => {
    act(() => useThemeStore.getState().setMode('dark'));
    expect(useThemeStore.getState().mode).toBe('dark');
    expect(localStorage.getItem('ems.theme.mode')).toContain('dark');
  });
});
