import { Card, Form, Input, Button, message } from 'antd';
import { authApi } from '@/api/auth';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { PageHeader } from '@/components/PageHeader';

export default function ProfilePage() {
  useDocumentTitle('个人中心');
  const [form] = Form.useForm();
  const onFinish = async (v: { oldPassword: string; newPassword: string; confirm: string }) => {
    if (v.newPassword !== v.confirm) {
      message.error('两次新密码不一致');
      return;
    }
    try {
      await authApi.changePassword(v.oldPassword, v.newPassword);
      message.success('密码已更新');
      form.resetFields();
    } catch {
      // 拦截器已弹消息
    }
  };
  return (
    <>
      <PageHeader title="个人中心" />
      <Card title="修改密码" style={{ maxWidth: 480 }}>
      <Form form={form} layout="vertical" onFinish={onFinish}>
        <Form.Item name="oldPassword" label="原密码" rules={[{ required: true }]}>
          <Input.Password />
        </Form.Item>
        <Form.Item name="newPassword" label="新密码" rules={[{ required: true, min: 8, max: 64 }]}>
          <Input.Password />
        </Form.Item>
        <Form.Item name="confirm" label="确认新密码" rules={[{ required: true }]}>
          <Input.Password />
        </Form.Item>
        <Button type="primary" htmlType="submit">
          保存
        </Button>
      </Form>
    </Card>
    </>
  );
}
