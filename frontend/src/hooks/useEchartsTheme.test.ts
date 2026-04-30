import { describe, it, expect } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useEchartsTheme } from './useEchartsTheme';
import { useThemeStore } from '@/stores/themeStore';

describe('useEchartsTheme', () => {
  it('returns ems-light when light', () => {
    useThemeStore.setState({ mode: 'light' });
    const { result } = renderHook(() => useEchartsTheme());
    expect(result.current).toBe('ems-light');
  });

  it('returns ems-dark when dark', () => {
    useThemeStore.setState({ mode: 'dark' });
    const { result } = renderHook(() => useEchartsTheme());
    expect(result.current).toBe('ems-dark');
  });

  it('updates when store changes', () => {
    useThemeStore.setState({ mode: 'light' });
    const { result } = renderHook(() => useEchartsTheme());
    act(() => useThemeStore.setState({ mode: 'dark' }));
    expect(result.current).toBe('ems-dark');
  });
});
