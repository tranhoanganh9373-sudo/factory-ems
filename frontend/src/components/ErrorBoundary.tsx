import React from 'react';
import { Result, Button } from 'antd';

interface State {
  hasError: boolean;
  error?: Error;
}
export class ErrorBoundary extends React.Component<{ children: React.ReactNode }, State> {
  state: State = { hasError: false };
  static getDerivedStateFromError(error: Error): State {
    return { hasError: true, error };
  }
  componentDidCatch(error: Error, info: React.ErrorInfo) {
    // eslint-disable-next-line no-console
    console.error('UI error:', error, info);
  }
  render() {
    if (this.state.hasError) {
      return (
        <Result
          status="error"
          title="页面渲染出错"
          subTitle={this.state.error?.message}
          extra={<Button onClick={() => location.reload()}>刷新页面</Button>}
        />
      );
    }
    return this.props.children;
  }
}
