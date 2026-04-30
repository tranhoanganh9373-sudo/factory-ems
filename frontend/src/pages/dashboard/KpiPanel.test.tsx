import { describe, it, expect } from 'vitest';
import { render } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import KpiPanel from './KpiPanel';

describe('KpiPanel', () => {
  it('renders without crash under default filter', () => {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={qc}>
        <KpiPanel />
      </QueryClientProvider>
    );
    // Either skeleton, error alert, empty, or data — but should not crash.
    expect(document.body).toBeDefined();
  });
});
