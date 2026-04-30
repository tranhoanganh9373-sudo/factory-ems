import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App as AntdApp } from 'antd';
import Page from './shifts';

describe('production/shifts', () => {
  it('renders Chinese page title', () => {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={qc}>
        <AntdApp>
          <MemoryRouter>
            <Page />
          </MemoryRouter>
        </AntdApp>
      </QueryClientProvider>,
    );
    expect(screen.getByText('班次管理')).toBeInTheDocument();
  });
});
