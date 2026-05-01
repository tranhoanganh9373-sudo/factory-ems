import { Drawer, Descriptions, Tag } from 'antd';
import { useQuery } from '@tanstack/react-query';
import dayjs from 'dayjs';
import { collectorDiagApi } from '@/api/collectorDiag';
import { translate, CONNECTION_STATE_LABEL, COLLECTOR_PROTOCOL_LABEL } from '@/utils/i18n-dict';

interface Props {
  channelId: number | null;
  onClose: () => void;
}

export function ChannelDetailDrawer({ channelId, onClose }: Props) {
  const { data } = useQuery({
    queryKey: ['collector', 'state', channelId],
    queryFn: () => (channelId !== null ? collectorDiagApi.get(channelId) : null),
    enabled: channelId !== null,
    refetchInterval: 3000,
  });

  return (
    <Drawer
      open={channelId !== null}
      onClose={onClose}
      width={520}
      title={data ? `通道 #${data.channelId} 详情` : '详情'}
      destroyOnClose
    >
      {data && (
        <Descriptions column={1} bordered size="small">
          <Descriptions.Item label="协议">
            <Tag>{translate(COLLECTOR_PROTOCOL_LABEL, data.protocol)}</Tag>
          </Descriptions.Item>
          <Descriptions.Item label="状态">
            {translate(CONNECTION_STATE_LABEL, data.connState)}
          </Descriptions.Item>
          <Descriptions.Item label="最近连接">
            {data.lastConnectAt ? dayjs(data.lastConnectAt).format('YYYY-MM-DD HH:mm:ss') : '-'}
          </Descriptions.Item>
          <Descriptions.Item label="最近成功">
            {data.lastSuccessAt ? dayjs(data.lastSuccessAt).format('YYYY-MM-DD HH:mm:ss') : '-'}
          </Descriptions.Item>
          <Descriptions.Item label="最近失败">
            {data.lastFailureAt ? dayjs(data.lastFailureAt).format('YYYY-MM-DD HH:mm:ss') : '-'}
          </Descriptions.Item>
          <Descriptions.Item label="24h 成功">{data.successCount24h}</Descriptions.Item>
          <Descriptions.Item label="24h 失败">{data.failureCount24h}</Descriptions.Item>
          <Descriptions.Item label="平均延迟">{data.avgLatencyMs} ms</Descriptions.Item>
          <Descriptions.Item label="最后错误">{data.lastErrorMessage ?? '-'}</Descriptions.Item>
          <Descriptions.Item label="协议元信息">
            <pre style={{ margin: 0, whiteSpace: 'pre-wrap' }}>
              {JSON.stringify(data.protocolMeta, null, 2)}
            </pre>
          </Descriptions.Item>
        </Descriptions>
      )}
    </Drawer>
  );
}
