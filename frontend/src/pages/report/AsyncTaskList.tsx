import { useEffect } from 'react';
import { App, Button, Space, Table, Tag, Tooltip } from 'antd';
import { DeleteOutlined, DownloadOutlined, QuestionCircleOutlined } from '@ant-design/icons';
import { HELP_ASYNC_EXPORT } from '@/components/pageHelp';
import { useQuery } from '@tanstack/react-query';
import type { ColumnsType } from 'antd/es/table';
import dayjs from 'dayjs';
import { getFileToken, triggerDownload, FileTokenDTO } from '@/api/report';
import { useReportTasksStore, ReportTask } from '@/stores/reportTasks';

const STATUS_COLOR: Record<ReportTask['status'], string> = {
  PENDING: 'processing',
  RUNNING: 'processing',
  READY: 'success',
  FAILED: 'error',
};

const STATUS_LABEL: Record<ReportTask['status'], string> = {
  PENDING: '等待中',
  RUNNING: '生成中',
  READY: '就绪',
  FAILED: '失败',
};

/** Per-row polling hook — only active while PENDING or RUNNING */
function useTaskPoller(task: ReportTask) {
  const { updateTask, removeTask } = useReportTasksStore();
  const { message } = App.useApp();

  const { data, error } = useQuery({
    queryKey: ['reportTask', task.token],
    queryFn: () => getFileToken(task.token),
    enabled: task.status === 'PENDING' || task.status === 'RUNNING',
    refetchInterval: 2000,
    retry: false,
  });

  useEffect(() => {
    if (!data) return;
    if (data instanceof Blob) {
      // Unexpected: READY blob arrived during polling — download immediately
      triggerDownload(data, task.filename);
      removeTask(task.token);
    } else {
      const dto = data as FileTokenDTO;
      if (dto.status === 'READY' || dto.status === 'FAILED') {
        updateTask(task.token, {
          status: dto.status,
          bytes: dto.bytes,
          error: dto.error,
        });
      }
    }
  }, [data]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (!error) return;
    const status = (error as { response?: { status?: number } })?.response?.status;
    if (status === 410) {
      message.warning(`任务 ${task.filename} 已过期，已移除`);
      removeTask(task.token);
    } else {
      updateTask(task.token, { status: 'FAILED', error: String(error) });
    }
  }, [error]); // eslint-disable-line react-hooks/exhaustive-deps
}

/** Render a <tr> while also running the polling hook for this row's task */
function PollingRow(
  props: React.HTMLAttributes<HTMLTableRowElement> & { 'data-row-key'?: string }
) {
  const token = props['data-row-key'];
  const task = useReportTasksStore((s) => s.tasks.find((t) => t.token === token));

  // Hooks must be called unconditionally — poller noops when task is undefined/settled
  const dummyTask: ReportTask = task ?? {
    token: '',
    filename: '',
    status: 'FAILED',
    createdAt: new Date().toISOString(),
  };
  useTaskPoller(dummyTask);

  return <tr {...props} />;
}

export default function AsyncTaskList() {
  const { tasks, removeTask } = useReportTasksStore();
  const { message } = App.useApp();

  async function handleDownload(task: ReportTask) {
    try {
      // 显式 download=true：让后端 evict + 返回 blob。轮询那条路保留 DTO 不动。
      const result = await getFileToken(task.token, true);
      if (result instanceof Blob) {
        triggerDownload(result, task.filename);
        removeTask(task.token); // server evicts after one read
      } else {
        message.error('文件尚未就绪，请稍后重试');
      }
    } catch (err: unknown) {
      const status = (err as { response?: { status?: number } })?.response?.status;
      if (status === 410) {
        message.warning('文件已过期');
        removeTask(task.token);
      } else {
        message.error('下载失败');
      }
    }
  }

  const columns: ColumnsType<ReportTask> = [
    {
      title: '文件名',
      dataIndex: 'filename',
      key: 'filename',
      ellipsis: true,
    },
    {
      title: '状态',
      key: 'status',
      width: 100,
      render: (_, r) => <Tag color={STATUS_COLOR[r.status]}>{STATUS_LABEL[r.status]}</Tag>,
    },
    {
      title: '创建时间',
      key: 'createdAt',
      width: 160,
      render: (_, r) => dayjs(r.createdAt).format('YYYY-MM-DD HH:mm:ss'),
    },
    {
      title: '大小',
      key: 'bytes',
      width: 90,
      render: (_, r) => (r.bytes != null ? `${(r.bytes / 1024).toFixed(1)} KB` : '—'),
    },
    {
      title: '操作',
      key: 'action',
      width: 130,
      render: (_, r) => (
        <Space>
          {r.status === 'READY' && (
            <Button
              size="small"
              type="primary"
              icon={<DownloadOutlined />}
              onClick={() => handleDownload(r)}
            >
              下载
            </Button>
          )}
          <Button size="small" danger icon={<DeleteOutlined />} onClick={() => removeTask(r.token)}>
            删除
          </Button>
        </Space>
      ),
    },
  ];

  if (tasks.length === 0) return null;

  return (
    <Table<ReportTask>
      rowKey="token"
      columns={columns}
      dataSource={tasks}
      pagination={false}
      size="small"
      title={() => (
        <Space>
          <strong>异步任务列表</strong>
          <Tooltip title={HELP_ASYNC_EXPORT} overlayStyle={{ maxWidth: 520 }} placement="bottomLeft">
            <QuestionCircleOutlined
              style={{ fontSize: 14, color: '#8c8c8c', cursor: 'help' }}
              aria-label="异步导出帮助"
            />
          </Tooltip>
        </Space>
      )}
      components={{
        body: {
          row: PollingRow,
        },
      }}
    />
  );
}
