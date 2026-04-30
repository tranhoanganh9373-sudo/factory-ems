import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import AppLayout from './AppLayout';
import { useAuthStore } from '@/stores/authStore';

describe('AppLayout', () => {
  it('renders header brand lockup with system name', () => {
    useAuthStore.setState({
      user: { id: 1, username: 'tester', roles: ['ADMIN'] } as any,
      accessToken: 'x',
    });
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={['/home']}>
          <AppLayout />
        </MemoryRouter>
      </QueryClientProvider>
    );
    expect(screen.getByText('能源管理系统')).toBeInTheDocument();
  });
});
