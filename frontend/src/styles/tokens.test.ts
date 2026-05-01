import { describe, it, expect } from 'vitest';
import { theme } from 'antd';
import { buildAntdTheme } from './tokens';

describe('buildAntdTheme', () => {
  it('returns light algorithm with petrol primary', () => {
    const cfg = buildAntdTheme('light');
    expect(cfg.algorithm).toBe(theme.defaultAlgorithm);
    expect(cfg.token?.colorPrimary).toBe('#007D8A');
  });

  it('returns dark algorithm with cyan primary', () => {
    const cfg = buildAntdTheme('dark');
    expect(cfg.algorithm).toBe(theme.darkAlgorithm);
    expect(cfg.token?.colorPrimary).toBe('#00C2CC');
  });

  it('keeps brand-locked layout header colors', () => {
    expect(buildAntdTheme('light').components?.Layout?.headerBg).toBe('#0E1A2B');
    expect(buildAntdTheme('dark').components?.Layout?.headerBg).toBe('#06090F');
  });
});
