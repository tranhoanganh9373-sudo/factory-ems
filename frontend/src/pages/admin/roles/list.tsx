import { Card, Table, Tag } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { roleApi, RoleDTO } from '@/api/role';

export default function RoleListPage() {
  const { data = [], isLoading } = useQuery({ queryKey: ['roles'], queryFn: roleApi.list });
  return (
    <Card title="角色管理 (只读)">
      <Table<RoleDTO>
        rowKey="id"
        loading={isLoading}
        dataSource={data}
        pagination={false}
        columns={[
          {
            title: 'Code',
            dataIndex: 'code',
            render: (c) => <Tag color={c === 'ADMIN' ? 'red' : 'blue'}>{c}</Tag>,
          },
          { title: '名称', dataIndex: 'name' },
          { title: '说明', dataIndex: 'description' },
        ]}
      />
    </Card>
  );
}
