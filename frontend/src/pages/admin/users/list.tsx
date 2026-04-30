import { useState } from 'react';
import {
  Card,
  Table,
  Button,
  Space,
  Input,
  Tag,
  Popconfirm,
  message,
  Modal,
  Form,
  Select,
} from 'antd';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { PageHeader } from '@/components/PageHeader';
import { PlusOutlined, KeyOutlined, DeleteOutlined, SafetyOutlined } from '@ant-design/icons';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { userApi, UserDTO } from '@/api/user';
import { roleApi } from '@/api/role';
import { Link } from 'react-router-dom';

export default function UserListPage() {
  useDocumentTitle('系统管理 - 用户');
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(1);
  const [createOpen, setCreateOpen] = useState(false);
  const qc = useQueryClient();
  const { data, isLoading } = useQuery({
    queryKey: ['users', page, keyword],
    queryFn: () => userApi.list(page, 20, keyword || undefined),
  });
  const { data: roles = [] } = useQuery({ queryKey: ['roles'], queryFn: roleApi.list });

  const [form] = Form.useForm();
  const createMut = useMutation({
    mutationFn: userApi.create,
    onSuccess: () => {
      message.success('已创建');
      qc.invalidateQueries({ queryKey: ['users'] });
      setCreateOpen(false);
      form.resetFields();
    },
  });
  const delMut = useMutation({
    mutationFn: (id: number) => userApi.delete(id),
    onSuccess: () => {
      message.success('已删除');
      qc.invalidateQueries({ queryKey: ['users'] });
    },
  });

  const [resetFor, setResetFor] = useState<UserDTO | null>(null);
  const resetMut = useMutation({
    mutationFn: ({ id, pwd }: { id: number; pwd: string }) => userApi.resetPassword(id, pwd),
    onSuccess: () => {
      message.success('密码已重置');
      setResetFor(null);
    },
  });

  return (
    <>
      <PageHeader title="用户管理" />
      <Card
        extra={
          <Space>
            <Input.Search
              placeholder="搜索用户名/姓名"
              allowClear
              onSearch={(v) => {
                setKeyword(v);
                setPage(1);
              }}
              style={{ width: 240 }}
            />
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
              新建
            </Button>
          </Space>
        }
      >
      <Table<UserDTO>
        rowKey="id"
        loading={isLoading}
        dataSource={data?.items ?? []}
        pagination={{ current: page, pageSize: 20, total: data?.total ?? 0, onChange: setPage }}
        columns={[
          { title: '用户名', dataIndex: 'username' },
          { title: '姓名', dataIndex: 'displayName' },
          {
            title: '角色',
            dataIndex: 'roles',
            render: (rs: string[]) =>
              rs.map((r) => (
                <Tag key={r} color={r === 'ADMIN' ? 'red' : 'blue'}>
                  {r}
                </Tag>
              )),
          },
          {
            title: '状态',
            dataIndex: 'enabled',
            render: (e) => (e ? <Tag color="green">启用</Tag> : <Tag>禁用</Tag>),
          },
          { title: '最近登录', dataIndex: 'lastLoginAt' },
          {
            title: '操作',
            key: 'ops',
            render: (_, u) => (
              <Space>
                <Link to={`/admin/users/${u.id}`}>编辑</Link>
                <Link to={`/admin/users/${u.id}/permissions`}>
                  <SafetyOutlined /> 权限
                </Link>
                <Button size="small" icon={<KeyOutlined />} onClick={() => setResetFor(u)}>
                  重置密码
                </Button>
                <Popconfirm
                  title={`确认删除 ${u.username}？`}
                  onConfirm={() => delMut.mutate(u.id)}
                >
                  <Button size="small" danger icon={<DeleteOutlined />}>
                    删除
                  </Button>
                </Popconfirm>
              </Space>
            ),
          },
        ]}
      />

      <Modal
        title="新建用户"
        open={createOpen}
        onCancel={() => setCreateOpen(false)}
        onOk={() => form.validateFields().then((v) => createMut.mutate(v))}
        confirmLoading={createMut.isPending}
        destroyOnClose
      >
        <Form form={form} layout="vertical">
          <Form.Item
            name="username"
            label="用户名"
            rules={[
              { required: true, min: 3, max: 64 },
              { pattern: /^[A-Za-z0-9_.-]+$/, message: '只允许字母数字 _ . -' },
            ]}
          >
            <Input />
          </Form.Item>
          <Form.Item name="password" label="初始密码" rules={[{ required: true, min: 8, max: 64 }]}>
            <Input.Password />
          </Form.Item>
          <Form.Item name="displayName" label="姓名" rules={[{ max: 128 }]}>
            <Input />
          </Form.Item>
          <Form.Item name="roleCodes" label="角色" initialValue={['VIEWER']}>
            <Select
              mode="multiple"
              options={roles.map((r) => ({ label: r.name, value: r.code }))}
            />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={`重置 ${resetFor?.username} 的密码`}
        open={!!resetFor}
        onCancel={() => setResetFor(null)}
        onOk={() => {
          const pwd = (document.getElementById('reset-new-pwd') as HTMLInputElement)?.value;
          if (!pwd || pwd.length < 8) {
            message.error('至少 8 位');
            return;
          }
          resetMut.mutate({ id: resetFor!.id, pwd });
        }}
        confirmLoading={resetMut.isPending}
      >
        <Input.Password id="reset-new-pwd" placeholder="新密码" />
      </Modal>
    </Card>
    </>
  );
}
