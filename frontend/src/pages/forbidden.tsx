import { Result, Button } from 'antd';
import { useNavigate } from 'react-router-dom';
export default function ForbiddenPage() {
  const nav = useNavigate();
  return (
    <Result
      status="403"
      title="403"
      subTitle="您无权访问此页面"
      extra={
        <Button type="primary" onClick={() => nav('/')}>
          返回首页
        </Button>
      }
    />
  );
}
