import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { BrandLockup } from './BrandLockup';

describe('BrandLockup', () => {
  it('header variant shows pill logo + 能源管理系统', () => {
    render(<BrandLockup variant="header" />);
    expect(screen.getByAltText('松羽科技集团')).toBeInTheDocument();
    expect(screen.getByText('能源管理系统')).toBeInTheDocument();
  });

  it('login variant shows large logo + system + company subtitle', () => {
    render(<BrandLockup variant="login" />);
    expect(screen.getByAltText('松羽科技集团')).toBeInTheDocument();
    expect(screen.getByText('能源管理系统')).toBeInTheDocument();
    expect(screen.getByText('松羽科技集团')).toBeInTheDocument();
  });
});
