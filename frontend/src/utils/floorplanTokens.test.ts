import { describe, it, expect } from 'vitest';
import { floorplanTokens } from './floorplanTokens';

describe('floorplanTokens', () => {
  it('returns light palette', () => {
    const t = floorplanTokens('light');
    expect(t.bgFill).toBe('#F4F5F7');
    expect(t.deviceStroke).toBe('#007D8A');
    expect(t.deviceStrokeAlarm).toBe('#C8201F');
  });

  it('returns dark palette', () => {
    const t = floorplanTokens('dark');
    expect(t.bgFill).toBe('#0A1018');
    expect(t.deviceStroke).toBe('#00C2CC');
    expect(t.deviceStrokeAlarm).toBe('#FF6464');
  });
});
