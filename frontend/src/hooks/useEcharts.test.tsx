import { describe, it, expect } from 'vitest';
import { renderHook } from '@testing-library/react';
import { useEcharts } from './useEcharts';
import { useThemeStore } from '@/stores/themeStore';

describe('useEcharts', () => {
  it('returns a div ref under light theme', () => {
    useThemeStore.setState({ mode: 'light' });
    const { result } = renderHook(() => useEcharts({}));
    expect(result.current).toBeDefined();
  });
});
