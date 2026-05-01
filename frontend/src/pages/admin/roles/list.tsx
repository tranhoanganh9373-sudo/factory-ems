import { Card, Table, Tag } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { roleApi, RoleDTO } from '@/api/role';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { PageHeader } from '@/components/PageHeader';

export default function RoleListPage() {
  useDocumentTitle('系统管理 - 角色');
  const { data = [], isLoading } = useQuery({ queryKey: ['roles'], queryFn: roleApi.list });
  return (
    <>
      <PageHeader title="角色管理" />
      <Card>
        <Table<RoleDTO>
          rowKey="id"
          loading={isLoading}
          dataSource={data}
          pagination={false}
          columns={[
            {
              title: '编码',
              dataIndex: 'code',
              render: (c) => <Tag color={c === 'ADMIN' ? 'red' : 'blue'}>{c}</Tag>,
            },
            { title: '名称', dataIndex: 'name' },
            { title: '说明', dataIndex: 'description' },
          ]}
        />
      </Card>
    </>
  );
}
