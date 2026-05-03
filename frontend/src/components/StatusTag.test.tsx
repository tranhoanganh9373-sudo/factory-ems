import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { StatusTag } from './StatusTag';

describe('StatusTag', () => {
  it.each([
    ['success', '在线'],
    ['warning', '报警'],
    ['error', '严重'],
    ['info', '已确认'],
    ['default', '离线'],
  ] as const)('renders %s tone with text "%s"', (tone, label) => {
    render(<StatusTag tone={tone}>{label}</StatusTag>);
    expect(screen.getByText(label)).toBeInTheDocument();
  });
});
