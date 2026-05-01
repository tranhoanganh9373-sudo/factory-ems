import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { ThemeToggle } from './ThemeToggle';
import { useThemeStore } from '@/stores/themeStore';

describe('ThemeToggle', () => {
  beforeEach(() => useThemeStore.setState({ mode: 'light' }));

  it('renders aria label for switching to dark when light', () => {
    render(<ThemeToggle />);
    expect(screen.getByLabelText('切换到深色模式')).toBeInTheDocument();
  });

  it('clicking flips mode', () => {
    render(<ThemeToggle />);
    fireEvent.click(screen.getByLabelText('切换到深色模式'));
    expect(useThemeStore.getState().mode).toBe('dark');
  });
});
