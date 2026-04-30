import { Card, Form, Input, Switch, Select, Button, message, Spin } from 'antd';
import { useParams, useNavigate } from 'react-router-dom';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { PageHeader } from '@/components/PageHeader';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { userApi } from '@/api/user';
import { roleApi } from '@/api/role';
import { useEffect } from 'react';

export default function UserEditPage() {
  useDocumentTitle('系统管理 - 编辑用户');
  const { id } = useParams();
  const userId = Number(id);
  const [form] = Form.useForm();
  const qc = useQueryClient();
  const nav = useNavigate();

  const { data: user, isLoading } = useQuery({
    queryKey: ['user', userId],
    queryFn: () => userApi.getById(userId),
    enabled: !!userId,
  });
  const { data: roles = [] } = useQuery({ queryKey: ['roles'], queryFn: roleApi.list });

  useEffect(() => {
    if (user) form.setFieldsValue(user);
  }, [user, form]);

  const updateMut = useMutation({
    mutationFn: (v: { displayName?: string; enabled?: boolean }) => userApi.update(userId, v),
    onSuccess: () => {
      message.success('基础信息已更新');
      qc.invalidateQueries({ queryKey: ['user', userId] });
    },
  });
  const rolesMut = useMutation({
    mutationFn: (codes: string[]) => userApi.assignRoles(userId, codes),
    onSuccess: () => {
      message.success('角色已更新');
      qc.invalidateQueries({ queryKey: ['user', userId] });
    },
  });

  if (isLoading) return <Spin />;
  if (!user) return null;

  return (
    <>
      <PageHeader title="编辑用户" />
      <Card extra={<Button onClick={() => nav(-1)}>返回</Button>}>
      <Form form={form} layout="vertical" style={{ maxWidth: 480 }}>
        <Form.Item label="用户名">
          <Input disabled value={user.username} />
        </Form.Item>
        <Form.Item name="displayName" label="姓名" rules={[{ max: 128 }]}>
          <Input />
        </Form.Item>
        <Form.Item name="enabled" label="启用" valuePropName="checked">
          <Switch />
        </Form.Item>
        <Button
          type="primary"
          onClick={() =>
            form
              .validateFields()
              .then((v) => updateMut.mutate({ displayName: v.displayName, enabled: v.enabled }))
          }
          loading={updateMut.isPending}
        >
          保存基础信息
        </Button>
      </Form>

      <Card type="inner" title="角色" style={{ marginTop: 24, maxWidth: 480 }}>
        <Form.Item label="角色" name="roleCodes" initialValue={user.roles}>
          <Select
            mode="multiple"
            options={roles.map((r) => ({ label: r.name, value: r.code }))}
            defaultValue={user.roles}
            onChange={(codes) => rolesMut.mutate(codes)}
          />
        </Form.Item>
      </Card>
    </Card>
    </>
  );
}
