import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App as AntdApp } from 'antd';
import List from './list';

describe('bills/list', () => {
  it('renders Chinese page title', () => {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={qc}>
        <AntdApp>
          <MemoryRouter>
            <List />
          </MemoryRouter>
        </AntdApp>
      </QueryClientProvider>
    );
    expect(screen.getByText('账单列表')).toBeInTheDocument();
  });
});
