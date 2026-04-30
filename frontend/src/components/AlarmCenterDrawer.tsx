import { Drawer, List, Tag, Button, Space, Empty, App } from 'antd';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { alarmApi, type AlarmListItemDTO } from '@/api/alarm';
import { useAuthStore } from '@/stores/authStore';

interface Props {
  open: boolean;
  onClose: () => void;
}

export function AlarmCenterDrawer({ open, onClose }: Props) {
  const { message } = App.useApp();
  const nav = useNavigate();
  const qc = useQueryClient();
  const { hasRole } = useAuthStore();
  const isAdmin = hasRole('ADMIN');

  const { data, isLoading } = useQuery({
    queryKey: ['alarms', 'recent-active'],
    queryFn: () => alarmApi.list({ status: 'ACTIVE', page: 1, size: 20 }),
    enabled: open,
    refetchOnWindowFocus: false,
  });

  const ack = useMutation({
    mutationFn: alarmApi.ack,
    onSuccess: () => {
      message.success('已确认');
      qc.invalidateQueries({ queryKey: ['alarms'] });
    },
    onError: (e: unknown) => {
      const msg = e instanceof Error ? e.message : '确认失败';
      message.error(msg);
    },
  });

  const items = data?.items ?? [];

  return (
    <Drawer
      title={`告警中心（${items.length}）`}
      open={open}
      onClose={onClose}
      width={440}
      placement="right"
    >
      {!isLoading && items.length === 0 ? (
        <Empty description="当前无活动告警" />
      ) : (
        <List
          loading={isLoading}
          dataSource={items}
          renderItem={(a: AlarmListItemDTO) => (
            <List.Item
              actions={[
                isAdmin && (
                  <Button
                    key="ack"
                    size="small"
                    loading={ack.isPending && ack.variables === a.id}
                    onClick={() => ack.mutate(a.id)}
                  >
                    确认
                  </Button>
                ),
                <Button
                  key="go"
                  type="link"
                  size="small"
                  onClick={() => {
                    nav(`/alarms/history?id=${a.id}`);
                    onClose();
                  }}
                >
                  详情
                </Button>,
              ].filter(Boolean) as React.ReactNode[]}
            >
              <List.Item.Meta
                title={
                  <Space>
                    <Tag color="error">告警</Tag>
                    <span>{a.deviceCode}</span>
                    <span style={{ color: '#888' }}>{a.deviceName}</span>
                  </Space>
                }
                description={
                  <Space size="small" direction="vertical" style={{ width: '100%' }}>
                    <span>{a.alarmType === 'SILENT_TIMEOUT' ? '采集中断' : '连续失败'}</span>
                    <span style={{ color: '#888', fontSize: 12 }}>触发：{a.triggeredAt}</span>
                    {a.lastSeenAt && (
                      <span style={{ color: '#888', fontSize: 12 }}>最后数据：{a.lastSeenAt}</span>
                    )}
                  </Space>
                }
              />
            </List.Item>
          )}
        />
      )}
    </Drawer>
  );
}
