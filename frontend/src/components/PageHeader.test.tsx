import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { PageHeader } from './PageHeader';

describe('PageHeader', () => {
  it('shows title and optional extra', () => {
    render(<PageHeader title="报警历史" extra={<button>导出</button>} />);
    expect(screen.getByText('报警历史')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '导出' })).toBeInTheDocument();
  });

  it('renders subtitle when provided', () => {
    render(<PageHeader title="账单列表" subtitle="本月共 12 条" />);
    expect(screen.getByText('本月共 12 条')).toBeInTheDocument();
  });
});
