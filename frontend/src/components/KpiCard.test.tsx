import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { KpiCard } from './KpiCard';

describe('KpiCard', () => {
  it('renders label, value, unit, delta', () => {
    render(<KpiCard label="本月用电" value="12,345" unit="kWh" delta={-3.2} />);
    expect(screen.getByText('本月用电')).toBeInTheDocument();
    expect(screen.getByText('12,345')).toBeInTheDocument();
    expect(screen.getByText('kWh')).toBeInTheDocument();
    expect(screen.getByText(/3\.2%/)).toBeInTheDocument();
  });

  it('omits delta when not provided', () => {
    render(<KpiCard label="水耗" value="1,200" unit="m³" />);
    expect(screen.queryByText(/%/)).toBeNull();
  });
});
