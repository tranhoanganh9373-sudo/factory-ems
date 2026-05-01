import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import LoginPage from './index';

describe('LoginPage', () => {
  it('renders system name, company subtitle, and login form fields', () => {
    render(
      <MemoryRouter>
        <LoginPage />
      </MemoryRouter>
    );
    expect(screen.getByText('能源管理系统')).toBeInTheDocument();
    expect(screen.getByText('松羽科技集团')).toBeInTheDocument();
    expect(screen.getByPlaceholderText('用户名')).toBeInTheDocument();
    expect(screen.getByPlaceholderText('密码')).toBeInTheDocument();
    // Ant Design Button wraps text in <span> with letter-spacing, accessible name becomes "登 录"
    expect(screen.getByRole('button', { name: '登 录' })).toBeInTheDocument();
  });
});
