import { describe, it, expect } from 'vitest';
import { formatDateTime, formatDate } from './format';

describe('format', () => {
  it('formatDateTime handles undefined', () => {
    expect(formatDateTime()).toBe('-');
  });
  it('formatDate formats ISO', () => {
    expect(formatDate('2026-04-24T10:00:00+08:00')).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  });
});
