import { describe, it, expect } from 'vitest';
import { densityForRoute } from './useDensity';

describe('densityForRoute', () => {
  it.each([
    '/dashboard',
    '/dashboard/overview',
    '/collector',
    '/floorplan/editor',
    '/alarms/history',
  ])('returns small for monitoring prefix %s', (p) => {
    expect(densityForRoute(p)).toBe('small');
  });

  it.each([
    '/admin/users',
    '/meters',
    '/orgtree',
    '/tariff',
    '/report/daily',
    '/login',
    '/profile',
  ])('returns middle for non-monitoring %s', (p) => {
    expect(densityForRoute(p)).toBe('middle');
  });
});
